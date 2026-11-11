package dev.mibao.companion;

import java.util.*;
import java.util.function.BiPredicate;

/** Server receipt evidence, separate from the menu's locally predicted contents. */
public final class MenuSyncEvidence<T> {
  private final BiPredicate<T, T> same;
  private Object owner;
  private int id, state;
  private long revision, received;
  private List<T> slots;
  private T cursor;

  public MenuSyncEvidence(BiPredicate<T, T> same) {
    this.same = same;
  }

  public void bind(Object owner, int id) {
    if (this.owner != owner || this.id != id) {
      clear();
      this.owner = owner;
      this.id = id;
    }
  }

  public void clear() {
    owner = null;
    slots = null;
    cursor = null;
    received = 0;
    revision++;
  }

  public void content(Object owner, int id, int state, List<T> slots, T cursor, long now) {
    if (this.owner != owner || this.id != id) return;
    this.slots = new ArrayList<>(slots);
    this.cursor = cursor;
    this.state = state;
    received = now;
    revision++;
  }

  public void slot(Object owner, int id, int state, int slot, T value, long now) {
    if (this.owner != owner || this.id != id || slots == null || slot < 0 || slot >= slots.size())
      return;
    slots.set(slot, value);
    this.state = state;
    received = now;
    revision++;
  }

  public void cursor(Object owner, T value, long now) {
    if (this.owner == owner && slots != null) {
      cursor = value;
      received = now;
      revision++;
    }
  }

  public boolean ready(
      Object owner, int id, List<T> current, T cursor, long now, long quietMillis) {
    if (this.owner != owner
        || this.id != id
        || slots == null
        || slots.size() != current.size()
        || now - received < quietMillis
        || !same.test(this.cursor, cursor)) return false;
    for (int i = 0; i < slots.size(); i++)
      if (!same.test(slots.get(i), current.get(i))) return false;
    return true;
  }

  public boolean received() {
    return slots != null;
  }

  /** Confirm just the affected slot and cursor; unrelated animated/virtual slots may change. */
  public boolean targetReady(Object owner, int id, int slot, T current, T cursor) {
    return this.owner == owner
        && this.id == id
        && slots != null
        && slot >= 0
        && slot < slots.size()
        && same.test(slots.get(slot), current)
        && same.test(this.cursor, cursor);
  }

  public int size() {
    return slots == null ? 0 : slots.size();
  }

  public long revision() {
    return revision;
  }

  public int state() {
    return state;
  }

  public long receivedAt() {
    return received;
  }
}
