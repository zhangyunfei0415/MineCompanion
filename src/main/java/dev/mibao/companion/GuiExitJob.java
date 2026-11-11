package dev.mibao.companion;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/**
 * Auto-stow the cursor, then send Esc ({@code back}) or close the item screen. Used by {@code
 * gui_back}/{@code close_screen} so a user who still holds an item is not stuck: the native paths
 * reject a non-empty cursor. A craft-grid close still refuses instead of discarding items.
 */
final class GuiExitJob extends Actions.Job {
  private final boolean back;
  private boolean stowIssued, stowed;
  private long stowBefore;
  private int stowTarget = -1;
  private String stowNote = "";

  GuiExitJob(boolean back, Consumer<String> done) {
    super(done);
    this.back = back;
  }

  @Override
  void tick() {
    var mc = Minecraft.getInstance();
    if (mc.player == null) {
      finish("失败：玩家不可用。");
      return;
    }
    if (mc.screen == null) {
      finish(back ? "成功：已在世界。" : "界面已经关闭。");
      return;
    }
    AbstractContainerMenu menu = mc.player.containerMenu;
    ItemStack carried = menu.getCarried();
    if (!stowIssued) {
      if (carried.isEmpty()) {
        stowed = true;
      } else {
        stowTarget = MenuClickJob.stowTargetSlot(menu, carried);
        if (stowTarget < 0) {
          finish("失败：[INVENTORY_FULL] 鼠标持有物品且没有可完整接收的玩家槽；未丢弃，请先腾出背包空位或手动 stow_cursor。");
          return;
        }
        if (!MenuContext.targetConfirmed(stowTarget)) {
          finish("失败：[MENU_UNCONFIRMED] 收回目标槽或鼠标物品尚无一致服务器快照；先刷新观察，未关闭界面。");
          return;
        }
        stowBefore = MenuContext.revision();
        Actions.status = "GUI退出 · 自动收回鼠标物品";
        Actions.click(stowTarget, 0, ClickType.PICKUP);
        stowIssued = true;
        return;
      }
    } else if (!stowed) {
      if (carried.isEmpty()
          && MenuContext.revision() > stowBefore
          && MenuContext.targetConfirmed(stowTarget)) {
        stowed = true;
        stowNote = "已自动收回鼠标物品到玩家槽位 " + stowTarget + "；";
      } else {
        ++ticks;
        if (ticks > 80) finish("部分完成：自动收回鼠标物品已发送但没有服务器一致确认，未关闭界面；请核对后再操作。");
        return;
      }
    }
    Actions.status = "GUI退出 · 发送关闭";
    if (back) finish(stowNote + ScreenInput.back());
    else finish(stowNote + close());
  }

  private String close() {
    var mc = Minecraft.getInstance();
    var screen = mc.screen;
    if (screen == null) return "界面已经关闭。";
    if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
      if (!InventorySession.canClose())
        return "失败：[SCREEN_BLOCKING] 合成栏仍有物品；不会自动关闭，请先清理合成栏或手动返回世界。";
      mc.player.closeContainer();
      return "物品界面已关闭；服务器结果请重新observe核对。";
    }
    screen.onClose();
    return "界面关闭请求已发送（非物品界面使用Esc/onClose），请重新观察。";
  }

  @Override
  String interrupted() {
    return "自动收回/返回已停止；未确认的操作请核对界面与鼠标物品。";
  }
}
