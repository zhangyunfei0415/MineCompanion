package dev.mibao.companion;

import com.google.gson.*;

/**
 * Pure Chinese receipt and client-side diagnostics for one FTB task submit. No game runtime
 * required, so the wording and JSON fields stay testable offline.
 */
final class QuestTaskReceipt {
  private QuestTaskReceipt() {}

  static String receipt(JsonObject snapshot, int ticks) {
    return "已发送FTB任务提交：任务#"
        + value(snapshot, "task_id")
        + "《"
        + text(snapshot, "task_title")
        + "》/章节#"
        + value(snapshot, "quest_id")
        + "《"
        + text(snapshot, "quest_title")
        + "》；提交前[有效="
        + value(snapshot, "task_valid")
        + ", 进度="
        + value(snapshot, "progress_before")
        + "/"
        + value(snapshot, "max_progress")
        + ", 已完成="
        + value(snapshot, "completed_before")
        + ", 章节已完成="
        + value(snapshot, "quest_completed_before")
        + ", 可开始="
        + value(snapshot, "can_start")
        + ", 依赖完成="
        + value(snapshot, "deps_complete")
        + ", 锁定="
        + value(snapshot, "locked")
        + ", 可编辑="
        + value(snapshot, "editable")
        + ", 自提交间隔="
        + value(snapshot, "auto_submit")
        + "]；已消耗本次quest_view，约"
        + ticks
        + "tick后客户端复核（见quest_book_state的last_submit）；依赖未完成时FTB只记进度不标记完成，回执不等于服务端完成。";
  }

  static JsonObject lastSubmit(
      JsonObject snapshot,
      Long progressAfter,
      Boolean completedAfter,
      Boolean depsAfter,
      String note) {
    var o = new JsonObject();
    number(o, "task_id", numbers(snapshot, "task_id"));
    number(o, "quest_id", numbers(snapshot, "quest_id"));
    number(o, "progress_before", numbers(snapshot, "progress_before"));
    flag(o, "completed_before", bools(snapshot, "completed_before"));
    number(o, "progress_after", progressAfter);
    flag(o, "completed_after", completedAfter);
    flag(o, "deps_complete", depsAfter != null ? depsAfter : bools(snapshot, "deps_complete"));
    o.addProperty("confirmed", confirmed(snapshot, progressAfter, completedAfter));
    o.addProperty("note", note == null ? "" : note);
    return o;
  }

  /** True only when the delayed client read saw a real progress or completion change. */
  static boolean confirmed(JsonObject snapshot, Long progressAfter, Boolean completedAfter) {
    Long before = numbers(snapshot, "progress_before");
    if (progressAfter != null && (before == null || progressAfter > before)) return true;
    return Boolean.TRUE.equals(completedAfter)
        && !Boolean.TRUE.equals(bools(snapshot, "completed_before"));
  }

  static String verificationNote(
      JsonObject snapshot, Long progressAfter, Boolean completedAfter, Boolean depsAfter) {
    Boolean deps = depsAfter != null ? depsAfter : bools(snapshot, "deps_complete");
    if (Boolean.TRUE.equals(completedAfter)) return "已确认任务完成（客户端任务状态已同步）";
    if (confirmed(snapshot, progressAfter, completedAfter))
      return Boolean.FALSE.equals(deps)
          ? "进度已增长，但章节依赖未完成，FTB不会把该任务标记完成"
          : "进度已增长，任务尚未完成，可能还需继续提交或等待同步";
    if (progressAfter == null && completedAfter == null)
      return "复核未取得客户端任务状态，请重新quest_book_state并查看任务详情";
    if (Boolean.FALSE.equals(deps)) return "未观察到变化；依赖未完成时FTB只记录进度，不会标记完成";
    return "未观察到进度或完成变化，可能客户端尚未同步或被服务端拒绝（锁定/未开始/提交包未受理）";
  }

  private static void number(JsonObject o, String key, Long value) {
    if (value == null) o.add(key, JsonNull.INSTANCE);
    else o.addProperty(key, value);
  }

  private static void flag(JsonObject o, String key, Boolean value) {
    if (value == null) o.add(key, JsonNull.INSTANCE);
    else o.addProperty(key, value);
  }

  private static String value(JsonObject o, String key) {
    JsonElement e = o.get(key);
    return e == null || e.isJsonNull() ? "未知" : e.getAsString();
  }

  private static String text(JsonObject o, String key) {
    JsonElement e = o.get(key);
    return e == null || e.isJsonNull() ? "" : Rules.clean(e.getAsString(), 200);
  }

  private static Long numbers(JsonObject o, String key) {
    JsonElement e = o.get(key);
    if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
    try {
      return e.getAsLong();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static Boolean bools(JsonObject o, String key) {
    JsonElement e = o.get(key);
    if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
    try {
      return e.getAsBoolean();
    } catch (RuntimeException ignored) {
      return null;
    }
  }
}
