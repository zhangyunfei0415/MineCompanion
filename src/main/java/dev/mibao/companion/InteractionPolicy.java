package dev.mibao.companion;

public final class InteractionPolicy {
  public static boolean leftTap(
      boolean survival, boolean emptyMain, String hand, String block, float progress) {
    return survival
        && emptyMain
        && hand.equals("main")
        && block.startsWith("functionalstorage:")
        && progress > 0
        && progress < 1;
  }
}
