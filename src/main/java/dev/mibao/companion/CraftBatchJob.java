package dev.mibao.companion;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;

/** One plain vanilla batch: recipe-book request, authoritative ingredients, then verified take. */
public final class CraftBatchJob extends Actions.Job {
  private final CraftingRecipe recipe;
  private Object menu;
  private Object screen;
  private int phase, wait;
  private long revision;
  private Map<String, Integer> before, expected;
  private ItemStack output;
  private final Set<String> involved = new HashSet<>();
  private final Deque<int[]> clicks = new ArrayDeque<>();
  private String supplyNote = "";

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public static boolean outputAllowed(ItemStack s) {
    return !s.isEmpty()
        && ProductionProof.safeOutputTag(
            s.hasTag(),
            s.isDamageableItem(),
            s.getDamageValue(),
            s.hasTag() ? s.getTag().getAllKeys() : Set.of());
  }

  public CraftBatchJob(CraftingRecipe recipe, Consumer<String> done) {
    super(done);
    this.recipe = recipe;
    if (!RecipeKnowledge.ordinary(recipe))
      throw new IllegalArgumentException("仅支持普通原版/模组合成配方（新工具仅允许默认Damage:0标签）；特殊机器需独立适配");
    for (var ing : recipe.getIngredients())
      for (var s : ing.getItems())
        if (s.hasTag() || s.hasCraftingRemainingItem())
          throw new IllegalArgumentException("暂不处理带NBT或返回桶等容器的配方");
  }

  static Map<String, Integer> inventory() {
    var m = mc().player.containerMenu;
    Map<String, Integer> out = new HashMap<>();
    for (var s : m.slots)
      if (s.container == mc().player.getInventory() && !s.getItem().isEmpty())
        out.merge(WorldSense.fingerprint(s.getItem()), s.getItem().getCount(), Integer::sum);
    return out;
  }

  private int end() {
    return mc().player.containerMenu instanceof CraftingMenu ? 9 : 4;
  }

  private boolean emptyGrid() {
    for (int i = 1; i <= end(); i++)
      if (mc().player.containerMenu.getSlot(i).hasItem()) return false;
    return true;
  }

  private void refresh() {
    revision = MenuContext.revision();
    wait = 0;
    if (!MenuRefresh.request()) throw new IllegalArgumentException("菜单不支持普通同步");
  }

  @Override
  void tick() {
    var m = mc().player.containerMenu;
    Actions.status = "制作 · " + recipe.getId() + " · " + phase;
    if (phase == 0) {
      if (!(mc().screen
              instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)
          || (m.getClass() != InventoryMenu.class && m.getClass() != CraftingMenu.class)) {
        finish("失败：不是已打开的原版玩家/工作台菜单");
        return;
      }
      menu = m;
      screen = mc().screen;
      refresh();
      phase = 1;
      return;
    }
    if (menu != m || screen != mc().screen) {
      finish("部分完成：制作菜单改变，保留现场并停止");
      return;
    }
    if (phase == 4) {
      if (!clicks.isEmpty()) {
        var click = clicks.removeFirst();
        Actions.click(
            click[0], click[1], net.minecraft.world.inventory.ClickType.values()[click[2]]);
        GuiContext.invalidate();
        return;
      }
      if (!m.getCarried().isEmpty()) {
        finish("部分完成：[CRAFT_CURSOR] 摆放后鼠标未归位，保留现场");
        return;
      }
      refresh();
      phase = 2;
      return;
    }
    if (MenuContext.revision() <= revision || !MenuContext.synchronizedNow()) {
      if (++wait > 120) finish("部分完成：制作菜单6秒内未获得完整稳定同步，停止且不重复点击");
      return;
    }
    if (!m.getCarried().isEmpty()) {
      finish("部分完成：鼠标物品改变，保留界面");
      return;
    }
    if (phase == 1) {
      if (!emptyGrid()) {
        finish("失败：合成栏已有物品，请先收好");
        return;
      }
      // Recipe-book matching ignores NBT. Refuse if any candidate could consume a tagged item.
      for (var slot : m.slots)
        if (slot.container == mc().player.getInventory() && slot.getItem().hasTag())
          for (var ingredient : recipe.getIngredients())
            if (ingredient.test(slot.getItem())) {
              finish("失败：配方可能使用带NBT物品，请先人工隔离这些变体");
              return;
            }
      LogisticsRuntime.requireCraftMaterials(recipe);
      before = inventory();
      output = recipe.getResultItem(mc().level.registryAccess()).copy();
      involved.add(WorldSense.fingerprint(output));
      for (var slot : m.slots)
        if (slot.container == mc().player.getInventory() && !slot.getItem().isEmpty())
          for (var ingredient : recipe.getIngredients())
            if (ingredient.test(slot.getItem()))
              involved.add(WorldSense.fingerprint(slot.getItem()));
      int width = m instanceof CraftingMenu ? 3 : 2;
      var groups = new LinkedHashMap<Integer, List<Integer>>();
      var counts = new HashMap<Integer, Integer>();
      var choices = new ArrayList<List<Integer>>();
      var ingredients = new ArrayList<Integer>();
      for (int i = 0; i < recipe.getIngredients().size(); i++) {
        var ingredient = recipe.getIngredients().get(i);
        if (ingredient.isEmpty()) continue;
        ingredients.add(i);
        var candidates = new ArrayList<Integer>();
        for (int n = 0; n < m.slots.size(); n++) {
          var slot = m.getSlot(n);
          var stack = slot.getItem();
          if (slot.container == mc().player.getInventory()
              && !stack.hasTag()
              && ingredient.test(stack)) {
            candidates.add(n);
            counts.put(n, stack.getCount());
          }
        }
        choices.add(candidates);
      }
      var selected = CraftAllocation.choose(choices, counts);
      List<Integer> cells = null;
      for (int shapeWidth = 1; shapeWidth <= width; shapeWidth++) {
        var testMenu =
            new AbstractContainerMenu(null, -1) {
              public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) {
                return ItemStack.EMPTY;
              }

              public boolean stillValid(net.minecraft.world.entity.player.Player p) {
                return false;
              }
            };
        var grid =
            new net.minecraft.world.inventory.TransientCraftingContainer(testMenu, width, width);
        var candidate = new ArrayList<Integer>();
        boolean fits = true;
        for (int i = 0; i < selected.size(); i++) {
          int cell = CraftAllocation.cell(ingredients.get(i), shapeWidth, width);
          if (cell > end()) {
            fits = false;
            break;
          }
          var value = m.getSlot(selected.get(i)).getItem().copy();
          value.setCount(1);
          grid.setItem(cell - 1, value);
          candidate.add(cell);
        }
        if (fits
            && recipe.matches(grid, mc().level)
            && ItemStack.matches(recipe.assemble(grid, mc().level.registryAccess()), output)) {
          cells = candidate;
          break;
        }
      }
      if (cells == null)
        throw new IllegalArgumentException(
            "[CRAFT_LAYOUT_UNSUPPORTED] 当前配方无法在普通合成栏验证布局，未摆料；请查配方条件");
      for (int i = 0; i < selected.size(); i++)
        groups.computeIfAbsent(selected.get(i), k -> new ArrayList<>()).add(cells.get(i));
      for (var group : groups.entrySet())
        for (var click :
            GesturePlan.oneEach(
                group.getKey(),
                group.getValue(),
                m.getSlot(group.getKey()).getItem().getCount(),
                Store.config.craftGestures))
          clicks.add(
              new int[] {click.slot(), click.button(), ClickType.valueOf(click.type()).ordinal()});
      phase = 4;
      return;
    }
    if (phase == 2) {
      var actual = m.getSlot(0).getItem();
      if (!ItemStack.matches(output, actual)) {
        finish("失败：[CRAFT_RESULT_MISMATCH] 普通点击摆料后服务器产物不匹配；可能配方被服务器修改，保留合成栏，不报材料不足");
        return;
      }
      Map<String, Integer> used = new HashMap<>();
      int units = 0;
      for (int i = 1; i <= end(); i++) {
        var s = m.getSlot(i).getItem();
        if (!s.isEmpty()) {
          if (s.hasTag() || s.getCount() != 1) {
            finish("失败：合成栏不是一批普通材料，停止取出");
            return;
          }
          used.merge(WorldSense.fingerprint(s), 1, Integer::sum);
          units++;
        }
      }
      long ingredients = recipe.getIngredients().stream().filter(i -> !i.isEmpty()).count();
      if (units != ingredients) {
        finish("失败：材料槽数量与普通配方不符，停止");
        return;
      }
      var filled = new HashMap<>(inventory());
      used.forEach((k, v) -> filled.merge(k, v, Integer::sum));
      if (!InventoryTolerance.confirms(before, filled, involved)) {
        if (!InventoryTolerance.onlyGains(before, filled)) {
          finish(
              "部分完成：[CRAFT_FILLED_NOT_TAKEN]"
                  + " 合成栏已填、结果可用，但相关材料出现减少/未知变化；尚未取出成品，请inspect_gui核对后决定，不能重复合成");
          return;
        }
        supplyNote = "摆料期间有外来补给增量，已重新建立取出前基线，未计作生产；";
      }
      WorkLedger.inventoryChange(before, filled);
      before = filled;
      expected =
          ProductionProof.crafted(before, used, WorldSense.fingerprint(output), output.getCount());
      Actions.click(0, 0, ClickType.QUICK_MOVE);
      GuiContext.invalidate();
      refresh();
      phase = 3;
      return;
    }
    finish(
        InventoryTolerance.confirms(expected, inventory(), involved)
                && m.getCarried().isEmpty()
                && emptyGrid()
            ? "成功：制作一批 "
                + output.getHoverName().getString()
                + " ×"
                + output.getCount()
                + "；"
                + supplyNote
                + "相关材料与产物增量经服务器同步核对，无关补给不计入产物。"
            : "部分完成：取出后相关数量账本不一致或背包满，保留界面，不重复制作。");
  }

  @Override
  String interrupted() {
    return "制作已停止；已发送的配方/取出可能生效，合成栏及鼠标物品保留，请核对。";
  }
}
