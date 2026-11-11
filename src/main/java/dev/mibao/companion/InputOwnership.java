package dev.mibao.companion;

import java.util.*;
import java.util.function.Consumer;

/** Tracks only keys acquired by this controller. Repeated idle cleanup never touches human keys. */
public final class InputOwnership<T> extends AbstractSet<T> {
  private final Set<T> keys = new HashSet<>();

  public Iterator<T> iterator() {
    return keys.iterator();
  }

  public int size() {
    return keys.size();
  }

  public boolean add(T key) {
    return keys.add(key);
  }

  public void release(Consumer<T> keyUp) {
    var owned = List.copyOf(keys);
    keys.clear();
    owned.forEach(keyUp);
  }
}
