package dev.mibao.companion;

import java.util.*;

/** All persistent/UI changes occur on the client thread. Network thread only fills the mailbox. */
public final class StreamProgress {
  public static final class Run {
    final long ticket;
    final String scope;
    final StreamMailbox mailbox = new StreamMailbox();
    final TaskTimeline.Entry entry;
    boolean ended;
    long endedAt;

    Run(long ticket) {
      this.ticket = ticket;
      scope = Store.scope();
      entry = TaskTimeline.add("stream", "等待首段回复", "连接中 · 公开工作说明");
    }
  }

  private static Run current;

  public static Run begin(long ticket) {
    cancel("新请求开始");
    return current = new Run(ticket);
  }

  public static void publish(Run run, ProviderStream.Preview value) {
    if (run != null) run.mailbox.publish(value);
  }

  private static void apply(Run run) {
    var p = run.mailbox.drain();
    if (p != null) {
      run.entry.updated = System.currentTimeMillis();
      run.entry.title =
          TaskTimeline.clean(
              p.text().isBlank()
                  ? p.stage()
                  : p.text().substring(Math.max(0, p.text().length() - 1400)),
              Store.config.apiKey);
      run.entry.detail = TaskTimeline.clean(p.stage() + "；文字不是完成证明", Store.config.apiKey);
    }
  }

  public static void tick() {
    if (current == null || current.ended) return;
    if (current.ticket != Agent.epoch) {
      cancel("已中断 · 控制或任务已改变");
      return;
    }
    if (!current.scope.equals(Store.scope())) {
      current = null;
      return;
    }
    apply(current);
  }

  public static void finish(Run run, String content, boolean success) {
    if (run == null || run.ended || current != run) return;
    if (run.ticket != Agent.epoch || !run.scope.equals(Store.scope())) {
      cancel("已中断 · 任务已改变");
      return;
    }
    apply(run);
    run.ended = true;
    run.endedAt = System.currentTimeMillis();
    run.entry.updated = run.endedAt;
    run.entry.kind = success ? "note" : "stream_error";
    run.entry.title =
        TaskTimeline.clean(
            content == null || content.isBlank()
                ? (success ? "工具参数已完整接收 · 等待执行与结果核对" : run.entry.title)
                : content,
            Store.config.apiKey);
    run.entry.detail =
        success
            ? (run.entry.detail.contains("未使用流式")
                ? "整段回复 · 接口未使用流式；工作说明不是执行证明"
                : "生成完成 · 工作说明不是执行证明")
            : "流式中断 · 本轮工具未提交；已有文字仅供参考";
    if (success && content != null && !content.isBlank())
      LocalDialogue.add("工作说明", run.entry.title);
  }

  public static void cancel(String reason) {
    if (current != null && !current.ended) {
      if (current.scope.equals(Store.scope())) {
        apply(current);
        current.entry.kind = "stream_error";
        current.entry.detail = TaskTimeline.clean(reason + "；本轮工具未提交");
      }
      current.ended = true;
      current.endedAt = System.currentTimeMillis();
    }
  }

  public static String latest() {
    if (current != null && !current.ended && current.scope.equals(Store.scope()))
      return tail(current.entry.title);
    var events = Store.data.taskEvents;
    for (int i = events.size() - 1; i >= 0; i--) {
      var e = events.get(i);
      if ((e.kind.equals("note") || e.kind.equals("plan"))
          && System.currentTimeMillis() - Math.max(e.at, e.updated) < 15_000) return tail(e.title);
    }
    return "";
  }

  public static boolean active(String id) {
    return current != null
        && !current.ended
        && current.ticket == Agent.epoch
        && current.scope.equals(Store.scope())
        && current.entry.id.equals(id);
  }

  private static String tail(String s) {
    return s.length() > 110 ? "…" + s.substring(s.length() - 110) : s;
  }

  private StreamProgress() {}
}
