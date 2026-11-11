package dev.mibao.companion;

import java.util.*;

/** Vanilla right-drag distribution, independent of screen pixels and Mouse Tweaks internals. */
public final class GesturePlan {
  public record Click(int slot, int button, String type) {}

  public static List<Click> oneEach(
      int source, List<Integer> targets, int available, boolean drag) {
    if (source < 0
        || targets.isEmpty()
        || targets.size() > 64
        || available < targets.size()
        || new HashSet<>(targets).size() != targets.size()
        || targets.stream().anyMatch(i -> i < 0 || i == source))
      throw new IllegalArgumentException("[GESTURE_TARGETS] 需1..64个不同目标槽、足够物品，不能含源槽");
    var clicks = new ArrayList<Click>();
    clicks.add(new Click(source, 0, "PICKUP"));
    if (drag && targets.size() > 1) {
      clicks.add(new Click(-999, 4, "QUICK_CRAFT"));
      for (int slot : targets) clicks.add(new Click(slot, 5, "QUICK_CRAFT"));
      clicks.add(new Click(-999, 6, "QUICK_CRAFT"));
    } else for (int slot : targets) clicks.add(new Click(slot, 1, "PICKUP"));
    clicks.add(new Click(source, 0, "PICKUP"));
    return List.copyOf(clicks);
  }

  private GesturePlan() {}
}
