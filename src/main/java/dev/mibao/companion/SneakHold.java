package dev.mibao.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

/** Shared shift hold/release so an interrupted interaction job never leaves sneak pressed. */
final class SneakHold {
  private boolean held;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  void hold() {
    if (held || mc().player == null || mc().player.isShiftKeyDown()) return;
    held = true;
    mc().player.setShiftKeyDown(true);
    mc().getConnection()
        .send(
            new ServerboundPlayerCommandPacket(
                mc().player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
  }

  void release() {
    if (!held) return;
    held = false;
    if (mc().player == null) return;
    mc().player.setShiftKeyDown(false);
    mc().getConnection()
        .send(
            new ServerboundPlayerCommandPacket(
                mc().player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
  }

  boolean held() {
    return held;
  }
}
