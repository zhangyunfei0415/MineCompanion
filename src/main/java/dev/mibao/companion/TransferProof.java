package dev.mibao.companion;

import java.util.*;

/**
 * Compare a fresh reopened menu against the planned per-variant transfer, not client click
 * prediction.
 */
public final class TransferProof {
  public record Result(boolean verified, int moved, String reason) {}

  public static Result verify(
      Map<String, Integer> beforePlayer,
      Map<String, Integer> beforeBox,
      Map<String, Integer> afterPlayer,
      Map<String, Integer> afterBox,
      Map<String, Integer> plan,
      boolean deposit) {
    Set<String> keys = new HashSet<>();
    keys.addAll(beforePlayer.keySet());
    keys.addAll(beforeBox.keySet());
    keys.addAll(afterPlayer.keySet());
    keys.addAll(afterBox.keySet());
    keys.addAll(plan.keySet());
    int moved = 0;
    for (String key : keys) {
      int expected = plan.getOrDefault(key, 0);
      if (expected < 0) return new Result(false, 0, "invalid negative plan");
      int playerDelta = afterPlayer.getOrDefault(key, 0) - beforePlayer.getOrDefault(key, 0);
      int boxDelta = afterBox.getOrDefault(key, 0) - beforeBox.getOrDefault(key, 0);
      if (playerDelta != (deposit ? -expected : expected)
          || boxDelta != (deposit ? expected : -expected))
        return new Result(
            false,
            0,
            "reopened inventory differs from planned transfer; another player, slot correction or"
                + " item change may be involved");
      moved += expected;
    }
    return new Result(
        true, moved, "reopened player and container quantities match, including item variants");
  }
}
