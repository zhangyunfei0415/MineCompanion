package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/** World-local bounded procedures; every child is still dispatched through Agent.execute. */
public final class SessionPlan {
  public static final Set<String> TOOLS =
      Set.of(
          "fish",
          "illuminate_area",
          "light_survey",
          "find_blocks",
          "swim_out",
          "command_skill_run",
          "command_status",
          "observe_compact",
          "unstick",
          "break_block",
          "attack_block",
          "place_block",
          "write_sign",
          "inspect_client",
          "inspect_scene",
          "gui_select",
          "stow_cursor",
          "use_block",
          "interact_block",
          "inventory_memory",
          "organize_inventory",
          "use_item_on_block",
          "plant_crops",
          "fertilize_crops",
          "deposit",
          "fetch",
          "go_to",
          "scan_world",
          "query_world",
          "inspect_block",
          "open_container",
          "close_screen",
          "transport",
          "restock",
          "mine_resource",
          "chop_wood",
          "harvest_replant",
          "collect_items",
          "craft_item",
          "smelt_item",
          "prepare_workshop",
          "eat_food");

  public record Step(String tool, JsonObject args, Map<String, Integer> requires, int waitSeconds) {
    public Step {
      args = args.deepCopy();
      requires = Map.copyOf(requires);
    }

    @Override
    public JsonObject args() {
      return args.deepCopy();
    }
  }

  public final List<Step> steps;
  public final int seconds;
  public final String name;

  public SessionPlan(JsonObject input) {
    name = Rules.clean(AgentFree.string(input, "name", "连续后勤"), 64);
    seconds = number(input, "seconds", 600, 1, 1800);
    int wait = number(input, "wait_seconds", 30, 0, 120);
    if (!input.has("steps") || !input.get("steps").isJsonArray())
      throw new IllegalArgumentException("流程需要steps数组");
    var array = input.getAsJsonArray("steps");
    if (array.isEmpty() || array.size() > 16) throw new IllegalArgumentException("流程须含1..16步");
    var result = new ArrayList<Step>();
    for (var element : array) {
      var s = element.getAsJsonObject();
      String tool = AgentFree.string(s, "tool", "");
      if (!TOOLS.contains(tool)) throw new IllegalArgumentException("流程不允许嵌套/输入/未知工具：" + tool);
      JsonObject args = s.has("args") ? s.getAsJsonObject("args") : new JsonObject();
      if (args.toString().length() > 4000) throw new IllegalArgumentException("流程单步参数过长");
      var requires = new LinkedHashMap<String, Integer>();
      if (s.has("requires"))
        for (var e : s.getAsJsonObject("requires").entrySet()) {
          if (!e.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || requires.size() >= 16)
            throw new IllegalArgumentException("前置物资须为普通物品注册ID，最多16种");
          int amount = integer(e.getValue());
          if (amount < 1 || amount > 256) throw new IllegalArgumentException("前置物资数量须为1..256");
          requires.put(e.getKey(), amount);
        }
      if (tool.equals("harvest_replant")) {
        String seed =
            switch (AgentFree.string(args, "crop", "minecraft:wheat")) {
              case "minecraft:wheat" -> "minecraft:wheat_seeds";
              case "minecraft:carrots" -> "minecraft:carrot";
              case "minecraft:potatoes" -> "minecraft:potato";
              case "minecraft:beetroots" -> "minecraft:beetroot_seeds";
              default -> "";
            };
        if (!seed.isEmpty()) requires.merge(seed, 1, Math::max);
      }
      result.add(new Step(tool, args, requires, number(s, "wait_seconds", wait, 0, 120)));
    }
    steps = List.copyOf(result);
  }

  public static JsonObject stepSchema() {
    var array = type("array");
    array.addProperty("minItems", 1);
    array.addProperty("maxItems", 16);
    var step = type("object");
    var properties = new JsonObject();
    var tool = type("string");
    var choices = new JsonArray();
    new TreeSet<>(TOOLS).forEach(choices::add);
    tool.add("enum", choices);
    properties.add("tool", tool);
    properties.add("args", type("object"));
    var supplies = type("object");
    var amount = type("integer");
    amount.addProperty("minimum", 1);
    amount.addProperty("maximum", 256);
    supplies.add("additionalProperties", amount);
    supplies.addProperty("description", "普通物品注册ID到最低随身数量；仅作执行前准备条件，实际动作另核验");
    properties.add("requires", supplies);
    var wait = type("integer");
    wait.addProperty("minimum", 0);
    wait.addProperty("maximum", 120);
    properties.add("wait_seconds", wait);
    step.add("properties", properties);
    var required = new JsonArray();
    required.add("tool");
    required.add("args");
    step.add("required", required);
    array.add("items", step);
    return array;
  }

  private static JsonObject type(String name) {
    var o = new JsonObject();
    o.addProperty("type", name);
    return o;
  }

  private static int integer(JsonElement value) {
    try {
      return value.getAsBigDecimal().intValueExact();
    } catch (Exception e) {
      throw new IllegalArgumentException("流程数量必须为整数");
    }
  }

  private static int number(JsonObject a, String key, int fallback, int min, int max) {
    int n = a.has(key) ? integer(a.get(key)) : fallback;
    if (n < min || n > max) throw new IllegalArgumentException(key + "超出范围");
    return n;
  }

  /** Avoid loading any Minecraft runtime classes in plan validation/tests. */
  private static final class AgentFree {
    static String string(JsonObject a, String key, String fallback) {
      return a.has(key) ? a.get(key).getAsString() : fallback;
    }
  }
}
