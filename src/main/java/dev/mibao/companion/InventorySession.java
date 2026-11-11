package dev.mibao.companion;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Owns one visible inventory screen and bounded authoritative refresh requests. */
public final class InventorySession {
  private final boolean showScreen;

  public InventorySession() {
    this(true);
  }

  /** World-use verification needs an authoritative snapshot, without flashing the inventory UI. */
  public InventorySession(boolean showScreen) {
    this.showScreen = showScreen;
  }

  private Screen screen;
  private boolean owned, requested;
  private long before;
  private int wait;
  public String error = "";

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public static List<ItemStack> snapshot() {
    return mc().player.inventoryMenu.slots.stream().map(s -> s.getItem().copy()).toList();
  }

  public void refresh() {
    requested = false;
    wait = 0;
  }

  public int tick() {
    var p = mc().player;
    if (mc().gameMode.getPlayerMode() != GameType.SURVIVAL
        && mc().gameMode.getPlayerMode() != GameType.ADVENTURE
        && mc().gameMode.getPlayerMode() != GameType.CREATIVE)
      return fail("[MODE_UNSUPPORTED] 旁观模式不支持背包写操作");
    if (p.containerMenu != p.inventoryMenu) return fail("其他容器已打开，请先正常关闭");
    if (screen == null && showScreen) {
      if (mc().screen == null) {
        screen = new InventoryScreen(p);
        owned = true;
        mc().setScreen(screen);
      } else if (mc().screen instanceof InventoryScreen) screen = mc().screen;
      else return fail("当前界面不允许打开背包");
    }
    if (mc().screen != screen) return fail("背包界面已改变，停止操作");
    if (!ClientEvidence.attached()) return fail("服务器证据接收器不可用");
    if (!requested) {
      before = InventoryEvidence.fullRevision;
      requested = true;
      // Vanilla 1.20.1 accepts slot -1 PICKUP as a no-op (unlike -999 which drops).
      // An out-of-date state requests a full authoritative resync. No inventory/world mutation.
      // Only menu 0 is allowed. Never issue outside-drop clicks or change the local state id.
      mc().getConnection()
          .send(
              new ServerboundContainerClickPacket(
                  0, -1, -1, 0, ClickType.PICKUP, ItemStack.EMPTY, new Int2ObjectOpenHashMap<>()));
      return 0;
    }
    if (InventoryEvidence.fullRevision > before) {
      var actual = snapshot();
      if (InventoryProof.confirms(
          before,
          InventoryEvidence.fullRevision,
          actual,
          InventoryEvidence.slots,
          actual,
          p.inventoryMenu.getCarried(),
          InventoryEvidence.cursor,
          p.inventoryMenu.getCarried(),
          ItemStack::matches)) return 1;
    }
    if (++wait > 100) return fail("5秒内没有完整且一致的服务器背包快照，停止并保留现场");
    return 0;
  }

  public boolean confirms(List<ItemStack> expected, ItemStack cursor) {
    return InventoryProof.confirms(
        before,
        InventoryEvidence.fullRevision,
        expected,
        InventoryEvidence.slots,
        snapshot(),
        cursor,
        InventoryEvidence.cursor,
        mc().player.inventoryMenu.getCarried(),
        ItemStack::matches);
  }

  private int fail(String reason) {
    error = reason;
    return -1;
  }

  public void close() {
    if (mc().player != null
        && owned
        && mc().screen == screen
        && requested
        && confirms(snapshot(), mc().player.inventoryMenu.getCarried())
        && canClose()) mc().player.closeContainer();
  }

  public static boolean canClose() {
    var m = mc().player.containerMenu;
    if (!m.getCarried().isEmpty()) return false;
    int end =
        m == mc().player.inventoryMenu
            ? 4
            : m instanceof net.minecraft.world.inventory.CraftingMenu ? 9 : 0;
    for (int i = 1; i <= end; i++) if (!m.getSlot(i).getItem().isEmpty()) return false;
    return true;
  }
}
