package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

public final class TaskTimeline {
  public static final class Entry {
    public String id = UUID.randomUUID().toString(), task = "", kind = "", title = "", detail = "";
    public long at = System.currentTimeMillis(), epoch, updated;
  }

  private static String run = "";
  private static long epoch = -1;

  public static void begin(String goal) {
    run = UUID.randomUUID().toString();
    epoch = Agent.epoch;
    add("command", goal, "");
  }

  public static String clean(String text) {
    String s = Rules.clean(text, 1400);
    return s.matches("(?is).*(authorization|bearer |api[_ -]?key|sk-[a-z0-9]).*")
        ? "内容已隐藏：可能包含凭据"
        : s;
  }

  public static String clean(String text, String key) {
    return clean(
        key == null || key.isBlank() ? text : (text == null ? "" : text).replace(key, "[隐藏密钥]"));
  }

  public static Entry add(String kind, String title, String detail) {
    if (Store.data.taskEvents == null) Store.data.taskEvents = new ArrayList<>();
    if (epoch != Agent.epoch || run.isBlank()) {
      epoch = Agent.epoch;
      run = UUID.randomUUID().toString();
    }
    var e = new Entry();
    e.task = run;
    e.epoch = epoch;
    e.kind = kind;
    e.title = clean(title);
    e.detail = clean(detail);
    if (Store.config.apiKey != null && !Store.config.apiKey.isBlank()) {
      e.title = e.title.replace(Store.config.apiKey, "[隐藏密钥]");
      e.detail = e.detail.replace(Store.config.apiKey, "[隐藏密钥]");
    }
    Store.data.taskEvents.add(e);
    if (kind.equals("plan") || kind.equals("note"))
      LocalDialogue.add(kind.equals("plan") ? "计划" : "工作说明", e.title);
    while (Store.data.taskEvents.size() > 400) Store.data.taskEvents.remove(0);
    return e;
  }

  public static JsonObject view(int offset, int limit) {
    var o = new JsonObject();
    var list = new ArrayList<>(Store.data.taskEvents);
    Collections.reverse(list);
    o.addProperty("total", list.size());
    o.add(
        "events",
        Store.GSON.toJsonTree(
            list.stream()
                .skip(Math.max(0, offset))
                .limit(Math.min(40, Math.max(1, limit)))
                .toList()));
    return o;
  }

  public static String label(String kind) {
    return switch (kind) {
      case "command" -> "任务";
      case "plan" -> "计划";
      case "note" -> "工作说明";
      case "stream" -> "生成片段";
      case "stream_error" -> "生成中断";
      case "action" -> "操作开始";
      case "result" -> "操作回执";
      case "human" -> "人工辅助";
      case "end" -> "会话结束";
      case "command_rejected" -> "接单诊断";
      default -> "状态";
    };
  }

  private TaskTimeline() {}
}
