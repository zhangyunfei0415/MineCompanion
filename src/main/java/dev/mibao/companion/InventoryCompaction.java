package dev.mibao.companion;

import java.util.*;

/** Consolidate equal fingerprints in the main bag only; preserve hotbar and equipment. */
public final class InventoryCompaction {
  public record Stack(String fingerprint, int count, int maximum) {}

  public record Merge(int from, int to, int moved) {}

  public static Merge next(List<Stack> slots) {
    if (slots.size() != 36) throw new IllegalArgumentException("需要36个随身槽位");
    for (int to = 9; to < 36; to++) {
      var a = slots.get(to);
      if (a.count() <= 0 || a.maximum() <= 1 || a.count() >= a.maximum()) continue;
      for (int from = 35; from > to; from--) {
        var b = slots.get(from);
        if (b.count() > 0 && a.fingerprint().equals(b.fingerprint()) && a.maximum() == b.maximum())
          return new Merge(from, to, Math.min(b.count(), a.maximum() - a.count()));
      }
    }
    return null;
  }

  private InventoryCompaction() {}
}
