package dev.mibao.companion;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Explicit destructive deletion, never implicit inventory preparation. */
final class CreativeClearJob extends Actions.Job {
  private final int slot;
  private final String fingerprint;
  private final InventorySession verify = new InventorySession(false);
  private boolean sent;

  CreativeClearJob(int slot, String fingerprint, boolean confirm, Consumer<String> done) {
    super(done);
    if (slot < 0 || slot > 35 || !confirm || fingerprint.isBlank())
      throw new IllegalArgumentException(
          "[INVALID_ARGUMENT] 需要slot0..35、实际fingerprint及confirm=true；只删除指定槽");
    this.slot = slot;
    this.fingerprint = fingerprint;
  }

  @Override
  void tick() {
    var mc = Minecraft.getInstance();
    if (mc.gameMode.getPlayerMode() != GameType.CREATIVE) {
      finish("失败：[MODE_REQUIRED] 仅创造模式可删除");
      return;
    }
    if (!sent) {
      if (mc.screen != null
          || mc.player.containerMenu != mc.player.inventoryMenu
          || !mc.player.inventoryMenu.getCarried().isEmpty())
        throw new IllegalArgumentException("[WRONG_CONTEXT] 先退出菜单并归位鼠标物品");
      if (!WorldSense.fingerprint(mc.player.getInventory().getItem(slot)).equals(fingerprint))
        throw new IllegalArgumentException("[ITEM_CHANGED] 当前槽与指定指纹不同，未删除");
      mc.player.getInventory().setItem(slot, ItemStack.EMPTY);
      mc.gameMode.handleCreativeModeItemAdd(ItemStack.EMPTY, InventoryLayout.menuSlot(slot));
      sent = true;
      return;
    }
    int r = verify.tick();
    if (r < 0) finish("部分完成：删除请求已发，" + verify.error + "；不重放");
    else if (r > 0)
      finish(
          mc.player.getInventory().getItem(slot).isEmpty()
              ? "成功：指定创造槽已删除并取得服务器同步；不是世界丢弃"
              : "部分完成：指定槽未清空，不重试");
  }
}
