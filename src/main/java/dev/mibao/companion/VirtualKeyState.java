package dev.mibao.companion;

import java.util.*;

/**
 * Pure expiring lease. No operating-system input; foreign windows and expired epochs pass through.
 */
public final class VirtualKeyState {
  private final Set<Integer> keys = new HashSet<>();
  private long window, epoch, until;
  private boolean owned;

  public void begin(long window, long epoch, long now, Collection<Integer> keys) {
    this.window = window;
    this.epoch = epoch;
    this.keys.clear();
    this.keys.addAll(keys);
    owned = true;
    beat(now);
  }

  public void beat(long now) {
    until = now + 500_000_000L;
  }

  public boolean active(long window, long epoch, long now) {
    return owned && this.window == window && this.epoch == epoch && now < until;
  }

  public int query(long window, long epoch, long now, int key) {
    return active(window, epoch, now) && keys.contains(key) ? 1 : -1;
  }

  public boolean empty() {
    return !owned;
  }

  public void clear() {
    keys.clear();
    until = 0;
    owned = false;
  }
}
