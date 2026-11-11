package dev.mibao.companion;

import java.util.*;

/** Pure queue/receipt bookkeeping; text supplied by a planner never upgrades a result. */
public final class LogisticsHistory {
  private static final Set<String> LIVE = Set.of("WAITING", "WAITING_MATERIALS", "ACTIVE");

  public static void trim(
      Map<String, LogisticsRecord> records, List<CommunityBoard.Task> tasks, int limit) {
    Set<String> retained = new HashSet<>();
    for (var task : tasks) if (LIVE.contains(task.state)) retained.addAll(task.orders);
    var it = records.entrySet().iterator();
    while (records.size() > limit && it.hasNext()) {
      var entry = it.next();
      if (!entry.getValue().state.equals("RUNNING") && !retained.contains(entry.getKey()))
        it.remove();
    }
  }

  public static String outcome(List<String> ids, Map<String, LogisticsRecord> records) {
    if (ids.isEmpty()) return "ENDED";
    boolean complete = true, delivered = false, blocked = true;
    for (String id : ids) {
      var r = records.get(id);
      if (r == null) {
        complete = false;
        blocked = false;
        continue;
      }
      complete &= r.state.equals("COMPLETE") && !r.uncertain;
      delivered |= r.delivered > 0 || r.produced > 0 || r.fetched > 0;
      blocked &= Set.of("COMPLETE", "BLOCKED").contains(r.state) && !r.uncertain;
    }
    return complete ? "DONE" : delivered ? "PARTIAL" : blocked ? "BLOCKED" : "NEEDS_CHECK";
  }

  private LogisticsHistory() {}
}
