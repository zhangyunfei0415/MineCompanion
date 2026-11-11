package dev.mibao.companion;

import java.util.*;

/** Pure bounds for window-local input, never operating-system shortcuts. */
public final class GameInputPolicy {
  public static int key(int code) {
    if (code < 32 || code > 348 || code == 343 || code == 347)
      throw new IllegalArgumentException("[INVALID_KEY] 使用GLFW键码32..348，不支持系统Super键");
    return code;
  }

  public static int modifiers(int value) {
    if ((value & ~7) != 0)
      throw new IllegalArgumentException("[INVALID_MODIFIERS] 仅SHIFT=1 CTRL=2 ALT=4");
    return value;
  }

  public static int ticks(int value) {
    if (value < 1 || value > 200)
      throw new IllegalArgumentException("[INPUT_BOUNDS] ticks须为1..200");
    return value;
  }

  public static Set<Integer> keys(int key, int mods) {
    key(key);
    var out = modifierKeys(mods);
    out.add(key);
    return out;
  }

  /**
   * Virtual key codes for the modifier bitmask only (SHIFT=340, CTRL=341, ALT=342).
   *
   * <p>Wheel scrolling reads live modifier state through {@code Screen.hasControlDown()}, so scroll
   * paths need the modifiers held as virtual keys without any main key.
   */
  public static Set<Integer> modifierKeys(int mods) {
    modifiers(mods);
    var out = new LinkedHashSet<Integer>();
    if ((mods & 1) != 0) out.add(340);
    if ((mods & 2) != 0) out.add(341);
    if ((mods & 4) != 0) out.add(342);
    return out;
  }
}
