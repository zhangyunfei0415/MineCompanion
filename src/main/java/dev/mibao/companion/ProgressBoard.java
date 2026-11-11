package dev.mibao.companion;

import com.google.gson.JsonObject;

/** User-facing declared plan, explicitly separate from actions and verified completion. */
public final class ProgressBoard {
  private static long epoch = -1, at;
  private static String goal = "", current = "", next = "", tool = "";

  public static void clear() {
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
