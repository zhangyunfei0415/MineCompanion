package dev.mibao.companion;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/** One explicit distribution transaction; no mouse coordinates, no desktop events. */
public final class GuiGestureJob extends Actions.Job {
  private final int source;
  private final List<Integer> targets;
  private final long view;
  private List<GesturePlan.Click> clicks;
  private int index;
  private Object menu;
  private Object screen;
  private long revision;
  private List<ItemStack> expected;
  private ItemStack cursor;

  public GuiGestureJob(int source, List<Integer> targets, long view, Consumer<String> done) {
    super(done);
    this.source = source;
    this.targets = List.copyOf(targets);
    this.view = view;
  }

  void tick() {
    var mc = Minecraft.getInstance();
    var m = mc.player.containerMenu;
    if (clicks == null) {
      GuiContext.validateSlot(view, source);
      if (!MenuContext.synchronizedNow())
        throw new IllegalArgumentException("[MENU_UNCONFIRMED] 先取得真实菜单同步");
      if (!m.getCarried().isEmpty()) throw new IllegalArgumentException("鼠标需为空");
      var value = m.getSlot(source).getItem();
      if (!m.getSlot(source).mayPickup(mc.player)) throw new IllegalArgumentException("源槽不允许取出");
      for (int target : targets) {
        GuiContext.validateSlot(view, target);
        var slot = m.getSlot(target);
        if (!slot.mayPlace(value)
            || !slot.getItem().isEmpty() && !ItemStack.isSameItemSameTags(slot.getItem(), value)
            || slot.getMaxStackSize(value) - slot.getItem().getCount() < 1)
          throw new IllegalArgumentException("目标槽不接收一件源物品");
      }
      clicks = GesturePlan.oneEach(source, targets, value.getCount(), Store.config.craftGestures);
      menu = m;
      screen = mc.screen;
    }
    if (menu != m || screen != mc.screen) {
      finish("部分完成：手势期间菜单改变，保留现场");
      return;
    }
    if (index < clicks.size()) {
      var c = clicks.get(index++);
      Actions.click(c.slot(), c.button(), ClickType.valueOf(c.type()));
      GuiContext.invalidate();
      if (index == clicks.size()) {
        expected = m.slots.stream().map(s -> s.getItem().copy()).toList();
        cursor = m.getCarried().copy();
        revision = MenuContext.revision();
        MenuRefresh.request();
      }
      return;
    }
    if (MenuContext.revision() > revision && MenuContext.synchronizedNow()) {
      boolean same = ItemStack.matches(cursor, m.getCarried());
      for (int i = 0; same && i < expected.size(); i++)
        same = ItemStack.matches(expected.get(i), m.getSlot(i).getItem());
      finish(
          same ? "成功：逐格分配事务经服务器槽位核对；分配格数 " + targets.size() + "，生产结果须另核验" : "部分完成：手势结果与预测不同，未重放");
      return;
    }
    if (++ticks > 120) finish("部分完成：手势已发送但未取得服务器确认，不重放");
  }
}
