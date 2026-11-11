package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/** Deterministic outgoing context reduction, preserving every assistant/tool-call pairing. */
public final class ModelContext {
  public static JsonArray build(List<JsonObject> history, int maxChars) {
    var keep = new ArrayList<JsonObject>();
    for (var m : history) {
      if (str(m, "role").equals("user") && str(m, "content").startsWith("当前真实游戏状态：")) continue;
      keep.add(m.deepCopy());
    }
    // Old tool output is an excerpt, never a fabricated summary. Latest four remain complete.
    int tools = 0;
    for (int i = keep.size() - 1; i >= 0; i--) {
      var m = keep.get(i);
      if (!str(m, "role").equals("tool")) continue;
      String value = str(m, "content");
      if (++tools > 4 && value.length() > 1600)
        m.addProperty(
            "content",
            value.substring(0, 900)
                + "\n[历史回执已节选；不能据此核实当前库存。task_timeline查记录或重新观察。]\n"
                + value.substring(value.length() - 500));
    }
    int removed = 0;
    while (size(keep) > maxChars) {
      int start = -1, end = -1;
      for (int i = 0; i < keep.size(); i++) {
        if (!str(keep.get(i), "role").equals("assistant")) continue;
        int next = i + 1;
        while (next < keep.size() && str(keep.get(next), "role").equals("tool")) next++;
        // The last assistant and its calls remain, even when the requested budget is tiny.
        if (next >= keep.size() || !completeGroup(keep, i, next)) continue;
        start = i;
        end = next;
        break;
      }
      if (start < 0) break;
      removed += end - start;
      keep.subList(start, end).clear();
    }
    var out = new JsonArray();
    if (removed > 0) {
      var note = new JsonObject();
      note.addProperty("role", "system");
      note.addProperty(
          "content", "较早的" + removed + "条工具对话已从本次请求移出；未决任务仍查询原ID，不能因缺少历史而重放。长期技能/账本与当前观察独立保存。");
      out.add(note);
    }
    keep.forEach(out::add);
    return out;
  }

  private static boolean completeGroup(List<JsonObject> messages, int start, int end) {
    var expected = new HashSet<String>();
    var actual = new HashSet<String>();
    var assistant = messages.get(start);
    if (assistant.has("tool_calls"))
      for (var call : assistant.getAsJsonArray("tool_calls"))
        expected.add(str(call.getAsJsonObject(), "id"));
    for (int i = start + 1; i < end; i++) actual.add(str(messages.get(i), "tool_call_id"));
    return expected.equals(actual);
  }

  private static int size(List<JsonObject> values) {
    return values.stream().mapToInt(v -> v.toString().length()).sum();
  }

  private static String str(JsonObject o, String k) {
    return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
  }

  private ModelContext() {}
}
