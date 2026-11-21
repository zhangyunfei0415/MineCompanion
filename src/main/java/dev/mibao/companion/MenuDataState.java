package dev.mibao.companion;

import java.util.*;

/**
 * Pure server-synchronized ContainerData tracker (progress bars, burn time, energy/mode indices).
 * Zero Minecraft runtime imports so state transitions and deltas can be verified offline.
 */
public final class MenuDataState {
  public static final int MAX_SLOTS = 64;

  private int boundMenuId = -1;
  private final Map<Integer, Integer> values = new TreeMap<>();
  private long revision;

  public void bind(int menuId) {
    if (this.boundMenuId != menuId) {
      reset();
      this.boundMenuId = menuId;
    }
  }

  public void reset() {
    boundMenuId = -1;
    values.clear();
    revision++;
  }

  public boolean update(int menuId, int dataId, int value) {
    if (boundMenuId < 0 || boundMenuId != menuId || dataId < 0 || dataId >= MAX_SLOTS) {
      return false;
    }
    values.put(dataId, value);
    revision++;
    return true;
  }

  public long revision() {
    return revision;
  }

  public Map<Integer, Integer> snapshot() {
    return Map.copyOf(values);
  }

  public static Map<Integer, int[]> delta(Map<Integer, Integer> before, Map<Integer, Integer> after) {
    Map<Integer, int[]> changed = new TreeMap<>();
    Set<Integer> keys = new TreeSet<>();
    if (before != null) keys.addAll(before.keySet());
    if (after != null) keys.addAll(after.keySet());
    for (int k : keys) {
      Integer prev = before != null ? before.get(k) : null;
      Integer curr = after != null ? after.get(k) : null;
      if (!Objects.equals(prev, curr)) {
        changed.put(k, new int[] {prev != null ? prev : Integer.MIN_VALUE, curr != null ? curr : Integer.MIN_VALUE});
      }
    }
    return Map.copyOf(changed);
  }
}
