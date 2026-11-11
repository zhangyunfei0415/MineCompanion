package dev.mibao.companion;

import java.util.*;

/** Bounded user-facing notices. Model reasoning and tool traces stay in the debug log. */
public final class StatusFeed {
  public enum Kind {
    REST("休息中"),
    IDLE("待命中"),
    THINKING("思考中"),
    WALKING("赶路中"),
    INTERFACE("界面操作"),
    EATING("进食中"),
    MINING("采集中"),
    COMBAT("战斗中"),
    STORAGE("搬运中"),
    SENSING("观察中"),
    WORKING("工作中"),
    WAITING("等待审批"),
    HELP("需要帮助");
    public final String title;

    Kind(String title) {
      this.title = title;
    }
  }

  public static final class Notice {
    public String text, level;
    public long at;
    public int repeats = 1;

    Notice(String text, String level, long at) {
      this.text = text;
      this.level = level;
      this.at = at;
    }
  }

  public static final class View {
    public String name = "小勤", player = "", task = "", detail = "", theme = "深海", scope = "";
    public Kind kind = Kind.REST;
    public long updated, requests, inputTokens, outputTokens;
    public float health;
    public int food;
    public double scanMillis;
    public long scanAge;
    public List<Notice> notices = new ArrayList<>();
    public Notice attention;
    public TaskCard taskCard;
    public com.google.gson.JsonObject plan;
  }

  private final Deque<Notice> notices = new ArrayDeque<>();
  private Notice attention;

  public void add(String text, String level, long now) {
    String clean = Rules.clean(text, 1200);
    if (clean.isBlank()) return;
    Notice last = notices.peekLast();
    if (last != null
        && last.text.equals(clean)
        && last.level.equals(level)
        && now - last.at < 8000) {
      last.repeats++;
      last.at = now;
      if (!level.equals("info")) attention = last;
      return;
    }
    Notice notice = new Notice(clean, level, now);
    notices.addLast(notice);
    if (!level.equals("info")) attention = notice;
    while (notices.size() > 40) notices.removeFirst();
  }

  public List<Notice> snapshot() {
    return new ArrayList<>(notices);
  }

  public Notice attention() {
    if (attention == null) return null;
    var copy = new Notice(attention.text, attention.level, attention.at);
    copy.repeats = attention.repeats;
    return copy;
  }

  public void acknowledge() {
    attention = null;
  }

  public void clear() {
    notices.clear();
    attention = null;
  }

  public static int opacity(long age, String level) {
    long hold = "info".equals(level) ? 20_000 : 50_000,
        fade = "info".equals(level) ? 10_000 : 15_000;
    if (age <= hold) return 255;
    if (age >= hold + fade) return 0;
    return (int) (255 * (hold + fade - age) / fade);
  }

  /**
   * Whether the pinned attention notice should stay on screen. Errors and requests ("alert"/"ask")
   * do not fade on their own: they stay until the player acknowledges them (or starts a new task).
   * Plain info notices still fade normally.
   */
  public static boolean keepAttention(Notice attention, boolean approvalPending, long now) {
    if (attention == null || attention.text == null) return false;
    if (approvalPending) return true;
    if (!"info".equals(attention.level)) return true;
    return opacity(now - attention.at, attention.level) > 8;
  }

  /**
   * One-line summary for the top of the sidebar: who controls the game and which planner is
   * actually connected.
   */
  public static String controlLine(
      boolean enabled, boolean paused, boolean cooperative, String driver, boolean harnessLinked) {
    String mode = !enabled ? "人工控制" : paused ? "AI 已暂停" : cooperative ? "协同" : "AI 接管";
    String link =
        "HARNESS".equals(driver) ? (harnessLinked ? "Harness 已连接" : "Harness 未连接") : "API 模型";
    return mode + " · " + link;
  }

  /**
   * Detail line while AI is not in control; the last stop reason is labelled as history, not live
   * state.
   */
  public static String restDetail(String lastState) {
    String last = lastState == null ? "" : lastState.trim();
    if (last.isBlank() || last.equals("休息")) return "AI 未接管 · 等待开启";
    return "AI 未接管 · 上次结束：" + last;
  }

  public static String level(String text) {
    return text.contains("失败")
            || text.contains("错误")
            || (text.contains("死亡") && !text.startsWith("成功"))
            || text.contains("过低")
            || text.startsWith("部分完成")
            || text.startsWith("未转移")
        ? "alert"
        : text.contains("审批") || text.contains("求助") || text.contains("协助撤离") ? "ask" : "info";
  }

  public static Kind classify(
      boolean enabled, boolean approval, boolean thinking, boolean action, String detail) {
    if (!enabled)
      return detail.contains("死亡") || detail.contains("过低") || detail.contains("协助撤离")
          ? Kind.HELP
          : Kind.REST;
    if (approval) return Kind.WAITING;
    if (action) {
      if (detail.startsWith("进食")) return Kind.EATING;
      if (detail.startsWith("GUI")
          || detail.startsWith("背包")
          || detail.startsWith("打开背包")
          || detail.contains("准备 ·")) return Kind.INTERFACE;
      if (detail.startsWith("观察")) return Kind.SENSING;
      if (detail.startsWith("采集") || detail.startsWith("采矿")) return Kind.MINING;
      if (detail.startsWith("清怪") || detail.startsWith("自卫")) return Kind.COMBAT;
      if (detail.startsWith("前往")) return Kind.WALKING;
      if (detail.startsWith("存入") || detail.startsWith("取出")) return Kind.STORAGE;
      return Kind.WORKING;
    }
    return thinking ? Kind.THINKING : Kind.IDLE;
  }
}
