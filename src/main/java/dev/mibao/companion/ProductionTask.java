package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.crafting.*;

/** Child tools retain their own permissions; fixed-area incremental search, finite recipe plan. */
public final class ProductionTask {
  public record Output(
      long epoch, String mode, String input, String item, int quantity, String id) {}

  public static Output lastOutput;
  private int verifiedOutput;
  private final boolean reserveSupplies;

  private final long epoch = Agent.epoch, deadline = System.currentTimeMillis() + 1_500_000L;
  private final String scope = Store.scope(),
      dimension = mc().level.dimension().location().toString(),
      mode,
      item;
  private final int count;
  private final Consumer<String> done;
  private final BlockPos center = mc().player.blockPosition();
  private BlockPos table, furnace;
  private int scan, phase, batches;
  private boolean pending, finished, planned, sourceTried;
  private int supplies, stationRepairs, stationChanges;
  private BlockPos supplyBox;
  private final String supplySource;
  private final Deque<Map.Entry<String, Integer>> suppliesNeeded = new ArrayDeque<>();
  private final Deque<CraftingRecipe> queue = new ArrayDeque<>();
  private final TaskCard card = new TaskCard();
  private final Map<String, CraftingRecipe> recipes = new HashMap<>();
  private SmeltingRecipe smelting;
  private SupplyWait materialWait;
  private Map<String, Integer> failedStock;
  private long nextMaterialCheck;
  private boolean knownStationsChecked;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public ProductionTask(String mode, String item, int count, Consumer<String> done) {
    this(mode, item, count, Store.data.productionSource, done);
  }

  public ProductionTask(String mode, String item, int count, String source, Consumer<String> done) {
    this(mode, item, count, source, false, done);
  }

  public ProductionTask(
      String mode,
      String item,
      int count,
      String source,
      boolean reserveSupplies,
      Consumer<String> done) {
    this.reserveSupplies = reserveSupplies;
    lastOutput = null;
    this.supplySource = source == null ? "" : Rules.clean(source.trim(), 128);
    this.mode = mode;
    this.item = item;
    this.count = count;
    this.done = done;
    card.kind = "production";
    card.progress = "准备生产，尚未确认产物";
    card.title = "生产 · " + mode;
    card.item = item;
    card.target = count;
    RestockTask.latest = card;
  }

  public boolean finished() {
    return finished;
  }

  public void cancel() {
    if (finished) return;
    checkpoint("cancelled", pending);
    finished = true;
    card.outcome = "cancelled";
    card.phase = "生产已停止";
    card.problem = "已确认制作批次 " + batches + "；合成栏、炉内及未核实步骤请重新观察";
    card.ended = System.currentTimeMillis();
    Companion.notifyUser(card.problem);
  }

  public void fail(String why) {
    end("部分完成：生产停止；已确认制作 " + batches + " 批。" + why);
  }

  private static boolean plain(net.minecraft.world.item.ItemStack s, String id) {
    return !s.isEmpty()
        && !s.hasTag()
        && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(id);
  }

  private boolean carrying(String id) {
    for (int i = 0; i < 36; i++)
      if (plain(mc().player.getInventory().getItem(i), id)
          && (!reserveSupplies || LogisticsRuntime.usable(id, LogisticsRuntime.count(id)) > 0))
        return true;
    return false;
  }

  private List<RecipePlanner.Recipe> recipeData;

  private RecipePlanner planner() {
    if (recipeData != null) return new RecipePlanner(recipeData);
    var library = ClientRecipes.crafting();
    recipes.putAll(library.recipes());
    recipeData = library.plans();
    return new RecipePlanner(recipeData);
  }

  private static String key(net.minecraft.world.item.ItemStack s) {
    return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
  }

  private Map<String, Integer> plainStock() {
    Map<String, Integer> stock = new HashMap<>();
    for (int i = 0; i < 36; i++) {
      var s = mc().player.getInventory().getItem(i);
      if (!s.isEmpty() && !s.hasTag()) stock.merge(key(s), s.getCount(), Integer::sum);
    }
    return stock;
  }

  private Map<String, Integer> taggedStock() {
    Map<String, Integer> stock = new HashMap<>();
    for (int i = 0; i < 36; i++) {
      var s = mc().player.getInventory().getItem(i);
      if (!s.isEmpty() && s.hasTag()) stock.merge(key(s), s.getCount(), Integer::sum);
    }
    return stock;
  }

  private Map<String, Integer> stock() {
    Map<String, Integer> stock = plainStock();
    if (reserveSupplies) stock.replaceAll((id, n) -> LogisticsRuntime.usable(id, n));
    return stock;
  }

  /** Base materials the whole recipe tree consumes, computed with unlimited stock. */
  private Map<String, Integer> needs(String goal, int amount) {
    planner();
    Map<String, Integer> infinite = new HashMap<>();
    for (var r : recipeData) {
      infinite.putIfAbsent(r.output(), 4096);
      for (var set : r.ingredients()) for (String id : set) infinite.putIfAbsent(id, 4096);
    }
    try {
      return planner().plan(goal, amount, infinite, true).consumedStock();
    } catch (RuntimeException e) {
      return Map.of();
    }
  }

  private String diagnosisText(String reason, String note) {
    return diagnosisText(reason, note, needs(item, count));
  }

  private String diagnosisText(String reason, String note, Map<String, Integer> need) {
    var receipt = ProductionDiagnosis.diagnose(reason, need, plainStock(), stock(), taggedStock());
    return receipt.text() + (note.isBlank() ? "" : "；" + note);
  }

  private String materialTimeout() {
    var receipt =
        ProductionDiagnosis.diagnose("", needs(item, count), plainStock(), stock(), taggedStock());
    String why =
        switch (receipt.reason()) {
          case ProductionDiagnosis.RESERVED -> "制作前等待材料30秒仍不足：材料被物流预留占用，当前可用不足；解除预留或提供可用材料后重试";
          case ProductionDiagnosis.NBT_EXCLUDED -> "制作前等待材料30秒仍不足：同名物品带NBT，不计作普通材料；提供无NBT材料或改用工作台";
          default -> "制作前等待材料30秒仍不足；当前步骤未投料";
        };
    return why + "；" + receipt.text();
  }

  private boolean tooLargeFor2x2() {
    planner();
    if (table != null || carrying("minecraft:crafting_table")) return false;
    boolean any = false;
    for (var r : recipeData)
      if (r.output().equals(item)) {
        any = true;
        if (!r.table()) return false;
      }
    return any;
  }

  private void plan(String goal, int amount) {
    if (planner() != null && recipeData.stream().noneMatch(r -> r.output().equals(goal)))
      throw new IllegalArgumentException(
          "[CRAFT_RECIPE_UNSUPPORTED] 客户端没有受支持的普通配方："
              + goal
              + "；先查lookup_client_recipes/recipe_details，不等待虚假的缺料");
    if (reserveSupplies && LogisticsRuntime.protectedId(goal))
      throw new IllegalArgumentException("生产目标或工作站已固定保护：" + goal);
    var p = planner().plan(goal, amount, stock(), table != null);
    for (String id : p.batches()) queue.add(recipes.get(id));
    card.target = batches + queue.size();
    card.current = batches;
    card.progress = "已确认制作 " + batches + " / " + card.target + " 批";
    card.next = "原料与工作站按整份配方准备，不自动采矿";
  }

  private void tryCraftPlan() {
    long now = System.currentTimeMillis();
    if (materialWait != null) {
      if (now < nextMaterialCheck) return;
      nextMaterialCheck = now + 500;
      var current = stock();
      if (current.equals(failedStock)) {
        if (materialWait.check(now, false) == SupplyWait.State.EXPIRED) {
          fail(materialTimeout());
          return;
        }
        card.phase = "等待临时制作补给";
        Agent.state = card.phase;
        return;
      }
      WorkLedger.inventoryChange(failedStock, current);
      failedStock = current;
    }
    try {
      plan(item, count);
      planned = true;
      materialWait = null;
      failedStock = null;
    } catch (IllegalArgumentException failure) {
      if (failure.getMessage().contains("CRAFT_RECIPE_UNSUPPORTED")) {
        fail(
            diagnosisText(
                ProductionDiagnosis.NO_RECIPE, "先查lookup_client_recipes/recipe_details，不等待虚假的缺料"));
        return;
      }
      if (failure.getMessage().contains("制作数量") || failure.getMessage().contains("4096步"))
        throw failure;
      if (tooLargeFor2x2()) {
        fail(
            diagnosisText(
                ProductionDiagnosis.RECIPE_TOO_LARGE,
                "目标配方需要3×3工作台，当前只有2×2合成格且附近没有可用工作台；先放置或靠近工作台，不等待30秒",
                Map.of()));
        return;
      }
      if (supplySource.isBlank() && !sourceTried && batches == 0 && queue.isEmpty()) {
        if (materialWait == null) {
          materialWait = new SupplyWait(30);
          failedStock = Map.copyOf(stock());
          checkpoint("waiting_materials", false);
        }
        if (materialWait.check(now, false) == SupplyWait.State.EXPIRED) {
          fail(materialTimeout());
          return;
        }
        card.phase = "等待临时制作补给";
        card.next = Rules.clean(failure.getMessage(), 180) + "；玩家提供材料后重新规划";
        Agent.state = card.phase;
        nextMaterialCheck = now + 500;
        return;
      }
      if (sourceTried || supplySource.isBlank()) throw failure;
      sourceTried = true;
      JsonObject a = new JsonObject();
      a.addProperty("place", supplySource);
      supplyBox = Actions.target(a);
      if (!mc().level.hasChunkAt(supplyBox)
          || !Rules.standardStorage(
              BuiltInRegistries.BLOCK
                  .getKey(mc().level.getBlockState(supplyBox).getBlock())
                  .toString()))
        throw new IllegalArgumentException("生产补料来源不是已加载的普通仓库；" + failure.getMessage());
      JsonObject open = new JsonObject();
      open.addProperty("x", supplyBox.getX());
      open.addProperty("y", supplyBox.getY());
      open.addProperty("z", supplyBox.getZ());
      child(
          "open_container",
          open,
          () -> {
            if (!MenuContext.matches(supplyBox)
                || !MenuContext.synchronizedNow()
                || !MenuContext.kind().equals("storage"))
              throw new IllegalArgumentException("补料源菜单未同步或用途未知");
            Map<String, Integer> owned = stock(), combined = new HashMap<>(owned);
            for (var slot : mc().player.containerMenu.slots) {
              var s = slot.getItem();
              if (slot.container != mc().player.getInventory() && !s.isEmpty() && !s.hasTag())
                combined.merge(
                    BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),
                    s.getCount(),
                    Integer::sum);
            }
            var virtual = planner().plan(item, count, combined, table != null);
            virtual
                .consumedStock()
                .forEach(
                    (id, n) -> {
                      int shortage = Math.max(0, n - owned.getOrDefault(id, 0));
                      if (shortage > 0) suppliesNeeded.add(Map.entry(id, shortage));
                    });
            if (suppliesNeeded.isEmpty()) throw new IllegalArgumentException("来源未提供可用缺料，停止而不反复取货");
            card.next = "从指定来源核实取料，再按真实背包重新规划";
          });
    }
  }

  public void tick() {
    if (finished || pending) return;
    if (epoch != Agent.epoch || !scope.equals(Store.scope())) {
      cancel();
      return;
    }
    if (System.currentTimeMillis() > deadline
        || SurvivalPolicy.blocked(
            mc().player.getHealth(),
            SurvivalPolicy.critical(mc().player.getMaxHealth()),
            Store.config.testIgnoreLowHealth)
        || mc().player.getFoodData().getFoodLevel() < 4) {
      fail("超过25分钟或生命/饥饿不足；保留现场");
      return;
    }
    if (scan < 2023) {
      long until = System.nanoTime() + 2_000_000;
      for (int n = 0; scan < 2023 && n < 256 && System.nanoTime() < until; n++, scan++) {
        int j = scan;
        BlockPos p = center.offset(j % 17 - 8, (j / 289) % 7 - 3, (j / 17) % 17 - 8);
        if (!mc().level.hasChunkAt(p)) continue;
        var block = mc().level.getBlockState(p).getBlock();
        if (block == net.minecraft.world.level.block.Blocks.CRAFTING_TABLE
            && (table == null || p.distSqr(center) < table.distSqr(center))) table = p;
        if (block == net.minecraft.world.level.block.Blocks.FURNACE
            && (furnace == null || p.distSqr(center) < furnace.distSqr(center))) furnace = p;
      }
      card.phase = "分帧寻找附近工作台/熔炉";
      return;
    }
    if (!knownStationsChecked) {
      knownStationsChecked = true;
      findKnownStations();
      return;
    }
    if (!suppliesNeeded.isEmpty()) {
      if (mc().screen != null) {
        close();
        return;
      }
      if (++supplies > 16) {
        fail("补料达到16次操作上限");
        return;
      }
      var next = suppliesNeeded.removeFirst();
      int amount = Math.min(64, next.getValue());
      if (next.getValue() > amount)
        suppliesNeeded.addFirst(Map.entry(next.getKey(), next.getValue() - amount));
      JsonObject a = new JsonObject();
      a.addProperty("place", supplySource);
      a.addProperty(
          "item", reserveSupplies ? LogisticsRuntime.filter(next.getKey()) : next.getKey());
      a.addProperty("count", amount);
      Actions.lastTransfer = null;
      child(
          "fetch",
          a,
          () -> {
            var receipt = Actions.lastTransfer;
            if (receipt == null
                || receipt.epoch() != epoch
                || receipt.deposit()
                || receipt.moved() != amount) throw new IllegalArgumentException("补料未完整核实，不重放取货");
            WorkLedger.add(
                "preparation", "从指定来源取到 " + amount + " 个 " + next.getKey() + "，准备后重新核对配方");
          });
      return;
    }
    if (!planned) {
      if (mode.equals("craft")) {
        if (mc().screen != null) {
          close();
          return;
        }
        tryCraftPlan();
        return;
      } else {
        if (mode.equals("smelt")) {
          smelting =
              mc().level.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING).stream()
                  .filter(
                      r ->
                          r.getId().getNamespace().equals("minecraft")
                              && Arrays.stream(r.getIngredients().get(0).getItems())
                                  .anyMatch(s -> plain(s, item)))
                  .sorted(Comparator.comparing(r -> r.getId().toString()))
                  .findFirst()
                  .orElse(null);
          if (smelting == null) {
            fail(
                diagnosisText(
                    ProductionDiagnosis.NO_RECIPE,
                    "没有普通原版熔炼配方：" + item + "；先查lookup_client_recipes/recipe_details，不等待虚假的缺料",
                    Map.of(item, Math.max(1, count))));
            return;
          }
          card.item =
              BuiltInRegistries.ITEM
                  .getKey(smelting.getResultItem(mc().level.registryAccess()).getItem())
                  .toString();
        }
        if (furnace == null && !carrying("minecraft:furnace")) plan("minecraft:furnace", 1);
        if (mode.equals("workshop")
            && table == null
            && !carrying("minecraft:crafting_table")
            && queue.isEmpty()) plan("minecraft:crafting_table", 1);
      }
      planned = true;
      return;
    }
    if (!queue.isEmpty()) {
      var r = queue.peekFirst();
      boolean needsTable = !r.canCraftInDimensions(2, 2);
      if (needsTable && !station(true)) return;
      if (!needsTable
          && !(mc().player.containerMenu instanceof InventoryMenu)
          && !(mc().player.containerMenu instanceof CraftingMenu)) {
        close();
        return;
      }
      if (mc().screen == null) {
        child("open_inventory", new JsonObject(), null);
        return;
      }
      if (!(mc().player.containerMenu instanceof InventoryMenu)
          && !(mc().player.containerMenu instanceof CraftingMenu)) {
        fail("当前菜单不支持合成");
        return;
      }
      JsonObject a = new JsonObject();
      a.addProperty("recipe", r.getId().toString());
      child(
          "craft_batch",
          a,
          () -> {
            queue.removeFirst();
            var output = r.getResultItem(mc().level.registryAccess());
            if (CraftBatchJob.outputAllowed(output)
                && BuiltInRegistries.ITEM.getKey(output.getItem()).toString().equals(item))
              verifiedOutput += output.getCount();
            batches++;
            card.current = batches;
            card.progress = "已确认制作 " + batches + " / " + card.target + " 批";
          });
      return;
    }
    if (mode.equals("craft")) {
      if (mc().screen != null) {
        close();
        return;
      }
      end("成功：制作计划完成，已逐批核对 " + batches + " 批；目标为新增 " + item + " ×" + count + "（配方批量可能多产），已正常退出界面。");
      return;
    }
    if (mode.equals("workshop")) {
      if (!station(true)) return;
      if (mc().screen != null) {
        close();
        return;
      }
      if (!station(false)) return;
      if (mc().screen != null) {
        close();
        return;
      }
      card.progress = "工作台与熔炉已确认";
      end("成功：附近工作台和普通熔炉均已找到或放置并核验；界面已退出，未额外消耗原料进行测试。");
      return;
    }
    if (phase == 0) {
      if (!station(false)) return;
      JsonObject a = new JsonObject();
      a.addProperty("item", item);
      a.addProperty("count", count);
      child(
          "smelt_batch",
          a,
          () -> {
            verifiedOutput = count * smelting.getResultItem(mc().level.registryAccess()).getCount();
            phase = 1;
          });
      return;
    }
    if (mc().screen != null) {
      close();
      return;
    }
    end("成功：熔炼与取出数量均已核实，已正常退出熔炉界面。");
  }

  private boolean station(boolean crafting) {
    BlockPos pos = crafting ? table : furnace;
    String id = crafting ? "minecraft:crafting_table" : "minecraft:furnace";
    Class<?> kind = crafting ? CraftingMenu.class : FurnaceMenu.class;
    if (pos != null && !mc().level.hasChunkAt(pos))
      throw new IllegalArgumentException("已选工作站区块未加载，仍未知，不复制放置新站点");
    if (pos != null
        && !BuiltInRegistries.BLOCK
            .getKey(mc().level.getBlockState(pos).getBlock())
            .toString()
            .equals(id)) {
      if (++stationChanges > 2) throw new IllegalArgumentException("工作站连续变化两次，停止恢复");
      WorldSense.record(pos, mc().level.getBlockState(pos));
      if (crafting) table = null;
      else furnace = null;
      pos = null;
    }
    if (pos == null && !carrying(id)) {
      if (++stationRepairs > 2) throw new IllegalArgumentException("准备工作站达到两次恢复预算");
      var remaining = new ArrayDeque<>(queue);
      queue.clear();
      plan(id, 1);
      queue.addAll(remaining);
      card.target = batches + queue.size();
      return false;
    }
    if (pos == null) {
      if (mc().screen != null) {
        close();
        return false;
      }
      if (!carrying(id)) throw new IllegalArgumentException("缺少工作站 " + id + "，附近已加载区域未找到；请提供材料/物品");
      JsonObject a = new JsonObject();
      a.addProperty("item", id);
      child(
          "place_block",
          a,
          () -> {
            if (crafting) table = PlaceJob.lastPlaced;
            else furnace = PlaceJob.lastPlaced;
          });
      return false;
    }
    if (mode.equals("workshop") && queue.isEmpty()) return true;
    if (mc().player.containerMenu.getClass() == kind && MenuContext.matches(pos)) return true;
    if (mc().screen != null) {
      close();
      return false;
    }
    JsonObject a = new JsonObject();
    a.addProperty("x", pos.getX());
    a.addProperty("y", pos.getY());
    a.addProperty("z", pos.getZ());
    child("open_container", a, null);
    return false;
  }

  private void close() {
    child("close_screen", new JsonObject(), null);
  }

  private void findKnownStations() {
    for (String category : List.of("crafting", "furnace"))
      for (var point :
          Store.data.world.query(
              dimension, center.getX(), center.getY(), center.getZ(), "", category, 64, 24)) {
        var pos = new BlockPos(point.x, point.y, point.z);
        if (!mc().level.hasChunkAt(pos)) continue;
        var state = mc().level.getBlockState(pos);
        if (state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)
            && (table == null || pos.distSqr(center) < table.distSqr(center))) table = pos;
        if (state.is(net.minecraft.world.level.block.Blocks.FURNACE)
            && (furnace == null || pos.distSqr(center) < furnace.distSqr(center))) furnace = pos;
      }
  }

  private void checkpoint(String phase, boolean uncertain) {
    if (!scope.equals(Store.scope())) return;
    JsonObject d = new JsonObject();
    d.addProperty("mode", mode);
    d.addProperty("item", item);
    d.addProperty("requested", count);
    d.addProperty("confirmed_batches", batches);
    d.addProperty("remaining_batches", queue.size());
    RecoveryLedger.put("production", phase, uncertain, d, dimension);
  }

  private void child(String tool, JsonObject a, Runnable success) {
    checkpoint(tool, true);
    pending = true;
    card.phase = ActionSummary.step(tool);
    Agent.execute(
        tool,
        a,
        r -> {
          if (finished || epoch != Agent.epoch) return;
          pending = false;
          if (Rules.actionFailed(r)) {
            fail(r);
            return;
          }
          try {
            if (success != null) success.run();
            checkpoint(tool + "_returned", false);
          } catch (Exception e) {
            fail("前置核对失败：" + Rules.clean(e.getMessage(), 240));
          }
        });
  }

  private void end(String r) {
    if (finished) return;
    if (r.startsWith("成功") || verifiedOutput > 0) {
      String output =
          mode.equals("smelt")
              ? BuiltInRegistries.ITEM
                  .getKey(smelting.getResultItem(mc().level.registryAccess()).getItem())
                  .toString()
              : item;
      lastOutput =
          new Output(
              epoch, mode, item, output, verifiedOutput, java.util.UUID.randomUUID().toString());
    }
    checkpoint(r.startsWith("成功") ? "complete" : "stopped", !r.startsWith("成功"));
    finished = true;
    card.phase = ActionSummary.describe(r);
    card.outcome = r.startsWith("成功") ? "complete" : "blocked";
    card.problem = r.startsWith("成功") ? "" : r;
    card.ended = System.currentTimeMillis();
    done.accept(r);
  }
}
