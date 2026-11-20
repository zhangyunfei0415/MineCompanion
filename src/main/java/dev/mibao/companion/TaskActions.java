package dev.mibao.companion;

import java.util.*;

/** Executable task entries shared by decision validation and the asynchronous task facade. */
final class TaskActions {
  static final Set<String> ALLOWED;

  static {
    var tools =
        new TreeSet<>(
            Set.of(
                "fish",
                "mine_expedition",
                "excavate_mine_route",
                "illuminate_area",
                "work_session",
                "craft_item",
                "smelt_item",
                "brew_batch",
                "enchant_item",
                "anvil_item",
                "harvest_plants",
                "harvest_honey",
                "restock",
                "transport",
                "farm_cycle",
                "build_platform",
                "build_bridge",
                "build_shelter",
                "boat_to",
                "elytra_land",
                "rest_at_bed",
                "recover_death_items",
                "take_vanilla_output",
                "run_library_skill",
                "command_skill_run",
                "fetch",
                "deposit",
                "open_container",
                "use_block",
                "use_entity",
                "go_to",
                "observe",
                "observe_compact",
                "report_progress",
                "eat_food",
                "place_block",
                "break_block",
                "drop_inventory",
                "use_hand",
                "use_item_on_block",
                "select_slot",
                "move_inventory",
                "organize_inventory",
                "stow_cursor",
                "close_screen",
                "open_inventory",
                "vanilla_menu_select",
                "inspect_gui",
                "inspect_menu",
                "inspect_block",
                "inspect_scene",
                "collect_items",
                "harvest_replant",
                "plant_crops",
                "fertilize_crops",
                "look_at",
                "game_command",
                "ae_fetch",
                "ae_deposit",
                "rs_fetch",
                "rs_deposit",
                "wait_menu"));
    Rules.TOOLS.stream().filter(t -> t.startsWith("gui_")).forEach(tools::add);
    tools.retainAll(Rules.TOOLS);
    ALLOWED = Collections.unmodifiableSet(tools);
  }

  static String unsupported(String tool) {
    return "[TASK_UNSUPPORTED] " + tool + "；合法候选工具：" + String.join(",", ALLOWED);
  }
}
