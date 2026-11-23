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
    var mc = mc();
    if (mc.player == null) return;
    if (!held) {
      held = true;
      PlayerControls.hold(mc.options.keyShift);
      if (mc.player.input != null) mc.player.input.shiftKeyDown = true;
      mc.player.setShiftKeyDown(true);
      if (mc.getConnection() != null) {
        mc.getConnection()
            .send(
                new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
      }
    } else {
      maintain();
    }
  }

  /** Re-asserts sneak input each tick so LocalPlayer.aiStep does not clear shiftKeyDown before useItemOn. */
  void maintain() {
    if (!held) return;
    var mc = mc();
    if (mc.player == null) return;
    mc.options.keyShift.setDown(true);
    if (mc.player.input != null) mc.player.input.shiftKeyDown = true;
    mc.player.setShiftKeyDown(true);
  }

  void release() {
    if (!held) return;
    held = false;
    var mc = mc();
    mc.options.keyShift.setDown(false);
    PlayerControls.release();
    if (mc.player == null) return;
    if (mc.player.input != null) mc.player.input.shiftKeyDown = false;
    mc.player.setShiftKeyDown(false);
    if (mc.getConnection() != null) {
      mc.getConnection()
          .send(
              new ServerboundPlayerCommandPacket(
                  mc.player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
    }
  }

  boolean held() {
    return held;
  }
}
