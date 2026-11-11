package dev.mibao.companion;

import java.util.Objects;

/** Screens, widgets, contents and geometry must all still match the latest observation. */
public final class GuiGuard {
  public static boolean valid(
      long expected, long current, Object screen, Object nowScreen, String snapshot, String now) {
    return expected > 0
        && expected == current
        && screen == nowScreen
        && Objects.equals(snapshot, now);
  }
}
