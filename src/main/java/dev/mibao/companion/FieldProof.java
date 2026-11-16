package dev.mibao.companion;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure completion rules shared with regression tests. */
final class FieldProof {
  static boolean covered(boolean scanComplete, int dark, int unknown) {
    return scanComplete && dark == 0 && unknown == 0;
  }

  static int fuelTicks(int normalTicks, boolean accelerated) {
    return Math.max(0, normalTicks) / (accelerated ? 2 : 1);
  }

  static boolean consumed(
      boolean damageable, int exactBefore, int exactAfter, int typeBefore, int typeAfter) {
    return !damageable && exactAfter < exactBefore && typeAfter < typeBefore;
  }

  static Map<String, Integer> caught(
      Map<String, Integer> before, Map<String, Integer> after, Map<String, Integer> pickedUp) {
    Map<String, Integer> confirmed = new LinkedHashMap<>();
    pickedUp.forEach(
        (key, amount) -> {
          int delta = Math.max(0, after.getOrDefault(key, 0) - before.getOrDefault(key, 0));
          int proven = Math.min(Math.max(0, amount), delta);
          if (proven > 0) confirmed.put(key, proven);
        });
    return confirmed;
  }

  private FieldProof() {}
}
