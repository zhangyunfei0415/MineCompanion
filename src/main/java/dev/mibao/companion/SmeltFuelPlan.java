package dev.mibao.companion;

import java.util.*;

/** Pure preflight expressed in burn ticks, before any input or fuel is moved. */
final class SmeltFuelPlan {
  record Plan(String fuel, int required, int inFurnace, int inInventory, int toAdd, int missing) {}

  static Plan choose(
      int ticks,
      String existing,
      int oldCount,
      Map<String, Integer> stock,
      Map<String, Integer> durations,
      String input,
      int inputCount) {
    String fuel = existing;
    if (fuel.isBlank()) {
      fuel =
          durations.keySet().stream()
              .filter(id -> durations.get(id) > 0)
              .sorted(
                  Comparator.<String>comparingInt(
                          id ->
                              Math.max(
                                  0,
                                  (ticks + durations.get(id) - 1) / durations.get(id)
                                      - Math.max(
                                          0,
                                          stock.getOrDefault(id, 0)
                                              - (id.equals(input) ? inputCount : 0))))
                      // Least burn time thrown away first, then fewest items, then a stable id.
                      .thenComparingLong(id -> waste(ticks, durations.get(id)))
                      .thenComparingInt(id -> (ticks + durations.get(id) - 1) / durations.get(id))
                      .thenComparing(id -> id))
              .findFirst()
              .orElse("minecraft:coal");
    }
    int duration = durations.getOrDefault(fuel, 1600);
    int required = (ticks + duration - 1) / duration;
    int available =
        Math.max(0, stock.getOrDefault(fuel, 0) - (fuel.equals(input) ? inputCount : 0));
    int add = Math.max(0, required - oldCount);
    return new Plan(fuel, required, oldCount, available, add, Math.max(0, add - available));
  }

  /** Burn ticks left unused after cooking {@code ticks} with whole items of {@code duration}. */
  static long waste(int ticks, int duration) {
    long items = (ticks + (long) duration - 1) / duration;
    return items * duration - ticks;
  }
}
