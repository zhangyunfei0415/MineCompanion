package dev.mibao.companion;

import java.util.*;
import java.util.function.BiPredicate;

/** An observed slot/cursor snapshot is required even when server state IDs have not changed. */
public final class MenuEvidence<T> {
  private final BiPredicate<T, T> same;
  private Object owner;
  private int menu, state;
  private long token;
  private Map<Integer, T> slots = Map.of();
  private T cursor;

  public MenuEvidence(BiPredicate<T, T> same) {
    this.same = same;
  }

  public long capture(Object owner, int menu, int state, Map<Integer, T> slots, T cursor) {
    this.owner = owner;
    this.menu = menu;
    this.state = state;
    this.slots = Map.copyOf(slots);
    this.cursor = cursor;
    return ++token;
  }

  public void clear() {
    owner = null;
    slots = Map.of();
    token++;
  }

  public boolean valid(
      Object owner,
      int menu,
      int state,
      long token,
      int target,
      Map<Integer, T> current,
      T cursor) {
    if (this.owner != owner
        || this.menu != menu
        || this.state != state
        || this.token != token
        || !slots.containsKey(target)
        || !same.test(this.cursor, cursor)) return false;
    for (var slot : slots.entrySet())
      if (!current.containsKey(slot.getKey())
          || !same.test(slot.getValue(), current.get(slot.getKey()))) return false;
    return true;
  }

  /** Content packets for unrelated slots must not revoke an unchanged target/cursor. */
  public boolean validTarget(Object owner, int menu, long token, int target, T current, T cursor) {
    return this.owner == owner
        && this.menu == menu
        && this.token == token
        && slots.containsKey(target)
        && same.test(slots.get(target), current)
        && same.test(this.cursor, cursor);
  }
}
