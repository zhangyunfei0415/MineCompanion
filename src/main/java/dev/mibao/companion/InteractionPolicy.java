package dev.mibao.companion;

import java.util.Collection;
import java.util.List;

/** Pure policy for non-destructive left-tap blocks (drawers etc.). Zero net.minecraft.* imports. */
public final class InteractionPolicy {
  public static boolean leftTap(
      boolean survival, boolean emptyMain, String hand, String block, float progress) {
    return leftTap(survival, emptyMain, hand, block, progress, List.of());
  }

  public static boolean leftTap(
      boolean survival,
      boolean emptyMain,
      String hand,
      String block,
      float progress,
      Collection<String> extraLeftTap) {
    if (!survival || !emptyMain || !"main".equals(hand) || !(progress > 0 && progress < 1))
      return false;
    if (block == null || block.isBlank()) return false;
    return block.startsWith("functionalstorage:")
        || block.startsWith("storagedrawers:")
        || WhitelistPolicy.matchesPrefixOrExact(block, extraLeftTap);
  }
}
