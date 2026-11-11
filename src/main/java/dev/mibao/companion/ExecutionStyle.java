package dev.mibao.companion;

/**
 * Prefer existing authoritative updates in fluent mode, then fall back once, never replay clicks.
 */
public final class ExecutionStyle {
  public static boolean fluent(String value) {
    return "FLUENT".equals(value);
  }

  public static boolean needsRefresh(
      String style, boolean requested, int waitingTicks, boolean synchronizedResult) {
    return !requested && !synchronizedResult && (!fluent(style) || waitingTicks >= 5);
  }

  private ExecutionStyle() {}
}
