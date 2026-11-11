package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/** Task-sized API menus. Changing a menu never changes permissions or Harness capabilities. */
public final class ToolMenu {
  private static final Set<String> COMMON =
      Set.of(
          "observe_compact",
          "permission_status",
          "integration_status",
          "column_probe",
          "vehicle_state",
          "capability_catalog",
          "select_tool_profile",
          "task_submit",
          "task_status",
          "task_result",
          "task_cancel",
          "report_plan",
          "report_progress",
          "finish",
          "say",
          "go_to",
          "query_world",
          "inspect_block",
          "list_library",
          "run_library_skill",
          "close_screen",
          "stow_cursor",
          "eat_food",
          "recovery_status");
  private static final Map<String, Set<String>> PROFILES =
      Map.of(
          "logistics",
              Set.of(
                  "inventory_memory",
                  "organize_inventory",
                  "restock",
                  "transport",
                  "fetch",
                  "deposit",
                  "open_container",
                  "inspect_menu",
                  "list_places",
                  "remember_place",
                  "scan_world"),
          "farming",
              Set.of(
                  "farm_cycle",
                  "plant_crops",
                  "fertilize_crops",
                  "harvest_replant",
                  "collect_items",
                  "inventory_memory",
                  "open_container",
                  "fetch",
                  "deposit",
                  "inspect_scene"),
          "production",
              Set.of(
                  "recipe_status",
                  "search_items",
                  "lookup_recipes",
                  "recipe_details",
                  "plan_recipe",
                  "craft_item",
                  "smelt_item",
                  "prepare_workshop",
                  "open_container",
                  "fetch",
                  "deposit",
                  "inspect_gui",
                  "gui_select",
                  "vanilla_menu_select",
                  "jei_transfer_recipe",
                  "ae_inventory",
                  "ae_fetch",
                  "ae_deposit",
                  "ae_request_craft",
                  "ae_workflow_state",
                  "ae_pattern_action",
                  "ae_craft_plan",
                  "ae_craft_confirm"),
          "construction",
              Set.of(
                  "light_survey",
                  "illuminate_area",
                  "find_blocks",
                  "preview_platform",
                  "build_platform",
                  "build_bridge",
                  "build_status",
                  "place_block",
                  "interact_block",
                  "use_item_on_block",
                  "look_at",
                  "move_inventory",
                  "select_slot",
                  "inspect_scene",
                  "unstick"),
          "gathering",
              Set.of(
                  "fish",
                  "find_blocks",
                  "light_survey",
                  "mine_resource",
                  "mine_batch",
                  "collect_mined",
                  "chop_wood",
                  "collect_items",
                  "break_block",
                  "inspect_scene",
                  "inventory_memory",
                  "move_inventory",
                  "restock"),
          "combat",
              Set.of(
                  "vehicle_control",
                  "hunt_mobs",
                  "inspect_scene",
                  "look_at",
                  "move_inventory",
                  "select_slot",
                  "use_hand",
                  "press_control",
                  "press_key",
                  "list_controls",
                  "unstick"),
          "gui",
              Set.of(
                  "vanilla_menu_select",
                  "inspect_screen",
                  "inspect_gui",
                  "inspect_menu",
                  "gui_select",
                  "gui_slot_target",
                  "click_slot",
                  "gui_text",
                  "gui_scroll",
                  "gui_back",
                  "game_input",
                  "request_visual",
                  "quest_book_state",
                  "quest_book_open",
                  "quest_book_act",
                  "quest_scroll",
                  "move_inventory",
                  "open_inventory"),
          "commands",
              Set.of(
                  "command_status",
                  "game_command",
                  "command_skill_save",
                  "command_skill_bind",
                  "command_skill_run",
                  "chat_input",
                  "creative_take",
                  "creative_clear_slot",
                  "inventory_memory",
                  "inspect_client"));
  private static String override = "", overrideTask = "";

  public static String profile(String task) {
    String text = (task == null ? "" : task).toLowerCase(Locale.ROOT);
    if (!override.isBlank() && overrideTask.equals(text)) return override;
    if (text.matches("(?s).*(指令|创造|/give|/home|传送).*")) return "commands";
    if (text.matches("(?s).*(任务书|gui|界面|按钮).*")) return "gui";
    if (text.matches("(?s).*(ae|rs|样板|配方|合成|做饭|烹饪|生产|熔炼|机器).*")) return "production";
    if (text.matches("(?s).*(平台|搭建|建造|桥|照明|火把).*")) return "construction";
    if (text.matches("(?s).*(农|种|收割|骨粉|田|作物).*")) return "farming";
    if (text.matches("(?s).*(战斗|攻击|防身|怪).*")) return "combat";
    if (text.matches("(?s).*(采|挖|矿|砍|木|拾取|收集|钓鱼|fishing).*")) return "gathering";
    return "logistics";
  }

  public static JsonObject use(String requested, String task) {
    if (!requested.equals("all") && !requested.equals("auto") && !PROFILES.containsKey(requested))
      throw new IllegalArgumentException(
          "[INVALID_ARGUMENT]"
              + " profile=auto/logistics/farming/production/construction/gathering/combat/gui/commands/all");
    override = requested.equals("auto") ? "" : requested;
    overrideTask = (task == null ? "" : task).toLowerCase(Locale.ROOT);
    var out = new JsonObject();
    out.addProperty("profile", profile(task));
    out.addProperty("rule", "下一次模型请求切换工具说明；完整Harness不变，权限不变；新任务按目标选择");
    return out;
  }

  public static JsonArray select(JsonArray all, String task, Set<String> installed) {
    String profile = profile(task);
    var allowed = PROFILES.getOrDefault(profile, Set.of());
    var out = new JsonArray();
    for (var el : all) {
      String tool = el.getAsJsonObject().getAsJsonObject("function").get("name").getAsString();
      if (!profile.equals("all") && !COMMON.contains(tool) && !allowed.contains(tool)) continue;
      String dep = CapabilityCatalog.dependency(tool);
      if (!dep.isBlank() && !installed.contains(dep)) continue;
      out.add(el.deepCopy());
    }
    return out;
  }

  private ToolMenu() {}
}
