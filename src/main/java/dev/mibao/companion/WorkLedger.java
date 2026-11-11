package dev.mibao.companion;

import java.util.*;

/** Bounded world-local observations, never a proof of who supplied or produced an item. */
public final class WorkLedger {
  public static final class Entry {
    public long at;
    public String dimension = "", kind = "", text = "";
  }

  public static void add(String kind, String text) {
    var mc = net.minecraft.client.Minecraft.getInstance();
    if (mc.level == null) return;
    if (Store.data.workLedger == null) Store.data.workLedger = new ArrayList<>();
    Entry e = new Entry();
    e.at = System.currentTimeMillis();
    e.dimension = mc.level.dimension().location().toString();
    e.kind = kind;
    e.text = Rules.clean(text, 1500);
    Store.data.workLedger.add(e);
    while (Store.data.workLedger.size() > 120) Store.data.workLedger.remove(0);
    Store.save();
  }

  public static void inventoryChange(Map<String, Integer> before, Map<String, Integer> after) {
    var changes = InventoryTolerance.delta(before, after);
    if (changes.isEmpty()) return;
    StringBuilder text = new StringBuilder("等待期间背包变化（来源未确认，不计作生产成果）：");
    changes.forEach(
        (fp, n) ->
            text.append(fp.split("#", 2)[0]).append(n > 0 ? " +" : " ").append(n).append("；"));
    add("inventory_observed", text.toString());
  }

  public static String current() {
    if (Agent.session != null) return Agent.session.human();
    TaskCard c = RestockTask.latest;
    if (c == null)
      return "当前状态：" + Agent.state + "\n当前任务：" + (Agent.task.isBlank() ? "无" : Agent.task);
    return "任务："
        + c.title
        + "\n状态："
        + c.outcomeLabel()
        + "\n步骤："
        + c.phase
        + "\n进度："
        + c.progress
        + "\n下一步："
        + c.next
        + "\n说明："
        + ActionSummary.describe(c.problem)
        + (c.outcome.equals("blocked") ? "\n建议：" + FailureAdvice.forResult(c.problem).next() : "");
  }
}
