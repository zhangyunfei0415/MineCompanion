package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.phys.AABB;

/** Tool preparation -> bounded mining -> scoped new drops -> authoritative inventory result. */
public final class ResourceTask {
  private final long epoch = Agent.epoch, deadline = System.currentTimeMillis() + 720_000;
  private final TaskCard card = new TaskCard();
  private final String block;
  private final int count, radius;
  private final boolean wood;
  private final Consumer<String> done;
  private final String scope = Store.scope(),
      dimension = mc().level.dimension().location().toString(),
      delivery;
  private int preparations, unloads;
  private boolean returnToCollection;
  private final Set<String> expectedDrops = new HashSet<>();
  private final Map<String, Integer> delivered = new LinkedHashMap<>();
  private Map<String, Integer> finalInventory = Map.of();
  private String collectionResult = "";
  private InventorySession unloadSync;
  private boolean miningCapacity, miningTool, uncertain;
  private boolean pending, finished;
  private int phase, delay, mined, batches;
  private boolean interruptedMining;
  private String miningResult = "";
  private final BlockPos center = mc().player.blockPosition();
  private final Set<UUID> oldDrops = new HashSet<>();
  private final List<BlockPos> broken = new ArrayList<>();
  private final Map<Long, Boolean> treeColumns = new HashMap<>();
  private Map<String, Integer> resourceBaseline;
  private int verifiedGained;
  private boolean gatheredProof;

  Map<Long, Boolean> treeColumns() {
    return treeColumns;
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public ResourceTask(String block, int count, int radius, boolean wood, Consumer<String> done) {
    this(block, count, radius, wood, Store.data.resourceDelivery, done);
  }

  public ResourceTask(
      String block, int count, int radius, boolean wood, String delivery, Consumer<String> done) {
    this.delivery = delivery == null ? "" : Rules.clean(delivery.trim(), 128);
    this.block = block;
    identifyDrops();
    this.count = count;
    this.radius = radius;
    this.wood = wood;
    this.done = done;
    card.kind = "resource";
    card.title = wood ? "备斧砍树与拾取" : "采矿与拾取";
    card.item = block;
    card.target = count;
    card.current = 0;
    card.progress = "已确认挖除 0 / " + count;
    RestockTask.latest = card;
  }

  private String holdKey = "";
  private boolean requireNatural = true;

  public ResourceTask options(String key, boolean natural) {
    holdKey = key;
    requireNatural = natural;
    return this;
  }

  public static boolean isWood(String block) {
    if (block.equals("#minecraft:logs")
        || block.equals("#minecraft:logs_that_burn")
        || block.matches("#minecraft:[a-z_]+_logs")) return true;
    if (block.startsWith("#")) return false;
    var id = net.minecraft.resources.ResourceLocation.tryParse(block);
    return id != null
        && net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getOptional(id)
            .map(b -> b.defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS))
            .orElse(false);
  }

  public boolean wood() {
    return wood;
  }

  public boolean finished() {
    return finished;
  }

  public void cancel() {
    checkpoint("cancelled", pending || uncertain);
    finished = true;
    card.outcome = "cancelled";
    card.phase = "采集已停止";
    card.problem = "已确认挖除 " + mined + "，拾取数量请核对现场";
    card.ended = System.currentTimeMillis();
  }

  public void fail(String why) {
    end("部分完成：采集流程停止。" + miningResult + " " + why);
  }

  public void tick() {
    if (finished || pending) return;
    if (System.currentTimeMillis() > deadline) {
      fail("整次采集已达12分钟预算");
      return;
    }
    if (epoch != Agent.epoch
        || !scope.equals(Store.scope())
        || !dimension.equals(mc().level.dimension().location().toString())) {
      cancel();
      return;
    }
    if (phase == 0) {
      if (wood && !hasAxe()) {
        if (++preparations > 2) {
          fail("自动备斧两次仍不足，停止以免反复消耗材料");
          return;
        }
        String axe =
            plainCount("minecraft:cobblestone") >= 3
                ? "minecraft:stone_axe"
                : "minecraft:wooden_axe";
        JsonObject a = new JsonObject();
        a.addProperty("item", axe);
        a.addProperty("count", 1);
        child(
            "craft_item",
            a,
            r -> {
              if (Rules.actionFailed(r)) fail("缺斧子，自动制作未完成：" + r);
            });
        return;
      }
      phase = 1;
      return;
    }
    if (phase == 1) {
      if (wood && !hasAxe()) {
        phase = 0;
        return;
      }
      if (batches == 0) {
        resourceBaseline = Map.copyOf(PlayerEvidence.actual("*"));
        for (var drop :
            mc().level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(radius + 4)))
          oldDrops.add(drop.getUUID());
      }
      JsonObject a = new JsonObject();
      a.addProperty("block", block);
      a.addProperty("count", Math.min(4, count - mined));
      a.addProperty("radius", radius);
      a.addProperty("require_axe", wood);
      a.addProperty("natural_tree", wood && requireNatural);
      a.addProperty("hold_key", holdKey);
      MiningJob.lastReceipt = null;
      child(
          "mine_batch",
          a,
          r -> {
            miningResult = r;
            var receipt = MiningJob.lastReceipt;
            if (receipt == null || receipt.epoch() != epoch) {
              end(r);
              return;
            }
            miningCapacity = receipt.capacityBlocked();
            miningTool = receipt.toolBlocked();
            if (receipt.positions().isEmpty()) {
              if (miningCapacity && prepareUnload(false)) return;
              end(r);
              return;
            }
            broken.addAll(receipt.positions());
            mined += receipt.positions().size();
            batches++;
            interruptedMining = Rules.actionFailed(r);
            phase = 2;
            delay = 0;
          });
      return;
    }
    if (phase == 2) {
      Agent.state = "采集后等待掉落物同步";
      if (++delay < 12) return;
      phase = 3;
      CollectionJob.lastReceipt = null;
      child(
          "collect_mined",
          new JsonObject(),
          r -> {
            collectionResult = r;
            var receipt = CollectionJob.lastReceipt;
            gatheredProof =
                receipt != null && receipt.epoch() == epoch && receipt.inventoryVerified();
            if (gatheredProof) {
              finalInventory = receipt.inventory();
              verifiedGained =
                  DeltaProof.gathered(
                      resourceBaseline,
                      GatheringCargo.includeDelivered(finalInventory, delivered),
                      true);
            }
            boolean capacity = gatheredProof && receipt.capacityBlocked();
            if (gatheredProof
                && (capacity || miningCapacity || mc().player.getInventory().getFreeSlot() < 2)
                && prepareUnload(capacity)) return;
            cycleDone();
          });
      return;
    }
    if (phase == 4) {
      if (unloadSync == null) unloadSync = new InventorySession();
      int sync = unloadSync.tick();
      if (sync < 0) {
        fail("卸货前完整背包核对失败：" + unloadSync.error);
        return;
      }
      if (sync == 0) return;
      unloadSync.close();
      finalInventory = Map.copyOf(PlayerEvidence.actual("*"));
      var cargo = GatheringCargo.available(resourceBaseline, finalInventory, this::allowedCargo);
      if (cargo.isEmpty()) {
        phase = 5;
        return;
      }
      if (unloads >= 12) {
        fail(
            "达到12次交付预算，已核实交付 "
                + delivered.values().stream().mapToInt(Integer::intValue).sum()
                + " 件；请查看现场");
        return;
      }
      var entry = cargo.entrySet().iterator().next();
      String fp = entry.getKey();
      int amount = Math.min(64, entry.getValue());
      JsonObject a = new JsonObject();
      a.addProperty("place", delivery);
      a.addProperty("item", fp);
      a.addProperty("count", amount);
      Actions.lastTransfer = null;
      unloadSync = null;
      unloads++;
      uncertain = true;
      checkpoint("unload_started", true);
      child(
          "deposit",
          a,
          r -> {
            var receipt = Actions.lastTransfer;
            if (receipt != null
                && receipt.epoch() == epoch
                && receipt.deposit()
                && receipt.item().equals(fp)
                && receipt.moved() > 0) {
              uncertain = false;
              delivered.merge(fp, receipt.moved(), Integer::sum);
              finalInventory = Map.copyOf(PlayerEvidence.actual("*"));
              verifiedGained =
                  DeltaProof.gathered(
                      resourceBaseline,
                      GatheringCargo.includeDelivered(finalInventory, delivered),
                      true);
            }
            if (Rules.actionFailed(r)
                || receipt == null
                || receipt.epoch() != epoch
                || receipt.moved() != amount) {
              fail("交付未完整核实，保留货物/现场，不重复存入。" + r);
              return;
            }
            checkpoint("unload_verified", false);
          });
      return;
    }
    if (phase == 5) {
      JsonObject a = new JsonObject();
      a.addProperty("x", center.getX());
      a.addProperty("y", center.getY());
      a.addProperty("z", center.getZ());
      child(
          "go_to",
          a,
          r -> {
            if (Rules.actionFailed(r)) {
              fail("卸货后返回工作区失败：" + r);
              return;
            }
            if (returnToCollection) {
              phase = 2;
              delay = 0;
            } else cycleDone();
          });
    }
  }

  private boolean prepareUnload(boolean recollect) {
    if (delivery.isBlank() || resourceBaseline == null || !gatheredProof) return false;
    if (GatheringCargo.available(resourceBaseline, finalInventory, this::allowedCargo).isEmpty())
      return false;
    JsonObject target = new JsonObject();
    target.addProperty("place", delivery);
    BlockPos p = Actions.target(target);
    if (!mc().level.hasChunkAt(p)
        || !Rules.standardStorage(
            net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(mc().level.getBlockState(p).getBlock())
                .toString())) {
      fail("指定采集交付箱未加载或不是普通仓储，停止恢复");
      return true;
    }
    returnToCollection = recollect;
    phase = 4;
    card.next = "只存本次已核实新增的普通采集物品，再返回工作区";
    return true;
  }

  private void cycleDone() {
    boolean failed =
        interruptedMining && !miningCapacity && !(wood && miningTool)
            || Rules.actionFailed(collectionResult)
            || !gatheredProof;
    if (!failed && mined < count) {
      phase = 1;
      return;
    }
    int sent = delivered.values().stream().mapToInt(Integer::intValue).sum();
    end(
        (failed || mined < count || verifiedGained == 0 ? "部分完成：" : "成功：")
            + "确认挖除 "
            + mined
            + " / "
            + count
            + " 个方块；分 "
            + batches
            + " 批拾取，整次核实新增 "
            + verifiedGained
            + " 件（含可能外来补给，不等同特定产量），其中已核实交付 "
            + sent
            + " 件。"
            + (gatheredProof ? "" : " 最终背包尚未核实。")
            + "\n"
            + miningResult
            + "\n"
            + collectionResult);
  }

  private void checkpoint(String phase, boolean uncertain) {
    if (!scope.equals(Store.scope())) return;
    JsonObject d = new JsonObject();
    d.addProperty("block", block);
    d.addProperty("mined", mined);
    d.addProperty("requested", count);
    d.addProperty("delivery", delivery);
    d.add("delivered", Store.GSON.toJsonTree(delivered));
    RecoveryLedger.put("resource", phase, uncertain, d, dimension);
  }

  private void identifyDrops() {
    if (block.startsWith("#")) {
      var id = net.minecraft.resources.ResourceLocation.tryParse(block.substring(1));
      if (id != null)
        net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getTag(
                net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.BLOCK, id))
            .ifPresent(
                tag -> {
                  for (var holder : tag) {
                    String drop =
                        GatheringCargo.commonDrop(
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                .getKey(holder.value())
                                .toString());
                    if (!drop.isEmpty()) expectedDrops.add(drop);
                  }
                });
    } else {
      String drop = GatheringCargo.commonDrop(block);
      if (!drop.isEmpty()) expectedDrops.add(drop);
    }
  }

  private boolean allowedCargo(String fp) {
    return wood
        ? GatheringCargo.allowed(block, true, fp)
        : expectedDrops.contains(fp.split("#", 2)[0]);
  }

  private boolean hasAxe() {
    for (int i = 0; i < 36; i++) {
      var s = mc().player.getInventory().getItem(i);
      if (s.getItem() instanceof AxeItem
          && (!s.isDamageableItem() || s.getMaxDamage() - s.getDamageValue() > 2)) return true;
    }
    return false;
  }

  private int plainCount(String id) {
    int n = 0;
    for (int i = 0; i < 36; i++) {
      var s = mc().player.getInventory().getItem(i);
      if (!s.hasTag()
          && net.minecraft.core.registries.BuiltInRegistries.ITEM
              .getKey(s.getItem())
              .toString()
              .equals(id)) n += s.getCount();
    }
    return n;
  }

  public Actions.Job collection(Consumer<String> done) {
    if (phase != 3 || broken.isEmpty()) throw new IllegalArgumentException("没有当前任务已确认挖除位置");
    return new CollectionJob(
        "*",
        Integer.MAX_VALUE,
        Math.min(28, radius + 4),
        center,
        e ->
            !oldDrops.contains(e.getUUID())
                && broken.stream()
                    .anyMatch(
                        p ->
                            PickupGeometry.nearBroken(
                                p.getX() + .5,
                                p.getY(),
                                p.getZ() + .5,
                                e.getX(),
                                e.getY(),
                                e.getZ())),
        true,
        done);
  }

  private void child(String tool, JsonObject a, Consumer<String> after) {
    pending = true;
    card.phase =
        switch (tool) {
          case "craft_item" -> "先制作斧子";
          case "collect_mined" -> "主动收集附近新掉落";
          case "deposit" -> "满包交付已核实采集物";
          case "go_to" -> "返回采集工作区";
          default -> "分批挖掘并等待服务器确认";
        };
    Agent.execute(
        tool,
        a,
        r -> {
          if (finished || epoch != Agent.epoch) return;
          pending = false;
          RestockTask.latest = card;
          try {
            after.accept(r);
          } catch (Exception e) {
            fail("恢复步骤核对失败：" + Rules.clean(e.getMessage(), 240));
          }
          card.current = mined;
          card.progress =
              "已确认挖除 "
                  + mined
                  + " / "
                  + count
                  + "，最终确认新增 "
                  + verifiedGained
                  + (gatheredProof ? "" : "（待完整核对）");
        });
  }

  private void end(String result) {
    if (finished) return;
    checkpoint(result.startsWith("成功") ? "complete" : "stopped", uncertain);
    finished = true;
    card.uncertain = uncertain;
    card.outcome = result.startsWith("成功") ? "complete" : "blocked";
    card.phase = "采集与拾取结束";
    card.problem = result.startsWith("成功") ? "" : result;
    card.ended = System.currentTimeMillis();
    done.accept(result);
  }
}
