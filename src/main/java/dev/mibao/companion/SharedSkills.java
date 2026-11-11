package dev.mibao.companion;

import java.util.*;
import java.util.function.BiPredicate;

/** Merge old per-world libraries without overwriting or multiplying conflicting versions. */
public final class SharedSkills {
  private SharedSkills() {}

  public static <T> void merge(
      Map<String, T> target, Map<String, T> incoming, String source, BiPredicate<T, T> equivalent) {
    if (target == incoming || incoming == null) return;
    incoming.forEach(
        (name, value) -> {
          if (value == null) return;
          String candidate = name;
          int suffix = 1;
          while (target.containsKey(candidate)) {
            if (equivalent.test(target.get(candidate), value)) return;
            candidate = name + " (" + source + (suffix == 1 ? "" : " #" + suffix) + ")";
            suffix++;
          }
          target.put(candidate, value);
        });
  }
}
