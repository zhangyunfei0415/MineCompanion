package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.Minecraft;

/**
 * Game-local key polling bridge; hooks do nothing unless a live AI lease owns this world window.
 */
public final class VirtualInput {
  private static final VirtualKeyState state = new VirtualKeyState();
  public static boolean keyHookSeen, focusHookSeen;
  private static boolean textInput;

  /**
   * Set while the quest-book bridge sends a wheel event: that path is not a GameInputJob, so the
   * job-type check in live() would otherwise suppress the virtual CTRL used for zoom.
   */
  static boolean scrollInput;

  public static void physicalText(Runnable action) {
    boolean old = textInput;
    textInput = true;
    try {
      action.run();
    } finally {
      textInput = old;
    }
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  private static boolean live() {
    return Agent.enabled
        && !Agent.paused
        && mc().player != null
        && mc().level != null
        && (mc().screen == null || Actions.job instanceof GameInputJob || scrollInput)
        && (!mc().isPaused() || Actions.job instanceof GameInputJob || scrollInput)
        && state.active(mc().getWindow().getWindow(), Agent.epoch, System.nanoTime());
  }

  public static void begin(Collection<Integer> keys) {
    state.begin(mc().getWindow().getWindow(), Agent.epoch, System.nanoTime(), keys);
  }

  public static void beat() {
    if (!state.empty()) state.beat(System.nanoTime());
  }

  public static void clear() {
    state.clear();
  }

  public static boolean owned() {
    return !state.empty();
  }

  public static int keyState(long window, int key) {
    keyHookSeen = true;
    if (textInput) return -1;
    int owned =
        state.empty() || !live() ? -1 : state.query(window, Agent.epoch, System.nanoTime(), key);
    if (owned >= 0) return owned;
    if ((WatchMode.active() || LocalDialogue.editing)
        && window == mc().getWindow().getWindow()
        && !(Panel.managing(mc().screen))) return 0;
    return -1;
  }

  public static boolean focused() {
    focusHookSeen = true;
    return !state.empty() && live() && Store.config.backgroundInput;
  }
}
