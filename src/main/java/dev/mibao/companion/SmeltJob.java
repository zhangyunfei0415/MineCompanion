package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;

/**
 * Empty vanilla furnace only. Exact input/fuel, synchronized clicks, bounded wait and output
 * ledger.
 */
public final class SmeltJob extends Actions.Job {
  private final String input;
  private final int count, cookingTicks;
  private final CookingStation station;
  private int fuel;
  private final ItemStack output;
  private final long deadline;
  private Object menu, screen;
  private long revision;
  private int phase, wait, transfer, remaining;
  private boolean clickPending;
  private ItemStack cursor;
  private Map<String, Integer> baseline, expected, clickExpected;
  private final Set<String> involved = new HashSet<>();
  private final Deque<int[]> clicks = new ArrayDeque<>();
  private JsonObject fuelEvidence = new JsonObject();
  private int inputBefore = -1, outputBefore;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public SmeltJob(String input, int count, AbstractCookingRecipe recipe, Consumer<String> done) {
    super(done);
    this.input = input;
    this.count = count;
    station = CookingStation.current();
    if (recipe.getType() != station.recipes())
      throw new IllegalArgumentException("[WRONG_RECIPE_TYPE] 炉型与配方不符");
    cookingTicks = count * recipe.getCookingTime();
    fuel = (count * recipe.getCookingTime() + 1599) / 1600;
    output = recipe.getResultItem(mc().level.registryAccess()).copy();
    if (output.hasTag() || output.getCount() * count > output.getMaxStackSize() || fuel > 64)
      throw new IllegalArgumentException("一批熔炼产物须能放入一个普通产物槽");
    deadline =
        System.currentTimeMillis()
            + Math.min(1_200_000L, 60_000L + count * recipe.getCookingTime() * 100L);
  }

  private void refresh() {
    revision = MenuContext.revision();
    wait = 0;
    MenuRefresh.request();
  }

  private static boolean plain(ItemStack s, String id) {
    return !s.isEmpty()
        && !s.hasTag()
        && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(id);
  }

  @Override
  void tick() {
    var m = mc().player.containerMenu;
    Actions.status =
        "熔炼 · "
            + input
            + " ×"
            + count
            + " · "
            + (phase == 3 ? "等待服务器产物" : phase == 4 ? "核对取出" : "填充材料/燃料");
    if (System.currentTimeMillis() > deadline) {
      finish("部分完成：熔炼等待超时；炉内材料/燃料及已出产物保留，请观察，不自动重复投料");
      return;
    }
    if (phase == 0) {
      if (m.getClass() != station.menu()) {
        finish("失败：[WRONG_FURNACE] 当前炉型与任务不符");
        return;
      }
      menu = m;
      screen = mc().screen;
      refresh();
      phase = 1;
      return;
    }
    if (menu != m || screen != mc().screen) {
      finish("部分完成：熔炉界面已改变，停止；炉内作业可能继续");
      return;
    }
    if (MenuContext.revision() <= revision || !MenuContext.synchronizedNow()) {
      if (++wait > 160) finish("部分完成：熔炉菜单8秒内没有完整稳定同步，停止保留现场");
      return;
    }
    if (clickPending) {
      boolean same =
          ItemStack.matches(cursor, m.getCarried())
              && InventoryTolerance.confirms(clickExpected, CraftBatchJob.inventory(), involved);
      if (!same) {
        finish("部分完成：服务器背包/鼠标结果与投料预测不一致，停止不重试");
        return;
      }
      clickPending = false;
    }
    if (phase == 1) {
      if (!m.getCarried().isEmpty()) {
        finish("失败：[CURSOR_NOT_EMPTY] 先stow_cursor收好鼠标物品");
        return;
      }
      if (m.getSlot(0).hasItem()) {
        finish("失败：[FURNACE_INPUT_DIRTY] 输入已有材料，不混入旧任务");
        return;
      }
      if (m.getSlot(2).hasItem()) {
        finish("失败：[FURNACE_OUTPUT_DIRTY] 先收取已有产物");
        return;
      }
      if (m.getSlot(1).hasItem() && burnTime(m.getSlot(1).getItem()) <= 0) {
        finish("失败：[FURNACE_FUEL_UNSUPPORTED] 现有燃料无法按当前熔炼配方使用，未改动");
        return;
      }
      int have = 0;
      for (var s : m.slots)
        if (s.container == mc().player.getInventory()) {
          if (plain(s.getItem(), input)) have += s.getItem().getCount();
        }
      if (Agent.routine instanceof LogisticsTask) {
        have = LogisticsRuntime.usable(input, have);
      }
      var existing = m.getSlot(1).getItem();
      inputBefore = countItem(input);
      outputBefore = countItem(BuiltInRegistries.ITEM.getKey(output.getItem()).toString());
      int oldFuel = existing.getCount();
      Map<String, Integer> available = new LinkedHashMap<>();
      Map<String, Integer> durations = new HashMap<>();
      for (var slot : m.slots) {
        var stack = slot.getItem();
        int duration = burnTime(stack);
        if (slot.container != mc().player.getInventory() || duration <= 0) continue;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        available.merge(id, stack.getCount(), Integer::sum);
        durations.put(id, duration);
      }
      String existingId =
          oldFuel > 0 ? BuiltInRegistries.ITEM.getKey(existing.getItem()).toString() : "";
      if (oldFuel > 0) durations.put(existingId, burnTime(existing));
      if (Agent.routine instanceof LogisticsTask)
        available.replaceAll((id, n) -> LogisticsRuntime.usable(id, n));
      var plan =
          SmeltFuelPlan.choose(
              cookingTicks, existingId, oldFuel, available, durations, input, count);
      fuelId = plan.fuel();
      fuel = plan.required();
      addedFuel = plan.toAdd();
      fuelLimit = Math.max(fuel, oldFuel);
      fuelEvidence.addProperty("fuel_id", fuelId);
      fuelEvidence.addProperty("fuel_ticks_per_item", durations.getOrDefault(fuelId, 1600));
      fuelEvidence.addProperty("fuel_available", plan.inFurnace() + plan.inInventory());
      fuelEvidence.addProperty("fuel_required", plan.required());
      fuelEvidence.addProperty("fuel_in_furnace", plan.inFurnace());
      fuelEvidence.addProperty("fuel_in_inventory", plan.inInventory());
      fuelEvidence.addProperty("fuel_to_add", plan.toAdd());
      fuelEvidence.addProperty("fuel_missing", plan.missing());
      fuelEvidence.addProperty("input_available", have);
      fuelEvidence.addProperty("input_required", count);
      fuelEvidence.add("accepted_fuels_observed", new Gson().toJsonTree(durations));
      if (have < count || plan.missing() > 0) {
        finish(
            have < count
                ? "失败：[MISSING_INPUT] 输入" + input + "需要" + count + "，背包可用" + have
                : "失败：[MISSING_FUEL] 燃料"
                    + fuelId
                    + "缺"
                    + plan.missing()
                    + "；可用普通木板、原木、煤、木炭、煤炭块、烈焰棒，以当前炉型燃烧时长为准");
        return;
      }
      inputBefore = countItem(input);
      outputBefore = countItem(BuiltInRegistries.ITEM.getKey(output.getItem()).toString());
      if (RestockTask.latest != null && "production".equals(RestockTask.latest.kind)) {
        RestockTask.latest.target = count * output.getCount();
        RestockTask.latest.current = 0;
        RestockTask.latest.progress = "熔炼尚未取得产物";
      }
      baseline = CraftBatchJob.inventory();
      expected = new HashMap<>(baseline);
      remaining = count;
      phase = 2;
      for (var slot : m.slots)
        if (plain(slot.getItem(), input) || plain(slot.getItem(), fuelId))
          involved.add(WorldSense.fingerprint(slot.getItem()));
      involved.add(WorldSense.fingerprint(output));
    }
    if (phase == 2) {
      if (!clicks.isEmpty()) {
        var c = clicks.removeFirst();
        issue(c[0], c[1], ClickType.PICKUP);
        return;
      }
      if (remaining == 0) {
        if (!m.getCarried().isEmpty()) {
          finish("部分完成：投料后鼠标未清空，停止");
          return;
        }
        if (transfer == 0) {
          transfer = 1;
          remaining = addedFuel;
        } else {
          phase = 3;
          refresh();
          return;
        }
      }
      if (remaining == 0) {
        phase = 3;
        refresh();
        return;
      }
      String id = transfer == 0 ? input : fuelId;
      int source = -1;
      for (int i = 3; i < m.slots.size(); i++)
        if (m.getSlot(i).container == mc().player.getInventory()
            && plain(m.getSlot(i).getItem(), id)) {
          source = i;
          break;
        }
      if (source < 0) {
        finish("部分完成：投料期间物品减少，停止并保留炉内现状");
        return;
      }
      ItemStack stack = m.getSlot(source).getItem();
      int n = Math.min(remaining, stack.getCount());
      String fp = WorldSense.fingerprint(stack);
      expected.merge(fp, -n, Integer::sum);
      if (expected.get(fp) == 0) expected.remove(fp);
      clicks.add(new int[] {source, 0});
      if (n == stack.getCount()) clicks.add(new int[] {transfer, 0});
      else {
        for (int i = 0; i < n; i++) clicks.add(new int[] {transfer, 1});
        clicks.add(new int[] {source, 0});
      }
      remaining -= n;
      return;
    }
    if (phase == 3) {
      if (!m.getCarried().isEmpty()) {
        finish("部分完成：等待熔炼时鼠标物品改变；保留现场重新观察");
        return;
      }
      var incoming = m.getSlot(0).getItem();
      var burning = m.getSlot(1).getItem();
      var produced = m.getSlot(2).getItem();
      if (!incoming.isEmpty() && !plain(incoming, input)
          || !burning.isEmpty() && (!plain(burning, fuelId) || burning.getCount() > fuelLimit)
          || !produced.isEmpty() && !ItemStack.isSameItemSameTags(output, produced)
          || !InventoryTolerance.furnaceBalance(
              count, incoming.getCount(), produced.getCount(), output.getCount())) {
        finish("部分完成：炉内输入/燃料/产物不再符合本次投料，停止且不重复投料；请重新观察");
        return;
      }
      var now = CraftBatchJob.inventory();
      WorkLedger.inventoryChange(expected, now);
      expected = new HashMap<>(now);
      var result = m.getSlot(2).getItem();
      if (RestockTask.latest != null && "production".equals(RestockTask.latest.kind)) {
        RestockTask.latest.current = result.getCount();
        RestockTask.latest.progress =
            "炉中已同步 " + result.getCount() + " / " + (count * output.getCount()) + " 个，尚未取出";
      }
      if (ItemStack.isSameItemSameTags(output, result)
          && result.getCount() == count * output.getCount()
          && !m.getSlot(0).hasItem()) {
        expected.merge(WorldSense.fingerprint(output), count * output.getCount(), Integer::sum);
        phase = 4;
        issue(2, 0, ClickType.QUICK_MOVE);
        return;
      }
      if (!result.isEmpty() && !ItemStack.isSameItemSameTags(result, output)) {
        finish("部分完成：熔炉出现非目标产物，停止");
        return;
      }
      refresh();
      return;
    }
    boolean verified =
        InventoryTolerance.confirms(expected, CraftBatchJob.inventory(), involved)
            && !m.getSlot(2).hasItem()
            && m.getCarried().isEmpty();
    if (verified && RestockTask.latest != null && "production".equals(RestockTask.latest.kind))
      RestockTask.latest.progress = "已取出并核实 " + (count * output.getCount()) + " 个产物";
    finish(
        verified
            ? "成功：熔炼并取出 "
                + output.getHoverName().getString()
                + " ×"
                + (count * output.getCount())
                + "；按本次取出增量核验，等待期间补给未计作产物。"
            : "部分完成：取出数量不符或背包满，保留熔炉，不重复投料。");
  }

  private String fuelId;

  private int countItem(String id) {
    int total = 0;
    for (var stack : mc().player.getInventory().items)
      if (plain(stack, id)) total += stack.getCount();
    var m = mc().player.containerMenu;
    if (m == menu)
      for (int i = 0; i < 3; i++)
        if (plain(m.getSlot(i).getItem(), id)) total += m.getSlot(i).getItem().getCount();
    if (plain(m.getCarried(), id)) total += m.getCarried().getCount();
    return total;
  }

  private JsonObject receipt(String text) {
    var r = new JsonObject();
    r.addProperty("status", text.startsWith("成功") ? "verified" : "partial");
    r.addProperty("business_success", text.startsWith("成功"));
    r.addProperty("recovery_action", "preserved_not_moved");
    r.addProperty("detail", text);
    r.addProperty("reason_code", ReasonCodes.describe(text).get("reason_code").getAsString());
    fuelEvidence.entrySet().forEach(e -> r.add(e.getKey(), e.getValue()));
    var recovered = new JsonObject();
    var m = mc().player.containerMenu;
    boolean same = m == menu && MenuContext.synchronizedNow();
    int inCursor = plain(m.getCarried(), input) ? m.getCarried().getCount() : 0;
    int inFurnace =
        same && plain(m.getSlot(0).getItem(), input) ? m.getSlot(0).getItem().getCount() : 0;
    int produced =
        same
            ? Math.max(
                0,
                countItem(BuiltInRegistries.ITEM.getKey(output.getItem()).toString())
                    - outputBefore)
            : 0;
    var balance =
        SmeltBalance.check(count, inputBefore, countItem(input), produced, output.getCount());
    boolean inputAlsoFuel = input.equals(fuelId);
    int missing = same && inputBefore >= 0 && !inputAlsoFuel ? balance.unaccounted() : 0;
    if (same && inputBefore >= 0 && !inputAlsoFuel)
      recovered.addProperty(input, balance.remaining());
    r.add("recovered_items", recovered);
    r.addProperty(
        "recovery_target",
        !same
            ? "unknown"
            : inCursor > 0
                ? "cursor"
                : inFurnace > 0
                    ? MenuContext.source() == null
                        ? "furnace:menu#" + m.containerId
                        : "furnace@"
                            + MenuContext.source().getX()
                            + ","
                            + MenuContext.source().getY()
                            + ","
                            + MenuContext.source().getZ()
                    : "inventory");
    r.addProperty("recovery_verified", same && inputBefore >= 0 && missing == 0 && !inputAlsoFuel);
    var evidence = new JsonObject();
    evidence.addProperty("input_on_cursor", inCursor);
    evidence.addProperty("input_in_furnace", inFurnace);
    evidence.addProperty("input_total_before", inputBefore);
    evidence.addProperty("input_total_now", same ? countItem(input) : -1);
    evidence.addProperty(
        "input_in_inventory", Math.max(0, countItem(input) - inCursor - inFurnace));
    evidence.addProperty("output_observed", produced);
    evidence.addProperty("unaccounted_input", missing);
    evidence.addProperty("synchronized", same);
    evidence.addProperty("input_also_fuel", inputAlsoFuel);
    r.add("evidence", evidence);
    if (missing > 0) {
      r.addProperty("reason_code", "ITEMS_UNACCOUNTED");
      r.addProperty("status", "partial");
      r.addProperty("business_success", false);
    }
    return r;
  }

  @Override
  void finish(String text) {
    super.finish(receipt(text).toString());
  }

  JsonObject recoveryEvidence() {
    return receipt("部分完成：保留当前材料归属，请先核对现场");
  }

  private int burnTime(ItemStack stack) {
    return burnTime(stack, station);
  }

  static int burnTime(ItemStack stack, CookingStation station) {
    if (stack.isEmpty() || stack.hasTag() || stack.isDamageableItem()) return 0;
    // Container-returning fuels need their own inventory ledger, so keep them separate.
    if (stack.hasCraftingRemainingItem()) return 0;
    return FieldProof.fuelTicks(
        net.minecraftforge.common.ForgeHooks.getBurnTime(stack, station.recipes()),
        station.menu() != FurnaceMenu.class);
  }

  private int addedFuel, fuelLimit;

  private void issue(int slot, int button, ClickType type) {
    Actions.click(slot, button, type);
    var m = mc().player.containerMenu;
    clickExpected = CraftBatchJob.inventory();
    cursor = m.getCarried().copy();
    clickPending = true;
    GuiContext.invalidate();
    refresh();
  }

  @Override
  String interrupted() {
    return receipt("部分完成：熔炼停止，输入槽/鼠标/背包归属见evidence；炉内可继续加工，旧投料不得重放").toString();
  }
}
