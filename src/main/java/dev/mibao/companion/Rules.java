package dev.mibao.companion;

import java.net.URI;
import java.util.Set;

/** Shared validation for model calls, recorded skills and local commands. */
public final class Rules {
  private Rules() {}

  public static final Set<String> TOOLS =
      Set.of(
          "command_status",
          "integration_status",
          "column_probe",
          "light_survey",
          "find_blocks",
          "illuminate_area",
          "fish",
          "vehicle_state",
          "vehicle_control",
          "vanilla_menu_select",
          "creative_clear_slot",
          "gui_slot_target",
          "jei_transfer_recipe",
          "command_skill_save",
          "command_skill_bind",
          "command_skill_run",
          "chat_input",
          "task_submit",
          "task_status",
          "task_result",
          "task_cancel",
          "observe_compact",
          "select_tool_profile",
          "ae_workflow_state",
          "ae_pattern_action",
          "ae_craft_plan",
          "ae_craft_confirm",
          "quest_book_state",
          "preview_platform",
          "build_platform",
          "build_bridge",
          "build_status",
          "quest_book_open",
          "inspect_screen",
          "gui_back",
          "game_input",
          "quest_book_click",
          "quest_task_submit",
          "quest_book_act",
          "quest_scroll",
          "swim_out",
          "unstick",
          "break_block",
          "attack_block",
          "machine_adapters",
          "capability_probe",
          "task_timeline",
          "report_progress",
          "resume_order",
          "write_sign",
          "gui_distribute",
          "run_order",
          "prepare_bag",
          "logistics_status",
          "team_task_wait",
          "team_tasks",
          "team_task_done",
          "capability_catalog",
          "runtime_stats",
          "list_library",
          "refresh_library",
          "run_library_skill",
          "gui_select",
          "gui_point",
          "use_entity",
          "drop_inventory",
          "inspect_client",
          "report_plan",
          "interact_block",
          "rs_inventory",
          "rs_fetch",
          "rs_deposit",
          "game_command",
          "creative_take",
          "ae_inventory",
          "ae_fetch",
          "ae_deposit",
          "ae_request_craft",
          "recipe_status",
          "search_items",
          "lookup_recipes",
          "lookup_client_recipes",
          "recipe_details",
          "plan_recipe",
          "show_recipe",
          "companion_context",
          "knowledge_notes",
          "inventory_memory",
          "organize_inventory",
          "permission_status",
          "inspect_scene",
          "use_item_on_block",
          "plant_crops",
          "fertilize_crops",
          "farm_cycle",
          "work_session",
          "work_session_status",
          "check_task",
          "look_at",
          "mine_batch",
          "collect_mined",
          "chop_wood",
          "recovery_status",
          "craft_item",
          "smelt_item",
          "prepare_workshop",
          "craft_batch",
          "smelt_batch",
          "use_hand",
          "place_block",
          "input_capabilities",
          "wait_menu",
          "inspect_gui",
          "gui_slot",
          "gui_button",
          "gui_text",
          "gui_scroll",
          "open_inventory",
          "close_screen",
          "move_inventory",
          "stow_cursor",
          "eat_food",
          "list_controls",
          "press_control",
          "press_key",
          "observe",
          "list_presets",
          "run_preset",
          "transport",
          "collect_items",
          "harvest_replant",
          "mine_resource",
          "hunt_mobs",
          "request_visual",
          "restock",
          "scan_world",
          "query_world",
          "inspect_block",
          "inspect_menu",
          "open_container",
          "remember_place",
          "list_places",
          "go_to",
          "mine_blocks",
          "deposit",
          "fetch",
          "use_block",
          "click_slot",
          "select_slot",
          "craft_recipe",
          "say",
          "remember",
          "recall",
          "save_skill",
          "run_skill",
          "list_skills",
          "read_web",
          "search_web",
          "finish");
  public static final Set<String> WRITE =
      Set.of(
          "illuminate_area",
          "fish",
          "vehicle_control",
          "vanilla_menu_select",
          "creative_clear_slot",
          "gui_slot_target",
          "jei_transfer_recipe",
          "ae_pattern_action",
          "ae_craft_plan",
          "ae_craft_confirm",
          "chat_input",
          "build_platform",
          "build_bridge",
          "quest_book_open",
          "game_input",
          "gui_back",
          "quest_book_click",
          "quest_task_submit",
          "quest_book_act",
          "quest_scroll",
          "swim_out",
          "unstick",
          "break_block",
          "attack_block",
          "write_sign",
          "gui_distribute",
          "use_entity",
          "drop_inventory",
          "gui_point",
          "select_slot",
          "open_inventory",
          "close_screen",
          "interact_block",
          "rs_fetch",
          "rs_deposit",
          "game_command",
          "creative_take",
          "ae_fetch",
          "ae_deposit",
          "ae_request_craft",
          "show_recipe",
          "organize_inventory",
          "use_item_on_block",
          "plant_crops",
          "fertilize_crops",
          "look_at",
          "mine_batch",
          "collect_mined",
          "craft_batch",
          "smelt_batch",
          "use_hand",
          "place_block",
          "gui_slot",
          "gui_button",
          "gui_text",
          "gui_scroll",
          "move_inventory",
          "stow_cursor",
          "eat_food",
          "press_control",
          "press_key",
          "collect_items",
          "harvest_replant",
          "mine_resource",
          "hunt_mobs",
          "mine_blocks",
          "deposit",
          "fetch",
          "use_block",
          "open_container",
          "click_slot",
          "craft_recipe");

  public static String limitedDenial(String tool, boolean mine, boolean place, boolean hunt) {
    if (tool.equals("illuminate_area") && !place) return "失败：照明需要放置权限";
    if (Set.of(
                "break_block",
                "attack_block",
                "mine_blocks",
                "mine_resource",
                "mine_batch",
                "chop_wood",
                "harvest_replant")
            .contains(tool)
        && !mine) return "失败：当前禁止采集；请在权限面板授权";
    if (tool.equals("harvest_replant") && !place) return "失败：补种需要放置权限，请先在权限面板授权";
    if (Set.of(
                "interact_block",
                "build_platform",
                "build_bridge",
                "place_block",
                "use_item_on_block",
                "plant_crops",
                "fertilize_crops")
            .contains(tool)
        && !place) return "失败：[PERMISSION_REQUIRED] 有限模式未允许放置方块";
    if (Set.of("game_command", "creative_take", "creative_clear_slot").contains(tool))
      return "失败：指令/创造准备工具需要全部权限或逐次审批";
    if (tool.equals("hunt_mobs") && !hunt) return "失败：当前禁止主动清怪；请在权限面板授权";
    return "";
  }

  public static int bounded(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  public static boolean actionFailed(String result) {
    if (result != null && result.startsWith("{"))
      try {
        var o = com.google.gson.JsonParser.parseString(result).getAsJsonObject();
        if (o.has("status")
            && Set.of(
                    "unverified",
                    "position_changed",
                    "state_matches",
                    "rejected",
                    "dispatched",
                    "interrupted_unknown_result")
                .contains(o.get("status").getAsString())) return true;
        if (o.has("business_success") && !o.get("business_success").getAsBoolean()) return true;
      } catch (RuntimeException ignored) {
      }
    return result.startsWith("失败")
        || result.startsWith("错误")
        || result.startsWith("未转移")
        || result.startsWith("部分完成");
  }

  public static boolean standardStorage(String block) {
    if (block == null || !block.startsWith("minecraft:")) return false;
    String path = block.substring(10);
    return Set.of(
                "chest", "trapped_chest", "barrel", "hopper", "dispenser", "dropper", "shulker_box")
            .contains(path)
        || path.endsWith("_shulker_box");
  }

  public static String clean(String text, int max) {
    if (text == null) return "";
    return text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
        .substring(0, Math.min(max, text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").length()));
  }

  public static boolean safePublicUrl(String value) {
    try {
      URI u = URI.create(value);
      String h = u.getHost();
      if (!"https".equalsIgnoreCase(u.getScheme()) || h == null || u.getUserInfo() != null)
        return false;
      h = h.toLowerCase();
      return !h.equals("localhost")
          && !h.endsWith(".local")
          && !h.contains(":")
          && !h.matches("[0-9.]+")
          && !h.endsWith(".internal");
    } catch (Exception e) {
      return false;
    }
  }
}
