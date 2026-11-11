package dev.mibao.companion;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Wait for a real synced slot, e.g. furnace output. Never equate a recipe preview with delivery.
 */
public final class MenuWaitJob extends Actions.Job {
  private final long view;
  private final int slot, count, limit;
  private final String item;
  private Object menu;
  private Screen screen;
  private boolean started;

  public MenuWaitJob(
      long view, int slot, String item, int count, int seconds, Consumer<String> done) {
    super(done);
    this.view = view;
    this.slot = slot;
    this.item = item;
    this.count = count;
    limit = seconds * 20;
  }

  @Override
  void tick() {
    var mc = Minecraft.getInstance();
    if (!started) {
      GuiContext.validate(view);
      if (slot < 0 || slot >= mc.player.containerMenu.slots.size()) {
        finish("失败：等待槽位越界。");
        return;
      }
      menu = mc.player.containerMenu;
      screen = mc.screen;
      started = true;
    }
    if (menu != mc.player.containerMenu || screen != mc.screen) {
      finish("部分完成：等待期间界面已改变，停止等待。");
      return;
    }
    var stack = mc.player.containerMenu.getSlot(slot).getItem();
    Actions.status = "GUI等待 · 槽" + slot + " · " + stack.getCount() + " / " + count;
    if (Actions.match(stack, item) && stack.getCount() >= count && MenuContext.synchronizedNow()) {
      finish("成功：服务器同步确认槽" + slot + "已有 " + stack.getCount() + " 件符合条件的物品。仅为当前槽位观察，尚未证明取出或交付。");
      return;
    }
    if (++ticks >= limit) finish("部分完成：等待槽位达到目标数量超时，机器内容保持原样，请检查原料、燃料或条件。");
  }
}
