package dev.mibao.companion;

/**
 * Pure snapshot/restore state machine for F4 (`WatchMode`) & manual eco power-saver (FPS cap +
 * master mute). Never stores to vanilla options.txt and captures the user's baseline only once.
 */
public final class WatchPowerState {
  public record Action(
      boolean applyCap, int fpsCap, boolean applyMute, boolean restoreFps, int restoredFps, boolean restoreAudio, double restoredVolume) {}

  private boolean applied;
  private boolean appliedFps, appliedAudio;
  private int savedFps = 120;
  private double savedVolume = 1.0;
  private boolean manualOverride;

  public static int clampFpsCap(int fps) {
    return Math.max(15, Math.min(60, fps));
  }

  public static boolean shouldApply(
      boolean watchActive, boolean manualPowerSave, boolean limitFps, boolean muteAudio) {
    return manualPowerSave || (watchActive && (limitFps || muteAudio));
  }

  public boolean isApplied() {
    return applied;
  }

  public boolean isFpsApplied() {
    return applied && appliedFps;
  }

  public boolean isAudioApplied() {
    return applied && appliedAudio;
  }

  public boolean isManualOverride() {
    return manualOverride;
  }

  public void setManualOverride(boolean on) {
    manualOverride = on;
  }

  public synchronized Action sync(
      boolean watchActive,
      boolean limitFps,
      int configuredCap,
      boolean muteAudio,
      int currentWindowFps,
      double currentMasterVolume) {
    int cap = clampFpsCap(configuredCap);
    boolean wantAny = shouldApply(watchActive, manualOverride, limitFps, muteAudio);
    if (!wantAny) {
      if (!applied) return new Action(false, cap, false, false, savedFps, false, savedVolume);
      Action out =
          new Action(false, cap, false, appliedFps, savedFps, appliedAudio, savedVolume);
      applied = false;
      appliedFps = false;
      appliedAudio = false;
      return out;
    }
    if (!applied) {
      savedFps = Math.max(30, currentWindowFps);
      savedVolume = Math.max(0.0, Math.min(1.0, currentMasterVolume));
      applied = true;
    }
    boolean defaultBothManual = manualOverride && !limitFps && !muteAudio;
    boolean wantFps = limitFps || defaultBothManual;
    boolean wantAudio = muteAudio || defaultBothManual;
    boolean restoreFpsNow = appliedFps && !wantFps;
    boolean restoreAudioNow = appliedAudio && !wantAudio;
    appliedFps = wantFps;
    appliedAudio = wantAudio;
    return new Action(
        wantFps, cap, wantAudio, restoreFpsNow, savedFps, restoreAudioNow, savedVolume);
  }

  public synchronized void reset() {
    applied = false;
    appliedFps = false;
    appliedAudio = false;
    manualOverride = false;
  }
}
