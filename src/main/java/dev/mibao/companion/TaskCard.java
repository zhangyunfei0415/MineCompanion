package dev.mibao.companion;

/** Display-only task receipt; contains no credentials, full inventories or executable commands. */
public final class TaskCard {
  public String title = "",
      item = "",
      phase = "",
      next = "",
      problem = "",
      outcome = "running",
      kind = "storage",
      progress = "";
  public int target, current = -1, delivered, carrying, trips;
  public boolean uncertain;
  public long started = System.currentTimeMillis(), ended;

  public String outcomeLabel() {
    return switch (outcome) {
      case "complete" -> "已完成";
      case "blocked" -> "受阻，等待处理";
      case "cancelled" -> "已停止";
      default -> "执行中";
    };
  }

  public int percent() {
    return outcome.equals("complete")
        ? 100
        : current < 0 || target <= 0
            ? 0
            : Rules.bounded((int) Math.min(100, (long) current * 100 / target), 0, 100);
  }

  public TaskCard copy() {
    TaskCard c = new TaskCard();
    c.title = title;
    c.item = item;
    c.phase = phase;
    c.next = next;
    c.problem = problem;
    c.outcome = outcome;
    c.kind = kind;
    c.progress = progress;
    c.target = target;
    c.current = current;
    c.delivered = delivered;
    c.carrying = carrying;
    c.trips = trips;
    c.uncertain = uncertain;
    c.started = started;
    c.ended = ended;
    return c;
  }
}
