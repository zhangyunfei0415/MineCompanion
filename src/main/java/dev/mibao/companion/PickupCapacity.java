package dev.mibao.companion;

import java.util.*;

/** Counts only matching variants and truly empty main-inventory slots. */
public final class PickupCapacity {
  public static boolean sweepClear(int skipped, boolean truncated, boolean capacityBlocked) {
    return skipped == 0 && !truncated && !capacityBlocked;
  }

  public record Slot(String fingerprint, int count, int maximum) {}

  public static int room(List<Slot> slots, String fingerprint, int itemMaximum) {
    int room = 0;
    for (var s : slots) {
      if (s.count <= 0) room += itemMaximum;
      else if (s.fingerprint.equals(fingerprint))
        room += Math.max(0, Math.min(itemMaximum, s.maximum) - s.count);
    }
    return room;
  }
}
