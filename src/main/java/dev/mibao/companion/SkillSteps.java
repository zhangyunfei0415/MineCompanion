package dev.mibao.companion;

import com.google.gson.*;
import java.util.Set;

/** Reusable procedure, with locations rebound against the current world's named places. */
public final class SkillSteps {
  private SkillSteps() {}

  public static JsonArray prepare(
      JsonArray steps, boolean sameScope, Set<String> places, JsonObject bindings) {
    if (steps == null || steps.isEmpty() || steps.size() > 32)
      throw new IllegalArgumentException("技能须含 1–32 步，请先修正技能");
    JsonArray result = steps.deepCopy();
    for (var el : result) {
      var step = el.getAsJsonObject();
      String tool = step.get("tool").getAsString();
      if (!Rules.TOOLS.contains(tool)
          || Set.of(
                  "work_session",
                  "farm_cycle",
                  "run_skill",
                  "run_preset",
                  "save_skill",
                  "finish",
                  "read_web",
                  "search_web")
              .contains(tool)) throw new IllegalArgumentException("技能含不允许的嵌套/控制工具：" + tool);
      if (Set.of("click_slot", "gui_slot", "gui_button", "gui_text", "gui_scroll", "wait_menu")
          .contains(tool)) throw new IllegalArgumentException("技能含旧槽位观察编号，须重新观察并生成当前点击；不能重放旧菜单点击");
      JsonObject args = step.has("args") ? step.getAsJsonObject("args") : new JsonObject();
      step.add("args", args);
      if (Set.of("restock", "transport", "craft_item", "mine_resource", "mine_blocks", "chop_wood")
          .contains(tool))
        for (String field : new String[] {"destination", "source", "delivery"}) {
          if (!args.has(field) || args.get(field).getAsString().isBlank()) continue;
          String original = args.get(field).getAsString();
          boolean explicit = bindings.has(original);
          String local = explicit ? bindings.get(original).getAsString() : original;
          if (places.contains(local)) args.addProperty(field, local);
          else if (!(sameScope && !explicit && original.contains("@")))
            throw new IllegalArgumentException(
                "搬运/补给技能的 " + field + " 未绑定到当前世界地点：" + original + "；请用 bindings 重绑");
        }
      String location =
          args.has("place")
              ? args.get("place").getAsString()
              : args.has("object")
                  ? args.get("object").getAsString()
                  : args.has("x")
                      ? args.get("x") + "," + args.get("y") + "," + args.get("z")
                      : null;
      if (location == null) continue;
      boolean explicit = bindings.has(location);
      if (explicit || args.has("place")) {
        String place = explicit ? bindings.get(location).getAsString() : location;
        if (!places.contains(place))
          throw new IllegalArgumentException("当前世界没有技能所需地点：" + place + "；请先标记，或用 bindings 映射到当前地点");
        args.remove("object");
        args.remove("x");
        args.remove("y");
        args.remove("z");
        args.addProperty("place", place);
      } else if (!sameScope)
        throw new IllegalArgumentException(
            "技能含其他世界的坐标/对象：" + location + "；请用 bindings 绑定到当前世界的命名地点");
    }
    return result;
  }
}
