package dev.mibao.companion;

import java.util.*;

/** Quantity ledger keyed by full item fingerprint; no predicted click counts as a receipt. */
public final class ProductionProof {
  public static boolean safeOutputTag(
      boolean tagged, boolean damageable, int damage, Set<String> keys) {
    return !tagged || damageable && damage == 0 && keys.equals(Set.of("Damage"));
  }

  public static Map<String, Integer> crafted(
      Map<String, Integer> before, Map<String, Integer> used, String output, int amount) {
    var result = new HashMap<>(before);
    used.forEach(
        (k, v) -> {
          if (v < 1 || result.getOrDefault(k, 0) < v) throw new IllegalArgumentException("材料账本不足");
          result.merge(k, -v, Integer::sum);
        });
    if (amount < 1) throw new IllegalArgumentException("产物数量无效");
    result.merge(output, amount, Integer::sum);
    result.entrySet().removeIf(e -> e.getValue() == 0);
    return result;
  }

  public static boolean confirms(
      long before,
      long after,
      boolean synced,
      Map<String, Integer> expected,
      Map<String, Integer> actual,
      boolean cursorEmpty,
      boolean gridEmpty) {
    return after > before && synced && expected.equals(actual) && cursorEmpty && gridEmpty;
  }
}
