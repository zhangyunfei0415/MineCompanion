package dev.mibao.companion;

import java.util.*;

/** Positive inventory deltas must agree with a later authoritative snapshot. */
public final class DeltaProof {
  /** The caller must have matched a fresh complete server snapshot to the actual inventory. */
  public static int gathered(
      Map<String, Integer> before,
      Map<String, Integer> finalInventory,
      boolean fullSnapshotVerified) {
    return fullSnapshotVerified ? gained(before, finalInventory, finalInventory) : 0;
  }

  public static Map<String, Integer> cargo(
      Map<String, Integer> before, Map<String, Integer> after, int verifiedMoved) {
    Map<String, Integer> added = new LinkedHashMap<>();
    Set<String> keys = new HashSet<>(before.keySet());
    keys.addAll(after.keySet());
    for (String k : keys) {
      int n = after.getOrDefault(k, 0) - before.getOrDefault(k, 0);
      if (n < 0) throw new IllegalArgumentException("取货期间其他变体减少");
      if (n > 0) added.put(k, n);
    }
    if (added.values().stream().mapToInt(Integer::intValue).sum() != verifiedMoved)
      throw new IllegalArgumentException("新增变体与转移证明不一致");
    return added;
  }

  public static int gained(
      Map<String, Integer> before, Map<String, Integer> current, Map<String, Integer> server) {
    Map<String, Integer> positive = new HashMap<>(), negative = new HashMap<>();
    Set<String> keys = new HashSet<>(before.keySet());
    keys.addAll(current.keySet());
    for (String k : keys) {
      int actual = current.getOrDefault(k, 0);
      if (!Objects.equals(server.getOrDefault(k, 0), actual)) continue;
      int delta = actual - before.getOrDefault(k, 0);
      String id = k.split("#", 2)[0];
      if (delta > 0) positive.merge(id, delta, Integer::sum);
      else if (delta < 0) negative.merge(id, -delta, Integer::sum);
    }
    int n = 0;
    for (var e : positive.entrySet())
      n += Math.max(0, e.getValue() - negative.getOrDefault(e.getKey(), 0));
    return n;
  }

  public static boolean planted(
      boolean serverYoungCrop, int seedBefore, int actualSeed, int confirmedSeed) {
    return serverYoungCrop && actualSeed == confirmedSeed && actualSeed == seedBefore - 1;
  }
}
