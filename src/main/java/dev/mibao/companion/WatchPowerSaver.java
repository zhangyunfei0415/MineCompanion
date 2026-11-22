package dev.mibao.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/**
 * Applies optional FPS cap + master volume mute while F4 WatchMode or manual eco mode is active,
 * restoring the user's exact window FPS cap and volume on exit without modifying options.txt.
 */
public final class WatchPowerSaver {
  private static final WatchPowerState state = new WatchPowerState();

  private WatchPowerSaver() {}

  public static boolean active() {
    return state.isApplied();
  }

  public static boolean manualActive() {
    return state.isManualOverride();
  }

  public static void toggleManual() {
    state.setManualOverride(!state.isManualOverride());
    sync();
  }

  public static void resetOnDisconnect() {
    state.setManualOverride(false);
    sync();
    state.reset();
  }

  public static String badgeText() {
    if (!state.isApplied()) return "";
    StringBuilder sb = new StringBuilder("省电 ");
    boolean fps = state.isFpsApplied();
    boolean audio = state.isAudioApplied();
    if (fps) sb.append(WatchPowerState.clampFpsCap(Store.config.watchFpsCap)).append("FPS");
    if (fps && audio) sb.append("·");
    if (audio) sb.append("静音");
    return sb.toString();
  }

  public static void tick() {
    if (!state.isApplied()) return;
    var mc = Minecraft.getInstance();
    if (mc == null) return;
    if (state.isFpsApplied() && mc.getWindow() != null) {
      int cap = WatchPowerState.clampFpsCap(Store.config.watchFpsCap);
      if (mc.getWindow().getFramerateLimit() != cap) mc.getWindow().setFramerateLimit(cap);
    }
    if (state.isAudioApplied() && mc.getSoundManager() != null) {
      mc.getSoundManager().updateSourceVolume(SoundSource.MASTER, 0.0f);
    }
  }

  public static void sync() {
    var mc = Minecraft.getInstance();
    if (mc == null || mc.options == null || mc.getWindow() == null) return;
    int currentFps = mc.getWindow().getFramerateLimit();
    double currentVol = mc.options.getSoundSourceVolume(SoundSource.MASTER);
    var act =
        state.sync(
            WatchMode.active(),
            Store.config.watchLimitFps,
            Store.config.watchFpsCap,
            Store.config.watchMuteAudio,
            currentFps,
            currentVol);
    if (act.applyCap()) {
      mc.getWindow().setFramerateLimit(act.fpsCap());
    } else if (act.restoreFps()) {
      mc.getWindow().setFramerateLimit(act.restoredFps());
    }
    if (mc.getSoundManager() != null) {
      if (act.applyMute()) {
        mc.getSoundManager().updateSourceVolume(SoundSource.MASTER, 0.0f);
      } else if (act.restoreAudio()) {
        mc.getSoundManager()
            .updateSourceVolume(
                SoundSource.MASTER, mc.options.getSoundSourceVolume(SoundSource.MASTER));
      }
    }
  }
}
