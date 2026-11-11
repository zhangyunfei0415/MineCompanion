package dev.mibao.companion;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/** Standard menu operation plus full resync; unknown menus need natural server evidence. */
public final class MenuClickJob extends Actions.Job {
  private final int slot, button;
  private final ClickType type;
  private long view;
  private final boolean stow;
  private final boolean menuView;
  private final boolean autoStow;
  private boolean stowIssued, stowDone;
  private long stowBefore;
  private int stowTarget = -1;
  private String stowNote = "";
  private Object menu;
  private Screen screen;
  private List<ItemStack> expected;
  private ItemStack cursor;
  private long before;
  private boolean issued, changed;
  private int affected;
  private ItemStack priorTarget, priorCursor;
  private boolean refreshRequested;
  private final String style = RuntimeStrategy.executionStyle();

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public MenuClickJob(
      int slot, int button, String type, long view, boolean stow, Consumer<String> done) {
    this(slot, button, type, view, stow, false, false, done);
  }

  /**
   * click_slot path: {@code view} is the newest menu observation number from inspect_menu or
   * inspect_gui, validated through MenuContext instead of the GUI stamp.
   */
  public MenuClickJob(int slot, int button, String type, long view, Consumer<String> done) {
    this(slot, button, type, view, false, true, false, done);
  }

  /** click_slot path with autoStow: a held cursor is first stored in the player inventory. */
  public static MenuClickJob clickSlot(
      int slot, int button, String type, long view, boolean autoStow, Consumer<String> done) {
    return new MenuClickJob(slot, button, type, view, false, true, autoStow, done);
  }

  private MenuClickJob(
      int slot,
      int button,
      String type,
      long view,
      boolean stow,
      boolean menuView,
      boolean autoStow,
      Consumer<String> done) {
    super(done);
    this.slot = slot;
    this.button = button;
    this.view = view;
    this.stow = stow;
    this.menuView = menuView;
    this.autoStow = autoStow;
    if (!Set.of("PICKUP", "QUICK_MOVE", "SWAP").contains(type))
      throw new IllegalArgumentException("仅支持PICKUP/QUICK_MOVE/SWAP");
    this.type = ClickType.valueOf(type);
    if (button < 0 || button > (type.equals("SWAP") ? 8 : 1))
      throw new IllegalArgumentException("PICKUP/QUICK_MOVE按钮0..1；SWAP为快捷栏0..8");
  }

  @Override
  void tick() {
    if (!issued) {
      if (menuView && autoStow && !stowDone) {
        autoStowStep();
        return;
      }
      if (stow) GuiContext.observe();
      else if (menuView) MenuContext.validateStored(view, slot);
      else GuiContext.validateSlot(view, slot);
      if (!MenuContext.synchronizedNow()) {
        finishResult("失败：菜单尚无稳定服务器快照，请等候再观察。");
        return;
      }
      var m = mc().player.containerMenu;
      int target = slot;
      if (stow) {
        if (m.getCarried().isEmpty()) {
          finishResult("鼠标没有持有物品。");
          return;
        }
        target = -1;
        for (int i = 0; i < m.slots.size(); i++) {
          var s = m.getSlot(i);
          if (s.container == mc().player.getInventory()
              && !s.hasItem()
              && s.mayPlace(m.getCarried())
              && s.getMaxStackSize(m.getCarried()) >= m.getCarried().getCount()) {
            target = i;
            break;
          }
        }
        if (target < 0) {
          finishResult("失败：没有可完整存放鼠标物品的空背包槽；保持界面，不丢弃。");
          return;
        }
      }
      if (target < 0 || target >= m.slots.size()) {
        finishResult("失败：槽位越界。");
        return;
      }
      if (type == ClickType.SWAP) {
        var source = m.getSlot(target);
        var held = mc().player.getInventory().getItem(button);
        if (!m.getCarried().isEmpty()
            || !source.mayPickup(mc().player)
            || !held.isEmpty()
                && (!source.mayPlace(held) || held.getCount() > source.getMaxStackSize(held))) {
          finishResult("失败：当前槽不允许完整快捷栏交换");
          return;
        }
      }
      var prior = m.slots.stream().map(s -> s.getItem().copy()).toList();
      var oldCursor = m.getCarried().copy();
      affected = target;
      priorTarget = m.getSlot(target).getItem().copy();
      priorCursor = oldCursor.copy();
      menu = m;
      screen = mc().screen;
      before = MenuContext.revision();
      Actions.click(target, stow ? 0 : button, stow ? ClickType.PICKUP : type);
      expected = m.slots.stream().map(s -> s.getItem().copy()).toList();
      cursor = m.getCarried().copy();
      changed = !ItemStack.matches(oldCursor, cursor);
      for (int i = 0; i < prior.size(); i++)
        changed |= !ItemStack.matches(prior.get(i), expected.get(i));
      issued = true;
      if (menuView) MenuContext.consumeObservation();
      GuiContext.invalidate();
      if (!ExecutionStyle.fluent(style)) {
        refreshRequested = true;
        MenuRefresh.request();
      }
      return;
    }
    Actions.status = "GUI槽位操作 · 等待服务器核对";
    if (menu != mc().player.containerMenu || screen != mc().screen) {
      finishResult("部分完成：点击后界面改变，无法继续核对，未重试。");
      return;
    }
    if (MenuContext.revision() > before && MenuContext.synchronizedNow()) {
      var m = mc().player.containerMenu;
      boolean same = expected.size() == m.slots.size() && ItemStack.matches(cursor, m.getCarried());
      for (int i = 0; same && i < expected.size(); i++)
        same = ItemStack.matches(expected.get(i), m.getSlot(i).getItem());
      boolean observed =
          !ItemStack.matches(priorTarget, m.getSlot(affected).getItem())
              || !ItemStack.matches(priorCursor, m.getCarried());
      finishResult(
          same && changed
              ? "成功：槽位与鼠标物品变化已由服务器同步核对；生产完成需再检查产物。"
              : same
                  ? "部分完成：服务器确认槽位没有变化，请检查物品、槽位条件或机器适配。"
                  : observed
                      ? "成功：已取得服务器同步，目标槽或鼠标确有变化；异步结果不同于预测，须重新观察实际数量，不代表生产已完成。"
                      : "部分完成：服务器结果与点击预测不同，请重新观察；没有重复点击。");
      return;
    }
    ++ticks;
    if (ExecutionStyle.needsRefresh(style, refreshRequested, ticks, false)) {
      refreshRequested = true;
      MenuRefresh.request();
    }
    if (ticks > 100) finishResult("部分完成：点击已发送，但没有获得完整一致的服务器结果；未知机器可能需要专用适配。保留界面，请核对后再操作。");
  }

  /**
   * click_slot autoStow: stow the held cursor into the player inventory, then refresh the number.
   */
  private void autoStowStep() {
    var m = mc().player.containerMenu;
    var carried = m.getCarried();
    if (carried.isEmpty()) {
      stowDone = true;
      return;
    }
    if (!MenuContext.synchronizedNow()) {
      finish("失败：[MENU_UNCONFIRMED] 菜单尚无稳定服务器快照，请等候再观察。");
      return;
    }
    if (!stowIssued) {
      stowTarget = stowTargetSlot(m, carried);
      if (stowTarget < 0) {
        finish("失败：[INVENTORY_FULL] 鼠标持有物品且没有可完整接收的玩家槽；未丢弃，请先腾出背包空位或手动 stow_cursor。");
        return;
      }
      stowBefore = MenuContext.revision();
      Actions.status = "GUI槽位操作 · 自动收回鼠标物品";
      Actions.click(stowTarget, 0, ClickType.PICKUP);
      stowIssued = true;
      return;
    }
    if (MenuContext.revision() > stowBefore && MenuContext.synchronizedNow() && carried.isEmpty()) {
      stowNote = "已自动收回鼠标物品到玩家槽位 " + stowTarget + "；";
      view = GuiContext.observe().get("gui_view").getAsLong();
      stowDone = true;
      return;
    }
    ++ticks;
    if (ticks > 80) finish("部分完成：自动收回鼠标物品已发送但未取得服务器一致快照，未继续点击；请重新inspect_menu后重试。");
  }

  private void finishResult(String text) {
    finish(stowNote + text);
  }

  /** First player slot that can take the whole cursor stack, preferring a mergeable slot. */
  static int stowTargetSlot(AbstractContainerMenu menu, ItemStack carried) {
    var inventory = Minecraft.getInstance().player.getInventory();
    int empty = -1;
    for (int i = 0; i < menu.slots.size(); i++) {
      var s = menu.getSlot(i);
      if (s.container != inventory
          || s.getContainerSlot() < 0
          || s.getContainerSlot() >= 36
          || !s.mayPlace(carried)) continue;
      ItemStack existing = s.getItem();
      if (existing.isEmpty()) {
        if (empty < 0) empty = i;
      } else if (ItemStack.isSameItemSameTags(existing, carried)
          && existing.getCount() + carried.getCount() <= existing.getMaxStackSize()) {
        return i;
      }
    }
    return empty;
  }

  @Override
  String interrupted() {
    return "GUI操作已停止；已发出的点击可能生效，界面与鼠标物品保留，请核对。";
  }
}
