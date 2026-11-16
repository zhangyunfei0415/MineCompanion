package dev.mibao.companion;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Exact-variant production credit is bounded by pickup receipts and verified inventory delta. */
final class MiningLedger {
  final Map<String, Integer> received = new LinkedHashMap<>();
  final Map<String, Integer> delivered = new LinkedHashMap<>();

  Map<String, Integer> credit(
      Map<String, Integer> before,
      Map<String, Integer> after,
      Map<String, Integer> pickup,
      boolean inventoryVerified) {
    if (!inventoryVerified) return Map.of();
    var addition = FieldProof.caught(before, after, pickup);
    addition.forEach((key, n) -> received.merge(key, n, Math::addExact));
    return addition;
  }

  Map<String, Integer> cargo(Map<String, Integer> baseline, Map<String, Integer> actual) {
    Map<String, Integer> cargo = new LinkedHashMap<>();
    received.forEach(
        (key, quantity) -> {
          int carried = quantity - delivered.getOrDefault(key, 0);
          int available = Math.max(0, actual.getOrDefault(key, 0) - baseline.getOrDefault(key, 0));
          int count = Math.min(carried, available);
          if (count > 0) cargo.put(key, count);
        });
    return cargo;
  }

  boolean transfer(String fingerprint, int requested, int proven, boolean deposit) {
    int remaining = received.getOrDefault(fingerprint, 0) - delivered.getOrDefault(fingerprint, 0);
    if (!deposit || requested < 1 || proven < 0 || proven > requested || proven > remaining)
      return false;
    if (proven > 0) delivered.merge(fingerprint, proven, Math::addExact);
    return proven == requested;
  }

  int progress(String goal, int broken, Predicate<String> matches) {
    if (goal.equals("blocks")) return broken;
    return (goal.equals("deliver") ? delivered : received)
        .entrySet().stream()
            .filter(e -> matches.test(e.getKey()))
            .mapToInt(Map.Entry::getValue)
            .sum();
  }
}
