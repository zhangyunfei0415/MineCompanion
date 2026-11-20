package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.level.GameType;

/** Converts the agent's objective into a read-only, immediately usable next action. */
final class MiningPlanner {
  private MiningPlanner() {}

  static JsonObject plan(JsonObject input) {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null)
      throw new IllegalArgumentException("[WORLD_REQUIRED] 请先进入世界");
    String objective = Agent.str(input, "objective",
        input.has("block") ? "collect" : input.has("target_y") ? "descend" : "tunnel");
    var plan = new JsonObject();
    plan.addProperty("objective", objective);
    plan.addProperty("dimension", mc.level.dimension().location().toString());
    boolean canExcavate = NavigationPermissions.canBreak()
        && mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SURVIVAL;
    plan.addProperty("excavation_available", canExcavate);
    if (objective.equals("collect")) {
      if (MiningRouteJob.hasEntry(input))
        throw new IllegalArgumentException("[INVALID_ARGUMENT] collect从当前位置开始；请先前往指定采矿地点");
      return collect(input, plan, canExcavate);
    }
    if (!Set.of("tunnel", "descend", "branches").contains(objective))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] objective=collect/tunnel/descend/branches");

    var entry = MiningRouteJob.requestedEntry(input);
    var args = new JsonObject();
    args.addProperty("x", entry.getX());
    args.addProperty("y", entry.getY());
    args.addProperty("z", entry.getZ());
    plan.add("entry", args.deepCopy());
    plan.addProperty("entry_source", MiningRouteJob.hasEntry(input) ? "specified" : "player_stance");
    boolean entryReady = MiningRouteJob.entryReady(entry);
    plan.addProperty("entry_ready", entryReady);
    plan.addProperty("route_ready", false);
    if (!entryReady) {
      plan.addProperty("status", "blocked");
      plan.addProperty("reason_code", "ROUTE_ENTRY");
      plan.addProperty("replan_hint", "先走到返回的入口坐标，在完整地板上站稳后重新规划");
      return plan;
    }
    copy(input, args, "max_break", "seconds", "branch_spacing", "branch_length");
    int width = SkillCatalog.bounded(input, "width", 1, 1, 3);
    int defaultLength = objective.equals("descend") ? 8 : width == 3 ? 6 : width == 2 ? 8 : 12;
    int requestedLength = SkillCatalog.bounded(input, "length", defaultLength, 1, 384);
    int length = Math.min(24, requestedLength);
    int remaining = requestedLength - length;
    if (objective.equals("descend") && input.has("target_y")) {
      int y = SkillCatalog.bounded(input, "target_y", entry.getY(),
          mc.level.getMinBuildHeight() + 1, mc.level.getMaxBuildHeight() - 1);
      if (y > entry.getY())
        throw new IllegalArgumentException("[ROUTE_BOUNDS] 目标脚底Y须低于当前位置且留出地板");
      int drop = entry.getY() - y;
      plan.addProperty("target_y", y);
      if (drop == 0) {
        plan.addProperty("status", "already_at_depth");
        return plan;
      }
      length = Math.min(length, drop);
      remaining = drop - length;
      plan.addProperty("remaining_depth_after_segment", remaining);
    } else {
      plan.addProperty("remaining_length_after_segment", remaining);
    }
    int straightRun = SkillCatalog.bounded(input, "max_straight_run", 24, 3, 24);
    var preset = MiningRoute.Preset.forObjective(objective, width, length, straightRun);
    boolean returnHome = !input.has("return_home") || input.get("return_home").getAsBoolean();
    // A multi-segment descent must leave each finished segment at its frontier.
    args.addProperty("return_home", returnHome && remaining == 0);
    plan.addProperty("requested_return_home", returnHome);
    args.addProperty("length", length);
    args.addProperty("preset", preset.id());
    plan.addProperty("chosen_preset", preset.id());
    plan.addProperty("selection_reason", switch (objective) {
      case "descend" -> "按预计下降级数及直线可用长度选台阶";
      case "branches" -> "采用主巷和交替支路，支路完成后回主巷";
      default -> "选用 " + preset.width + "×" + preset.height + " 截面"
          + (input.has("width") ? "，按指定宽度" : "，采用常用单列矿道");
    });
    plan.add("stages", strings("核对入口、权限和第一步", "逐段施工并核对实际净空",
        remaining > 0 ? "从已确认前沿重新规划下一段；保留本段入口供最终返程" : "核对终点与所要求的返程"));
    if (!canExcavate) {
      plan.addProperty("status", "blocked");
      plan.addProperty("reason_code", NavigationPermissions.canBreak()
          ? "MODE_REQUIRED" : "NAVIGATION_BREAK_DISABLED");
      return plan;
    }

    var directions = directions(input, mc.player.getDirection());
    var checked = new JsonArray();
    var blocked = new JsonObject();
    String singleDirectionReason = "ROUTE_OPENING_BLOCKED";
    for (var direction : directions) {
      args.addProperty("direction", direction.getName());
      try {
        var preview = MiningRouteJob.openingPreview(args);
        String result = preview.get("first_step_check").getAsString();
        if (directions.size() == 1) singleDirectionReason = result;
        checked.add(direction.getName() + ": " + result);
        if (result.equals("READY")) {
          plan.add("preview", preview);
          if (preview.has("branch_limits"))
            args.add("branch_limits", preview.get("branch_limits").deepCopy());
          plan.add("next_action", action("excavate_mine_route", args));
          plan.addProperty("status", "ready");
          break;
        }
        if (preview.has("first_step_detail"))
          blocked.add(direction.getName(), preview.get("first_step_detail"));
        if (result.equals("ROUTE_ENTRY")) break;
      } catch (IllegalArgumentException e) {
        if (e.getMessage() == null || !e.getMessage().startsWith("[ROUTE_BOUNDS]")) throw e;
        if (directions.size() == 1) singleDirectionReason = "ROUTE_BOUNDS";
        checked.add(direction.getName() + ": ROUTE_BOUNDS");
      }
    }
    plan.add("directions_checked", checked);
    if (blocked.size() > 0) plan.add("direction_blockers", blocked);
    if (!plan.has("next_action")) {
      plan.addProperty("status", "blocked");
      plan.addProperty("reason_code", singleDirectionReason.equals("ROUTE_ENTRY")
          ? "ROUTE_OPENING_BLOCKED" : singleDirectionReason);
      plan.addProperty("replan_hint", "核对站位、目标截面和支撑；在任务允许时换安全入口后重算");
    } else plan.addProperty("route_ready", true);
    return plan;
  }

  private static JsonObject collect(JsonObject input, JsonObject plan, boolean canExcavate) {
    var args = new JsonObject();
    copy(input, args, "block", "item", "goal", "count", "delivery", "source", "radius",
        "vertical_range", "max_blocks", "seconds", "min_durability", "free_slots",
        "tool_policy", "auto_craft", "lighting", "return_home", "delivery_fallbacks");
    if (Agent.str(args, "goal", "items").equals("deliver") && !args.has("delivery"))
      args.addProperty("delivery", Store.data.resourceDelivery);
    var spec = MiningSpec.parse(args);
    args.addProperty("goal", spec.goal());
    args.addProperty("mode", "vein");
    args.addProperty("terrain", "detour");
    plan.add("stages", strings("搜索已加载的露头并沿矿脉采集", "核对实际挖除和拾取",
        spec.goal().equals("deliver") ? "送往已指定的交付箱并核对入箱" : "按目标核对数量和返程"));
    plan.addProperty("status", "ready");
    plan.addProperty("prospecting_available", canExcavate && input.has("allow_prospect")
        && input.get("allow_prospect").getAsBoolean());
    plan.addProperty("replan_hint", "无可用露头时，按现场证据换作业点；允许勘探才规划短矿道，再从新露头核对剩余目标");
    plan.add("next_action", action("mine_expedition", args));
    return plan;
  }

  private static List<Direction> directions(JsonObject input, Direction facing) {
    if (!input.has("direction"))
      return List.of(facing, facing.getClockWise(), facing.getCounterClockWise(), facing.getOpposite());
    var direction = Direction.byName(input.get("direction").getAsString());
    if (direction == null || direction.getAxis().isVertical())
      throw new IllegalArgumentException("[INVALID_DIRECTION] 请选择 north/east/south/west");
    return List.of(direction);
  }

  private static JsonObject action(String tool, JsonObject args) {
    var result = new JsonObject();
    result.addProperty("tool", tool);
    result.add("args", args.deepCopy());
    return result;
  }

  private static JsonArray strings(String... values) {
    var result = new JsonArray();
    for (String value : values) result.add(value);
    return result;
  }

  private static void copy(JsonObject input, JsonObject args, String... fields) {
    for (String field : fields)
      if (input.has(field)) args.add(field, input.get(field).deepCopy());
  }
}
