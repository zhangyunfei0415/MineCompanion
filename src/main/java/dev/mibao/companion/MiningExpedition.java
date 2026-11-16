package dev.mibao.companion;

import static dev.mibao.companion.ActionResult.Code.*;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded player mining lifecycle, using the existing atomic operations and permissions. */
public final class MiningExpedition extends Actions.Job {
  private static MiningExpedition latest;
  final MiningSpec spec;
  final MiningSurvey survey;
  private final String id;
  private final long deadline;
  private final MiningLedger ledger = new MiningLedger();
  private final MiningDrops drops = new MiningDrops();
  private final Set<BlockPos> broken = new LinkedHashSet<>();
  private final Set<BlockPos> batchBroken = new LinkedHashSet<>();
  private final Map<BlockPos, BlockState> issued = new LinkedHashMap<>();
  private final TaskCard card = new TaskCard();
  private final BlockPos home;
  private final JsonObject definition;
  private final JsonObject request;
  private InventorySession sync = new InventorySession(false);
  private Map<String, Integer> baseline = Map.of(), batchBefore = Map.of();
  private List<BlockPos> batch = List.of();
  private BlockPos workReturn, focus;
  private String phase = "prepare", finishReason = "", lastChild = "";
  String lastGuard = "";
  private BlockPos lastGuardCell;
  private int craftAttempts,
      failedBatches,
      trips,
      litAt = -8,
      foodSupplies,
      torchSupplies,
      moveFailures;
  private boolean pending, ended, finishing, compacted;
  private boolean unknownWrite;
  private String childTool = "";

  MiningExpedition(JsonObject args, Consumer<String> done) {
    super(done);
    TaskReadiness.requireWorld(true);
    if (active() != null) throw new IllegalArgumentException("[MINING_BUSY] 当前采矿作业仍在运行，请先停止或查询");
    var normalized = args.deepCopy();
    if (!normalized.has("delivery"))
      normalized.addProperty("delivery", Store.data.resourceDelivery);
    if (!normalized.has("source")) normalized.addProperty("source", Store.data.productionSource);
    spec = MiningSpec.parse(normalized);
    if (spec.terrain().equals("modify") && !NavigationPermissions.canBreak())
      throw new IllegalArgumentException(
          "[NAVIGATION_BREAK_DISABLED] F8 / 设置 / 权限与行为：关闭仅绕行，并允许导航破坏及采集，或选择完全放权；terrain=detour可绕行");
    id = Agent.str(args, "job_id", UUID.randomUUID().toString());
    if (!id.matches("[A-Za-z0-9_.-]{1,80}"))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] job_id为1..80字母数字/._-");
    boolean resume = args.has("resume") && args.get("resume").getAsBoolean();
    definition = Store.GSON.toJsonTree(spec).getAsJsonObject();
    request = normalized.deepCopy();
    request.remove("resume");
    request.remove("job_id");
    String key = "mining:" + id;
    var old = Store.data.checkpoints.get(key);
    if (old != null && !resume)
      throw new IllegalArgumentException("[MINING_ID_EXISTS] 先mining_status查询或明确resume=true");
    if (resume && old == null) throw new IllegalArgumentException("[MINING_JOB_UNKNOWN] 没有此作业检查点");
    BlockPos origin = mc().player.blockPosition();
    if (old != null) {
      if (!Agent.str(old, "dimension", "").equals(dimension)
          || !old.getAsJsonObject("definition").equals(definition))
        throw new IllegalArgumentException("[MINING_RESUME_CONTEXT] 世界、维度或任务参数与检查点不同");
      origin = position(old.getAsJsonArray("origin"));
      baseline = quantities(old.getAsJsonObject("baseline"));
      ledger.received.putAll(quantities(old.getAsJsonObject("received")));
      ledger.delivered.putAll(quantities(old.getAsJsonObject("delivered")));
      for (var value : old.getAsJsonArray("broken")) broken.add(position(value.getAsJsonArray()));
    } else if (Store.data.checkpoints.keySet().stream().filter(k -> k.startsWith("mining:")).count()
        >= 128) throw new IllegalArgumentException("[MINING_LEDGER_FULL] 已有128项作业，请备份整理检查点");
    home = old == null ? origin : position(old.getAsJsonArray("home"));
    workReturn = mc().player.blockPosition();
    survey = new MiningSurvey(spec, origin);
    survey.completed(broken);
    deadline = System.nanoTime() + spec.seconds() * 1_000_000_000L;
    card.kind = "mining";
    card.title = "采矿作业 · " + spec.goal();
    card.item = spec.item();
    card.target = spec.count();
    latest = this;
    RestockTask.latest = card;
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  private static BlockPos position(JsonArray array) {
    return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
  }

  private static Map<String, Integer> quantities(JsonObject object) {
    Map<String, Integer> result = new LinkedHashMap<>();
    if (object != null)
      object.entrySet().forEach(e -> result.put(e.getKey(), e.getValue().getAsInt()));
    return result;
  }

  private static JsonArray coordinates(BlockPos pos) {
    return Store.GSON.toJsonTree(new int[] {pos.getX(), pos.getY(), pos.getZ()}).getAsJsonArray();
  }

  static MiningExpedition active() {
    return latest != null
            && !latest.ended
            && latest.epoch == Agent.epoch
            && Agent.enabled
            && latest.world == mc().level
            && latest.scope.equals(Store.scope())
        ? latest
        : null;
  }

  static boolean navigationTerrain() {
    var current = active();
    return current == null || current.spec.terrain().equals("modify") && current.withinWorkArea();
  }

  static boolean expire() {
    var current = active();
    if (current == null || System.nanoTime() <= current.deadline) return false;
    if (current.pending) Agent.cancelPendingMiningStep();
    if (current.pending && current.childTool.equals("craft_item") && Agent.production != null) {
      Agent.production.cancel();
      Agent.production = null;
    }
    if (Actions.job != null && Actions.job != current) Actions.job.stop();
    Actions.job = current;
    current.pending = false;
    current.end(TIME_BUDGET, "采矿时间预算结束，保留已核实产物和当前现场");
    return true;
  }

  static boolean allowsPlacement(BlockPos pos) {
    var current = active();
    if (current == null || Agent.paused || Cooperation.yielding() || !current.phase.equals("mine"))
      return true;
    var config = Store.config;
    boolean full = config.fullGameControl || config.permission.equals("FULL");
    return current.spec.terrain().equals("modify")
        && current.survey.contains(pos)
        && NavigationPermissions.canPlace()
        && (full || config.allowPlace);
  }

  static void cancelActive() {
    if (latest != null && !latest.ended) {
      latest.ended = true;
      latest.phase = "cancelled";
      latest.card.outcome = "cancelled";
      latest.card.ended = System.currentTimeMillis();
      latest.updateCard();
      latest.checkpoint("cancelled", latest.pending || latest.unknownWrite);
    }
  }

  int brokenCount() {
    return broken.size();
  }

  BlockPos target() {
    return focus;
  }

  boolean withinWorkArea() {
    return survey.contains(mc().player.blockPosition());
  }

  static String guard(BlockPos pos) {
    var job = active();
    if (job == null || Agent.paused || Cooperation.yielding()) return "";
    String reason = job.breakReason(pos);
    if (!reason.isBlank()) {
      job.lastGuard = reason + " @ " + pos.toShortString();
      job.lastGuardCell = pos.immutable();
      return reason;
    }
    if (job.phase.equals("mine")) {
      var state = mc().level.getBlockState(pos);
      if (job.survey.matches(state) && job.issued.size() < 256)
        job.issued.putIfAbsent(pos.immutable(), state);
    }
    return "";
  }

  /** Called before vanilla prediction or packet creation, including instant-break blocks. */
  public static boolean permitBreak(BlockPos pos) {
    return guard(pos).isBlank();
  }

  private String breakReason(BlockPos pos) {
    if (Agent.paused || !Agent.enabled) return "MINING_PAUSED";
    var config = Store.config;
    if (!config.fullGameControl && config.permission.equals("LIMITED") && !config.allowMine)
      return "POLICY_DENIED";
    if (!survey.contains(pos)) return "MINING_BOUNDARY";
    if (!mc().level.hasChunkAt(pos)) return "UNLOADED_TARGET";
    var state = mc().level.getBlockState(pos);
    if (state.isAir()) return "";
    if (state.hasBlockEntity()
        || !BreakPolicy.denial(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(state.getBlock())
                    .toString(),
                false,
                false,
                false,
                false)
            .isBlank()) return "BLOCK_PROTECTED";
    if (!survey.matches(state) && !spec.terrain().equals("modify")) return "MINING_DETOUR_ONLY";
    if (!survey.matches(state) && !NavigationPermissions.canBreak())
      return "NAVIGATION_BREAK_DISABLED";
    if (survey.matches(state)
        && (broken.size() >= spec.maxBlocks()
            || spec.goal().equals("blocks") && broken.size() >= spec.count()))
      return "MINING_BLOCK_BUDGET";
    if (!state.getFluidState().isEmpty() || pos.equals(mc().player.blockPosition().below()))
      return "MINING_SUPPORT_OR_FLUID";
    for (var direction : Direction.values()) {
      var neighbor = pos.relative(direction);
      if (!mc().level.hasChunkAt(neighbor)
          || !mc().level.getFluidState(neighbor).isEmpty()
          || mc().level.getBlockState(neighbor).is(net.minecraft.world.level.block.Blocks.FIRE)
          || mc().level
              .getBlockState(neighbor)
              .is(net.minecraft.world.level.block.Blocks.SOUL_FIRE))
        return "MINING_NEIGHBOR_UNKNOWN_OR_FLUID";
    }
    var held = mc().player.getMainHandItem();
    if (survey.matches(state)
        && (MiningTools.choose(state, spec.toolPolicy(), spec.durability()) < 0
            || !MiningTools.matchesPolicy(held, spec.toolPolicy())
            || state.requiresCorrectToolForDrops() && !held.isCorrectToolForDrops(state)
            || held.isDamageableItem()
                && held.getMaxDamage() - held.getDamageValue() <= spec.durability()))
      return "MINING_TOOL_REQUIRED";
    return "";
  }

  static void blockReceipt(BlockPos pos, BlockState state) {
    var job = active();
    if (job != null
        && job.phase.equals("mine")
        && state.isAir()
        && job.issued.containsKey(pos)
        && mc().level.hasChunkAt(pos)
        && mc().level.getBlockState(pos).isAir()) {
      job.broken.add(pos.immutable());
      job.batchBroken.add(pos.immutable());
    }
  }

  static void observeItem(int entityId) {
    var job = active();
    if (job != null && (job.phase.equals("mine") || job.phase.equals("collect")))
      job.drops.observe(entityId, job.issued.keySet());
  }

  static void pickup(int item, int player, int amount) {
    var job = active();
    if (job != null) job.drops.pickup(item, player, amount);
  }

  static Actions.Job batch(JsonObject args, Consumer<String> done) {
    var job = require(args, "mine");
    int amount = Math.min(4, job.spec.maxBlocks() - job.broken.size());
    if (job.spec.goal().equals("blocks"))
      amount = Math.min(amount, job.spec.count() - job.broken.size());
    if (job.spec.mode().equals("baritone")) return new BaritoneMiningJob(job, amount, done);
    return new MiningJob(job.spec.block(), Math.min(amount, job.batch.size()), 24, done)
        .targets(
            job.batch, mc().player.blockPosition(), job.spec.durability(), job.spec.toolPolicy());
  }

  static Actions.Job collection(JsonObject args, Consumer<String> done) {
    var job = require(args, "collect");
    return new CollectionJob(
        "*", Integer.MAX_VALUE, 28, mc().player.blockPosition(), job.drops::eligible, true, done);
  }

  private static MiningExpedition require(JsonObject args, String phase) {
    var job = active();
    if (job == null
        || !job.id.equals(Agent.str(args, "expedition_id", ""))
        || !job.pending
        || !job.phase.equals(phase))
      throw new IllegalArgumentException("[MINING_CONTEXT] 当前采矿批次已失效");
    return job;
  }

  @Override
  void tick() {
    if (pending || ended) return;
    updateCard();
    Actions.status = "采矿作业 · " + phase + " · " + progress() + " / " + spec.count();
    RestockTask.latest = card;
    if (System.nanoTime() > deadline) {
      end(TIME_BUDGET, "采矿时间预算结束");
      return;
    }
    if (phase.equals("prepare")) {
      int ready = sync.tick();
      if (ready == 0) return;
      if (ready < 0) {
        end(MENU_NOT_SYNCED, sync.error);
        return;
      }
      sync.close();
      sync = null;
      if (baseline.isEmpty() && broken.isEmpty() && ledger.received.isEmpty())
        baseline = Map.copyOf(PlayerEvidence.actual("*"));
      var cargo = ledger.cargo(baseline, PlayerEvidence.actual("*"));
      if (total(cargo) < total(ledger.received) - total(ledger.delivered)) {
        end(MINING_CARGO_CHANGED, "检查点的未交付产物与当前背包不符，请先核实库存");
        return;
      }
      phase = "scan";
      checkpoint(phase, false);
      return;
    }
    if (phase.equals("scan")) {
      if (!survey.advance()) {
        card.next = "分帧扫描 " + survey.scanned() + " 格";
        return;
      }
      choose();
      return;
    }
    if (phase.equals("choose")) {
      choose();
      return;
    }
    if (phase.equals("collect")) {
      CollectionJob.lastReceipt = null;
      dispatch(
          "collect_mined",
          batchArgs(),
          result -> {
            var receipt = CollectionJob.lastReceipt;
            if (receipt == null || receipt.epoch() != epoch || !receipt.inventoryVerified()) {
              end(MINING_PICKUP_UNCONFIRMED, result);
              return;
            }
            ledger.credit(batchBefore, receipt.inventory(), drops.pickups(batchBroken), true);
            survey.completed(batchBroken);
            survey.reject(batch.stream().filter(p -> !batchBroken.contains(p)).toList());
            failedBatches = batchBroken.isEmpty() ? failedBatches + 1 : 0;
            workReturn = mc().player.blockPosition();
            checkpoint("batch_verified", false);
            if (failedBatches >= 3) {
              end(MINING_NO_PROGRESS, "连续三批没有确认挖除，" + lastChild);
              return;
            }
            phase = "choose";
          });
      return;
    }
    if (phase.equals("unload")) {
      unload();
      return;
    }
    if (phase.equals("return")) {
      var target = finishing ? home : workReturn;
      var args = location(target);
      dispatch(
          "go_to",
          args,
          result -> {
            if (Rules.actionFailed(result)) {
              end(MINING_RETURN_FAILED, result);
              return;
            }
            if (finishing) end(NONE, finishReason);
            else phase = "choose";
          });
    }
  }

  private void choose() {
    boolean goalMet = progress() >= spec.count();
    if (spec.goal().equals("deliver")
        && ledger.progress("items", broken.size(), this::matchesOutput) >= spec.count()) {
      finishing = true;
      finishReason = "已采足目标产物，正在交付";
      phase = "unload";
      return;
    }
    if (goalMet || broken.size() >= spec.maxBlocks()) {
      finishWork(goalMet ? "目标数量已核实" : "方块预算已用完");
      return;
    }
    if (mc().player.getFoodData().getFoodLevel() < 14) {
      boolean food = false;
      for (int i = 0; i < 36; i++) {
        var stack = mc().player.getInventory().getItem(i);
        if (ControlPolicy.FOOD.contains(
            net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem())
                .toString())) food = true;
      }
      if (food) {
        dispatch(
            "eat_food",
            new JsonObject(),
            r -> {
              if (Rules.actionFailed(r)) end(MINING_FOOD_UNCONFIRMED, r);
              else phase = "choose";
            });
        return;
      }
      if (!spec.source().isBlank() && foodSupplies++ < 2) {
        supply("minecraft:bread", 4);
        return;
      }
      end(MINING_FOOD_REQUIRED, "采矿补给不足，需要普通食物或指定补料箱中的面包");
      return;
    }
    if (freeSlots() < spec.freeSlots()) {
      if (!compacted) {
        compacted = true;
        dispatch("organize_inventory", new JsonObject(), r -> phase = "choose");
        return;
      }
      if (!spec.delivery().isBlank()
          && !ledger.cargo(baseline, PlayerEvidence.actual("*")).isEmpty()) {
        phase = "unload";
        return;
      }
      end(NO_CAPACITY, "背包空间不足，当前没有可交付的本批产物或未指定交付箱");
      return;
    }
    compacted = false;
    var targets = survey.targets(4, focus == null ? mc().player.blockPosition() : focus);
    if (targets.isEmpty()) {
      finishWork("当前已加载区域没有更多可用露头，未知采样=" + survey.unknown);
      return;
    }
    focus = targets.get(0);
    if (focus.distSqr(mc().player.blockPosition()) > 20 * 20) {
      var landing = ColumnProbe.nearest(focus.getX(), focus.getZ(), focus.getY(), 4);
      if (landing == null) {
        survey.reject(List.of(focus));
        phase = "choose";
        return;
      }
      dispatch(
          "go_to",
          location(landing),
          r -> {
            if (Rules.actionFailed(r)) {
              survey.reject(List.of(focus));
              if (++moveFailures >= 8) {
                end(MINING_NO_PATH, "八个矿点接近失败，" + r);
                return;
              }
            }
            phase = "choose";
          });
      return;
    }
    batch =
        targets.stream().filter(p -> p.distSqr(mc().player.blockPosition()) <= 24 * 24).toList();
    var state = mc().level.getBlockState(focus);
    if (MiningTools.choose(state, spec.toolPolicy(), spec.durability()) < 0) {
      if (!spec.craft() || !spec.toolPolicy().equals("auto") || ++craftAttempts > 2) {
        end(MINING_TOOL_REQUIRED, "缺少满足采集等级、附魔偏好和耐久预留的工具");
        return;
      }
      String recipe = MiningTools.craft(state, spec.durability());
      if (recipe.isBlank()) {
        end(MINING_TOOL_REQUIRED, "没有适用的普通镐制作方案");
        return;
      }
      var args = new JsonObject();
      args.addProperty("item", recipe);
      args.addProperty("count", 1);
      if (!spec.source().isBlank()) args.addProperty("source", spec.source());
      dispatch(
          "craft_item",
          args,
          r -> {
            if (Rules.actionFailed(r)) end(MINING_TOOL_PREPARATION_FAILED, r);
            else phase = "choose";
          });
      return;
    }
    if (spec.lighting()
        && broken.size() - litAt >= 8
        && mc().level
                .getBrightness(
                    net.minecraft.world.level.LightLayer.BLOCK, mc().player.blockPosition())
            < 8
        && mc().player
            .getInventory()
            .contains(new ItemStack(net.minecraft.world.item.Items.TORCH))) {
      litAt = broken.size();
      var args = new JsonObject();
      args.addProperty("radius", 3);
      args.addProperty("vertical_range", 1);
      args.addProperty("count", 1);
      dispatch("illuminate_area", args, r -> phase = "choose");
      return;
    }
    if (spec.lighting()
        && !spec.source().isBlank()
        && torchSupplies < 2
        && mc().level
                .getBrightness(
                    net.minecraft.world.level.LightLayer.BLOCK, mc().player.blockPosition())
            < 8
        && !mc().player
            .getInventory()
            .contains(new ItemStack(net.minecraft.world.item.Items.TORCH))) {
      torchSupplies++;
      var args = new JsonObject();
      args.addProperty("place", spec.source());
      args.addProperty("item", "minecraft:torch");
      args.addProperty("count", 16);
      dispatch(
          "fetch",
          args,
          r -> {
            if (Rules.actionFailed(r)) {
              torchSupplies = 2;
              card.problem = "照明补给未完成，" + Rules.clean(r, 160);
            }
            phase = "choose";
          });
      return;
    }
    batchBefore = Map.copyOf(PlayerEvidence.actual("*"));
    batchBroken.clear();
    issued.clear();
    drops.begin();
    phase = "mine";
    MiningJob.lastReceipt = null;
    lastGuard = "";
    lastGuardCell = null;
    dispatch(
        "mine_batch",
        batchArgs(),
        result -> {
          lastChild = result;
          var receipt = MiningJob.lastReceipt;
          if (!spec.mode().equals("baritone") && receipt != null && receipt.epoch() == epoch) {
            broken.addAll(receipt.positions());
            batchBroken.addAll(receipt.positions());
          }
          if (batchBroken.isEmpty() && Rules.actionFailed(result)) survey.reject(batch);
          phase = "collect";
        });
  }

  private void supply(String item, int amount) {
    var args = new JsonObject();
    args.addProperty("place", spec.source());
    args.addProperty("item", item);
    args.addProperty("count", amount);
    dispatch(
        "fetch",
        args,
        r -> {
          if (Rules.actionFailed(r)) end(MINING_SUPPLY_FAILED, r);
          else phase = "choose";
        });
  }

  private int freeSlots() {
    int count = 0;
    for (int i = 0; i < 36; i++) if (mc().player.getInventory().getItem(i).isEmpty()) count++;
    return count;
  }

  private boolean matchesOutput(String fp) {
    return spec.item().equals(fp) || !spec.item().contains("#") && fp.startsWith(spec.item() + "#");
  }

  private int progress() {
    return ledger.progress(spec.goal(), broken.size(), this::matchesOutput);
  }

  private static int total(Map<String, Integer> values) {
    return values.values().stream().mapToInt(Integer::intValue).sum();
  }

  private JsonObject batchArgs() {
    var args = new JsonObject();
    args.addProperty("expedition_id", id);
    return args;
  }

  private JsonObject location(BlockPos target) {
    var args = new JsonObject();
    args.addProperty("x", target.getX());
    args.addProperty("y", target.getY());
    args.addProperty("z", target.getZ());
    return args;
  }

  private void finishWork(String reason) {
    finishing = true;
    finishReason = reason;
    if (!spec.delivery().isBlank() && !ledger.cargo(baseline, PlayerEvidence.actual("*")).isEmpty())
      phase = "unload";
    else if (spec.returnHome()) phase = "return";
    else end(NONE, reason);
  }

  private void unload() {
    var cargo = ledger.cargo(baseline, PlayerEvidence.actual("*"));
    if (cargo.isEmpty()) {
      phase = finishing && !spec.returnHome() ? "complete" : "return";
      if (phase.equals("complete")) end(NONE, finishReason);
      return;
    }
    if (++trips > 256) {
      end(MINING_TRANSFER_LIMIT, "已到交付批次数预算");
      return;
    }
    var entry = cargo.entrySet().iterator().next();
    int amount = Math.min(64, entry.getValue());
    var args = new JsonObject();
    args.addProperty("place", spec.delivery());
    args.addProperty("item", entry.getKey());
    args.addProperty("count", amount);
    Actions.lastTransfer = null;
    dispatch(
        "deposit",
        args,
        result -> {
          var receipt = Actions.lastTransfer;
          boolean valid =
              receipt != null && receipt.epoch() == epoch && receipt.item().equals(entry.getKey());
          boolean full =
              valid && ledger.transfer(entry.getKey(), amount, receipt.moved(), receipt.deposit());
          if (Rules.actionFailed(result) || !full) {
            end(MINING_TRANSFER_UNCONFIRMED, result);
            return;
          }
          checkpoint("delivery_verified", false);
          if (progress() >= spec.count()) {
            finishing = true;
            finishReason = "目标交付数量已核实";
          }
          phase = "unload";
        });
  }

  private void dispatch(String tool, JsonObject args, Consumer<String> continuation) {
    childTool = tool;
    pending = true;
    unknownWrite = Rules.WRITE.contains(tool);
    card.next = tool;
    updateCard();
    checkpoint(phase, unknownWrite);
    child(
        tool,
        args,
        result -> {
          if (ended) {
            if (Actions.job == this) Actions.job = null;
            return;
          }
          pending = false;
          unknownWrite = Rules.WRITE.contains(tool) && Rules.actionFailed(result);
          try {
            continuation.accept(result);
          } catch (RuntimeException error) {
            end(MINING_STAGE_FAILED, Rules.clean(error.getMessage(), 200));
          }
        });
  }

  @Override
  boolean acceptsChildResult() {
    return !ended;
  }

  private void updateCard() {
    card.current = progress();
    card.phase = phase;
    card.delivered = total(ledger.delivered);
    card.carrying = total(ledger.received) - card.delivered;
    card.trips = trips;
    card.uncertain = pending || unknownWrite;
    card.progress =
        "挖除 " + broken.size() + " · 入包 " + total(ledger.received) + " · 交付 " + card.delivered;
    RestockTask.latest = card;
  }

  private void checkpoint(String state, boolean uncertain) {
    if (!scope.equals(Store.scope()) || mc().level != world) return;
    var value = status();
    value.addProperty("phase", state);
    value.addProperty("uncertain", uncertain);
    value.add("definition", definition);
    value.add("origin", coordinates(survey.origin));
    value.add("home", coordinates(home));
    value.add("baseline", Store.GSON.toJsonTree(baseline));
    var positions = new JsonArray();
    broken.forEach(p -> positions.add(coordinates(p)));
    value.add("broken", positions);
    Store.data.checkpoints.put("mining:" + id, value);
    Store.save();
  }

  JsonObject status() {
    var value = new JsonObject();
    value.addProperty("job_id", id);
    value.addProperty("dimension", dimension);
    value.addProperty("phase", phase);
    value.addProperty("progress", progress());
    value.addProperty("goal", spec.goal());
    value.addProperty("requested", spec.count());
    value.addProperty("broken_verified", broken.size());
    value.addProperty("pending", pending);
    value.addProperty("uncertain", pending || unknownWrite);
    value.add("origin", coordinates(survey.origin));
    value.addProperty("radius", spec.radius());
    value.addProperty("vertical_range", spec.vertical());
    value.addProperty("scanned_cells", survey.scanned());
    value.addProperty("unloaded_samples", survey.unknown);
    value.addProperty("current_operation", childTool);
    value.addProperty("active", active() == this);
    value.addProperty("last_guard", lastGuard);
    value.addProperty("last_child", Rules.clean(lastChild, 400));
    value.addProperty("source", "client_block_receipts_new_drop_pickups_and_verified_inventory");
    value.add("request", request.deepCopy());
    value.addProperty("historical", active() != this);
    value.add("received", Store.GSON.toJsonTree(ledger.received));
    value.add("delivered", Store.GSON.toJsonTree(ledger.delivered));
    return value;
  }

  static JsonObject inspect(JsonObject args) {
    String requested = Agent.str(args, "job_id", "");
    if (mc().level != null
        && latest != null
        && latest.scope.equals(Store.scope())
        && latest.dimension.equals(mc().level.dimension().location().toString())
        && (requested.isBlank() || latest.id.equals(requested))) return latest.status();
    if (requested.isBlank()) {
      var result = new JsonObject();
      var ids = new JsonArray();
      Store.data.checkpoints.keySet().stream()
          .filter(k -> k.startsWith("mining:"))
          .limit(128)
          .forEach(k -> ids.add(k.substring(7)));
      result.add("job_ids", ids);
      result.addProperty("active", false);
      return result;
    }
    var saved = Store.data.checkpoints.get("mining:" + requested);
    if (saved == null) throw new IllegalArgumentException("[MINING_JOB_UNKNOWN] 没有此世界采矿记录");
    var copy = saved.deepCopy();
    copy.addProperty("active", false);
    copy.addProperty("historical", true);
    return copy;
  }

  private void end(ActionResult.Code reason, String detail) {
    if (ended) return;
    boolean verified = progress() >= spec.count() && reason == NONE;
    phase = verified ? "complete" : "partial";
    ended = true;
    checkpoint(phase, !verified && (pending || unknownWrite));
    var result = status();
    result.addProperty("schema_version", 2);
    result.addProperty("status", verified ? "verified" : "partial");
    result.addProperty("business_success", verified);
    result.addProperty(
        "reason_code",
        (verified ? NONE : reason == NONE ? MINING_TARGET_INCOMPLETE : reason).name());
    result.addProperty("detail", detail);
    if (!lastGuard.isBlank() || reason == MINING_NO_PROGRESS || reason == NO_PATH) {
      var recovery = new JsonObject();
      if (lastGuardCell != null) recovery.add("blocked_cell", coordinates(lastGuardCell));
      recovery.addProperty(
          "blocked_by", lastGuard.isBlank() ? reason.name() : lastGuard.split(" @ ", 2)[0]);
      var suggestions = new JsonArray();
      suggestions.add("inspect_block核对阻挡格、支撑和液体，未知区域先加载后观察");
      suggestions.add("移动到已确认安全站位，或选择另一露头/方向");
      suggestions.add("使用terrain=detour；补支撑需单独确认位置和放置权限");
      recovery.add("suggestions", suggestions);
      recovery.addProperty("replay_same_action", false);
      result.add("recovery", recovery);
      result.addProperty("failure_family", "MINING_NO_PROGRESS");
    }
    result.addProperty("completed_count", progress());
    result.addProperty("termination_reason", reason.name());
    if (reason == TIME_BUDGET) result.addProperty("failure_category", "TIMEOUT");
    result.addProperty("retryable", false);
    card.outcome = verified ? "complete" : "blocked";
    card.problem = verified ? "" : detail;
    card.ended = System.currentTimeMillis();
    updateCard();
    finish(result.toString());
  }

  @Override
  void finish(String text) {
    if (!ended) {
      end(MINING_STAGE_FAILED, text);
      return;
    }
    super.finish(text);
  }

  @Override
  void stop() {
    if (sync != null) sync.close();
    super.stop();
  }

  @Override
  String interrupted() {
    return "采矿作业已停止；挖除和已核实产物保留在mining_status，未知步骤恢复前需重新观察";
  }
}
