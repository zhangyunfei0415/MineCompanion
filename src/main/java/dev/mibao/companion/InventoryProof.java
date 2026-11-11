package dev.mibao.companion;

import java.util.*;
import java.util.function.BiPredicate;

/** A full snapshot newer than the operation, not a predicted click, proves the postcondition. */
public final class InventoryProof {
  public static <T> List<T> swapped(List<T> before, int from, int to) {
    if (from < 0 || to < 0 || from >= before.size() || to >= before.size())
      throw new IllegalArgumentException("槽位越界");
    List<T> out = new ArrayList<>(before);
    Collections.swap(out, from, to);
    return out;
  }

  public static <T> boolean confirms(
      long before,
      long received,
      List<T> expected,
      List<T> server,
      List<T> actual,
      T expectedCursor,
      T serverCursor,
      T actualCursor,
      BiPredicate<T, T> same) {
    if (received <= before || expected.size() != server.size() || server.size() != actual.size())
      return false;
    for (int i = 0; i < expected.size(); i++)
      if (!same.test(expected.get(i), server.get(i)) || !same.test(server.get(i), actual.get(i)))
        return false;
    return same.test(expectedCursor, serverCursor) && same.test(serverCursor, actualCursor);
  }
}
