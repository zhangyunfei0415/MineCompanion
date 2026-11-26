package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/**
 * Pure-Java persistent multi-step goal & checkpoint board stored per world scope in Store.Data.
 * Designed primarily for built-in API-key mode across reconnects/compactions, while remaining
 * strictly optional for external Harness agents. Zero {@code net.minecraft.*} imports.
 */
public final class GoalBoard {
  public static final int MAX_STEPS = 24;
  public static final int MAX_CHECKPOINTS = 16;
  public static final Set<String> VALID_STATUSES =
      Set.of("pending", "in_progress", "done", "blocked", "skipped");

  public static final class Step {
    public String id = "";
    public String title = "";
    public String status = "pending";
    public String note = "";

    public Step() {}

    public Step(String id, String title, String status, String note) {
      this.id = id == null ? "" : id;
      this.title = title == null ? "" : title;
      this.status = VALID_STATUSES.contains(status) ? status : "pending";
      this.note = note == null ? "" : note;
    }
  }

  public static final class State {
    public String goal = "";
    public long updatedAt;
    public List<Step> steps = new ArrayList<>();
    public Map<String, String> checkpoints = new LinkedHashMap<>();

    public void normalize() {
      goal = Rules.clean(goal, 220);
      if (steps == null) steps = new ArrayList<>();
      steps.removeIf(s -> s == null || s.title == null || s.title.isBlank());
      int seq = 1;
      for (Step s : steps) {
        if (s.id == null || s.id.isBlank()) s.id = "s" + seq;
        s.id = Rules.clean(s.id, 32);
        s.title = Rules.clean(s.title, 140);
        if (s.status == null || !VALID_STATUSES.contains(s.status)) s.status = "pending";
        s.note = Rules.clean(s.note, 180);
        seq++;
      }
      if (steps.size() > MAX_STEPS) steps = new ArrayList<>(steps.subList(0, MAX_STEPS));
      if (checkpoints == null) checkpoints = new LinkedHashMap<>();
      while (checkpoints.size() > MAX_CHECKPOINTS) {
        checkpoints.remove(checkpoints.keySet().iterator().next());
      }
    }

    public boolean isEmpty() {
      normalize();
      return goal.isBlank() && steps.isEmpty() && checkpoints.isEmpty();
    }
  }

  private GoalBoard() {}

  public static String apply(State state, String action, JsonObject args, long nowMs) {
    if (state == null) throw new IllegalArgumentException("[INVALID_ARGUMENT] GoalBoard状态为空");
    state.normalize();
    String act = action == null || action.isBlank() ? "legacy" : action.strip().toLowerCase(Locale.ROOT);
    switch (act) {
      case "get" -> {
        return toSnapshot(state).toString();
      }
      case "clear" -> {
        state.goal = "";
        state.steps.clear();
        state.checkpoints.clear();
        state.updatedAt = nowMs;
        return "已清空本世界持久目标板。";
      }
      case "set", "legacy" -> {
        String g = str(args, "goal", "");
        if (!g.isBlank()) state.goal = Rules.clean(g, 220);
        if (args != null && args.has("steps") && args.get("steps").isJsonArray()) {
          state.steps.clear();
          appendSteps(state, args.getAsJsonArray("steps"));
        } else if (act.equals("legacy") && state.steps.isEmpty()) {
          String cur = str(args, "current", "");
          String nxt = str(args, "next", "");
          if (!cur.isBlank()) state.steps.add(new Step("s1", cur, "in_progress", ""));
          if (!nxt.isBlank()) state.steps.add(new Step("s2", nxt, "pending", ""));
        }
        String cpKey = str(args, "checkpoint_key", "");
        String cpVal = str(args, "checkpoint_value", "");
        if (!cpKey.isBlank()) putCheckpoint(state, cpKey, cpVal);
        state.updatedAt = nowMs;
        state.normalize();
        return "已保存长程目标板（" + state.steps.size() + " 步）：" + summarizeLine(state);
      }
      case "add_steps" -> {
        if (args == null || !args.has("steps") || !args.get("steps").isJsonArray())
          throw new IllegalArgumentException("[INVALID_ARGUMENT] add_steps 需要 steps 数组");
        appendSteps(state, args.getAsJsonArray("steps"));
        state.updatedAt = nowMs;
        state.normalize();
        return "已追加步骤，当前共 " + state.steps.size() + " 步。";
      }
      case "update" -> {
        String stepId = str(args, "step_id", "");
        int stepIndex = args != null && args.has("step_index") ? args.get("step_index").getAsInt() : -1;
        String status = str(args, "status", "").strip().toLowerCase(Locale.ROOT);
        String note = args != null && args.has("note") ? str(args, "note", "") : null;
        if (!status.isBlank() && !VALID_STATUSES.contains(status))
          throw new IllegalArgumentException(
              "[INVALID_ARGUMENT] status须为 pending/in_progress/done/blocked/skipped");
        Step target = findStep(state, stepId, stepIndex);
        if (target == null)
          throw new IllegalArgumentException("[NOT_FOUND] 未找到指定步骤：" + (stepId.isBlank() ? stepIndex : stepId));
        if (!status.isBlank()) target.status = status;
        if (note != null) target.note = Rules.clean(note, 180);
        state.updatedAt = nowMs;
        state.normalize();
        return "步骤已更新 [" + target.id + " " + target.status + "]：" + target.title;
      }
      case "checkpoint" -> {
        String key = str(args, "checkpoint_key", str(args, "key", ""));
        String value = str(args, "checkpoint_value", str(args, "note", ""));
        if (key.isBlank())
          throw new IllegalArgumentException("[INVALID_ARGUMENT] checkpoint 需要 checkpoint_key");
        putCheckpoint(state, key, value);
        state.updatedAt = nowMs;
        state.normalize();
        return "已记录检查点 [" + key + "] = " + value;
      }
      default -> throw new IllegalArgumentException(
          "[INVALID_ARGUMENT] action须为 set/add_steps/update/checkpoint/get/clear");
    }
  }

  private static void appendSteps(State state, JsonArray arr) {
    int seq = state.steps.size() + 1;
    for (JsonElement el : arr) {
      if (state.steps.size() >= MAX_STEPS) break;
      if (el.isJsonPrimitive()) {
        String t = Rules.clean(el.getAsString(), 140);
        if (!t.isBlank()) state.steps.add(new Step("s" + seq++, t, "pending", ""));
      } else if (el.isJsonObject()) {
        JsonObject o = el.getAsJsonObject();
        String id = str(o, "id", "s" + seq);
        String title = str(o, "title", "");
        String st = str(o, "status", "pending");
        String note = str(o, "note", "");
        if (!title.isBlank()) {
          state.steps.add(new Step(id, title, st, note));
          seq++;
        }
      }
    }
  }

  private static Step findStep(State state, String id, int oneBasedIndex) {
    if (id != null && !id.isBlank()) {
      for (Step s : state.steps) {
        if (s.id.equalsIgnoreCase(id.strip())) return s;
      }
    }
    if (oneBasedIndex >= 1 && oneBasedIndex <= state.steps.size()) {
      return state.steps.get(oneBasedIndex - 1);
    }
    return null;
  }

  private static void putCheckpoint(State state, String key, String value) {
    String cleanKey = Rules.clean(key, 64);
    String cleanVal = Rules.clean(value, 180);
    if (cleanVal.isBlank()) state.checkpoints.remove(cleanKey);
    else state.checkpoints.put(cleanKey, cleanVal);
  }

  public static JsonObject toSnapshot(State state) {
    var out = new JsonObject();
    if (state == null || state.isEmpty()) return out;
    int done = 0;
    String currentTitle = "";
    String nextTitle = "";
    var arr = new JsonArray();
    for (Step s : state.steps) {
      if (s.status.equals("done") || s.status.equals("skipped")) done++;
      if (currentTitle.isBlank() && s.status.equals("in_progress")) currentTitle = s.title;
      else if (nextTitle.isBlank() && s.status.equals("pending")) nextTitle = s.title;
      var so = new JsonObject();
      so.addProperty("id", s.id);
      so.addProperty("title", s.title);
      so.addProperty("status", s.status);
      if (!s.note.isBlank()) so.addProperty("note", s.note);
      arr.add(so);
    }
    if (currentTitle.isBlank() && !nextTitle.isBlank()) {
      currentTitle = nextTitle;
      nextTitle = "";
    }
    out.addProperty("persistent", true);
    out.addProperty("goal", state.goal);
    out.addProperty("current", currentTitle);
    out.addProperty("next", nextTitle);
    out.addProperty("steps_done", done);
    out.addProperty("steps_total", state.steps.size());
    out.addProperty("updated", state.updatedAt);
    out.add("steps", arr);
    if (!state.checkpoints.isEmpty()) {
      var cps = new JsonObject();
      for (var e : state.checkpoints.entrySet()) cps.addProperty(e.getKey(), e.getValue());
      out.add("checkpoints", cps);
    }
    out.addProperty("rule", "持久目标板（API模式长程断点记忆；计划不等同于执行证据）");
    return out;
  }

  private static String summarizeLine(State state) {
    int done = 0;
    for (Step s : state.steps) if (s.status.equals("done")) done++;
    return state.goal + " [" + done + "/" + state.steps.size() + "]";
  }

  private static String str(JsonObject o, String key, String def) {
    if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
    try {
      return o.get(key).getAsString();
    } catch (RuntimeException e) {
      return def;
    }
  }
}
