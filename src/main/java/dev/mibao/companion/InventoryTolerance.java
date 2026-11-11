package dev.mibao.companion;

import java.util.*;

/** Click receipts protect involved fingerprints. Idle observations may rebase separately. */
public final class InventoryTolerance {
  public static boolean onlyGains(Map<String, Integer> before, Map<String, Integer> after) {
    return before.entrySet().stream()
            .allMatch(e -> after.getOrDefault(e.getKey(), 0) >= e.getValue())
        && after.values().stream().allMatch(n -> n >= 0);
  }

  public static boolean confirms(
      Map<String, Integer> expected, Map<String, Integer> actual, Set<String> involved) {
    Set<String> keys = new HashSet<>(expected.keySet());
    keys.addAll(actual.keySet());
    for (String key : keys) {
      int e = expected.getOrDefault(key, 0), a = actual.getOrDefault(key, 0);
      if (a < 0 || e < 0 || a < e || involved.contains(key) && a != e) return false;
    }
    return true;
  }

  public static Map<String, Integer> delta(
      Map<String, Integer> before, Map<String, Integer> after) {
    Map<String, Integer> out = new TreeMap<>();
    Set<String> keys = new HashSet<>(before.keySet());
    keys.addAll(after.keySet());
    for (String k : keys) {
      int n = after.getOrDefault(k, 0) - before.getOrDefault(k, 0);
      if (n != 0) out.put(k, n);
    }
    return out;
  }

  /** A full furnace snapshot must account for every input unit; fuel is checked separately. */
  public static boolean furnaceBalance(int requested, int remaining, int produced, int perInput) {
    return requested > 0
        && remaining >= 0
        && produced >= 0
        && perInput > 0
        && produced % perInput == 0
        && remaining + produced / perInput == requested;
  }
}
