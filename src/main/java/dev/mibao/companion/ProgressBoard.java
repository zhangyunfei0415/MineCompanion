package dev.mibao.companion;

import com.google.gson.JsonObject;

/**
 * User-facing declared plan banner plus durable per-world GoalBoard projection. Transient clear
 * wipes only the short-lived turn banner while preserving durable API-mode GoalBoard steps across
 * reconnects and compactions.
 */
public final class ProgressBoard {
  private static long epoch = -1, at;
  private static String goal = "", current = "", next = "", tool = "";

  public static void clear() {
    clearTransient();
  }

  /** Clears only the ephemeral per-turn banner; keeps Store.data GoalBoard intact across prompts. */
  public static void clearTransient() {
    epoch = -1;
    at = 0;
    goal = current = next = tool = "";
  }

  public static void report(String g, String c, String n) {
    TaskTimeline.add("plan", g, c + "；下一步：" + n);
    epoch = Agent.epoch;
    at = System.currentTimeMillis();
    goal = Rules.clean(g, 180);
    current = Rules.clean(c, 160);
    next = Rules.clean(n, 160);
  }

  public static void action(String name) {
    if (!Rules.WRITE.contains(name)) return;
    if (epoch != Agent.epoch) {
      goal = current = next = "";
    }
    epoch = Agent.epoch;
    at = System.currentTimeMillis();
    tool = name;
  }

  public static JsonObject snapshot() {
    try {
      if (Store.data != null && Store.data.goalBoards != null) {
        var gb = Store.goalBoard();
        if (!gb.isEmpty()) {
          var snap = GoalBoard.toSnapshot(gb);
          if (!tool.isBlank() && epoch == Agent.epoch) snap.addProperty("last_action", tool);
          return snap;
        }
      }
    } catch (RuntimeException ignored) {
    }
    var o = new JsonObject();
    if (epoch != Agent.epoch || System.currentTimeMillis() - at > 600_000) return o;
    o.addProperty("goal", goal);
    o.addProperty("current", current);
    o.addProperty("next", next);
    o.addProperty("last_action", tool);
    o.addProperty("updated", at);
    o.addProperty("rule", "声明计划，不是完成证据；实际执行状态和回执另行核对");
    return o;
  }
}
