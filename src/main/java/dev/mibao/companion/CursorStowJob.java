package dev.mibao.companion;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/** Stow only into real player slots; AE proxy slot churn is irrelevant. */
public final class CursorStowJob extends Actions.Job {
  private Object menu;
  private int inventorySlot;
  private ItemStack expected;

  public CursorStowJob(Consumer<String> done) {
    super(done);
  }

  @Override
  void tick() {
    var mc = Minecraft.getInstance();
    var m = mc.player.containerMenu;
    if (menu == null) {
      var carried = m.getCarried();
      if (carried.isEmpty()) {
        finish("鼠标没有持有物品。");
        return;
      }
      for (int i = 0; i < m.slots.size(); i++) {
        var s = m.getSlot(i);
        int inv = s.getContainerSlot();
        if (s.container != mc.player.getInventory() || inv < 0 || inv >= 36 || !s.mayPlace(carried))
          continue;
        var old = s.getItem();
        if (!old.isEmpty() && !ItemStack.isSameItemSameTags(old, carried)) continue;
        if (old.getCount() + carried.getCount() > s.getMaxStackSize(carried)) continue;
        expected = carried.copy();
        expected.setCount(old.getCount() + carried.getCount());
        inventorySlot = inv;
        menu = m;
        PlayerEvidence.begin();
        mc.gameMode.handleInventoryMouseClick(m.containerId, i, 0, ClickType.PICKUP, mc.player);
        GuiContext.invalidate();
        MenuRefresh.request();
        return;
      }
      finish("失败：没有能完整接收鼠标物品的玩家槽；未丢弃物品。");
      return;
    }
    if (menu != m) {
      finish("部分完成：菜单已变，先观察，不重发点击。");
      return;
    }
    if (PlayerEvidence.cursorConfirmed()
        && m.getCarried().isEmpty()
        && ItemStack.matches(expected, PlayerEvidence.inventorySlot(inventorySlot))
        && ItemStack.matches(expected, mc.player.getInventory().getItem(inventorySlot))) {
      finish("成功：鼠标已清空，目标玩家槽与服务器回执一致。");
      return;
    }
    if (++ticks > 100) finish("部分完成：收好鼠标点击已发出，服务器证据不足；保留现场，不重放。");
  }
}
