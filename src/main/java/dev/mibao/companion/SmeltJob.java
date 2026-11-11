package dev.mibao.companion;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SmeltingRecipe;

/**
 * Empty vanilla furnace only. Exact input/fuel, synchronized clicks, bounded wait and output
 * ledger.
 */
public final class SmeltJob extends Actions.Job {
  private final String input;
  private final int count, cookingTicks;
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

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public SmeltJob(String input, int count, SmeltingRecipe recipe, Consumer<String> done) {
    super(done);
    this.input = input;
    this.count = count;
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
      if (m.getClass() != FurnaceMenu.class) {
        finish("失败：当前不是原版普通熔炉菜单");
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
      if (oldFuel > 0) {
        fuelId = BuiltInRegistries.ITEM.getKey(existing.getItem()).toString();
        durations.put(fuelId, burnTime(existing));
      } else {
        fuelId = available.keySet().stream()
            .sorted(Comparator.comparingInt((String id) -> durations.get(id)).reversed())
            .filter(id -> {
              int stock = available.get(id) - (id.equals(input) ? count : 0);
              if (Agent.routine instanceof LogisticsTask) stock = LogisticsRuntime.usable(id, stock);
              return stock >= (cookingTicks + durations.get(id) - 1) / durations.get(id);
            }).findFirst().orElse("");
      }
      if (fuelId.isEmpty()) {
        finish("失败：[MISSING_FUEL] 缺少可用于本批熔炼的燃料，支持煤、木炭和普通木质燃料");
        return;
      }
      fuel = (cookingTicks + durations.get(fuelId) - 1) / durations.get(fuelId);
      addedFuel = Math.max(0, fuel - oldFuel);
      fuelLimit = Math.max(fuel, oldFuel);
      int stock = available.getOrDefault(fuelId, 0) - (fuelId.equals(input) ? count : 0);
      if (Agent.routine instanceof LogisticsTask) stock = LogisticsRuntime.usable(fuelId, stock);
      if (have < count || stock < addedFuel) {
        finish("失败：[MISSING_MATERIAL] 需要输入 " + count + " 与燃料 " + fuelId + " ×" + addedFuel);
        return;
      }
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
  private static int burnTime(ItemStack stack) {
    if (stack.isEmpty() || stack.hasTag() || stack.isDamageableItem()) return 0;
    // Container-returning fuels need their own inventory ledger, so keep them separate.
    if (stack.hasCraftingRemainingItem()) return 0;
    return net.minecraftforge.common.ForgeHooks.getBurnTime(stack, net.minecraft.world.item.crafting.RecipeType.SMELTING);
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
    return "熔炼已停止控制；已投入的炉内材料可能继续加工，保留界面及鼠标物品，请重新观察。";
  }
}
