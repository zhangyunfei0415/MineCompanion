package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/**
 * Stable descriptions shared by local skills, model tools and Harness. No game runtime required.
 */
public final class CapabilityCatalog {
  public static String family(String tool) {
    if (tool.equals("fish")) return "gathering";
    if (tool.equals("illuminate_area")) return "construction";
    if (tool.equals("vehicle_control")) return "movement";
    if (Set.of("run_order", "prepare_bag", "logistics_status").contains(tool)) return "logistics";
    if (tool.startsWith("ae_") || tool.startsWith("rs_")) return "virtual_storage";
    if (Set.of("game_command", "creative_take", "creative_clear_slot").contains(tool))
      return "test_preparation";
    if (tool.equals("write_sign") || tool.equals("vanilla_menu_select")) return "interface";
    if (tool.startsWith("gui_")
        || Set.of("click_slot", "open_inventory", "close_screen", "wait_menu", "stow_cursor")
            .contains(tool)) return "interface";
    if (tool.contains("inventory")
        || Set.of("fetch", "deposit", "transport", "restock", "open_container").contains(tool))
      return "inventory";
    if (Set.of("go_to", "look_at", "press_control").contains(tool)) return "movement";
    if (Set.of(
            "press_key",
            "use_hand",
            "interact_block",
            "use_block",
            "use_item_on_block",
            "select_slot",
            "use_entity")
        .contains(tool)) return "interaction";
    if (tool.contains("craft") || tool.contains("smelt") || tool.equals("prepare_workshop"))
      return "production";
    if (tool.contains("crop") || tool.contains("farm") || tool.contains("harvest"))
      return "farming";
    if (Set.of("break_block", "attack_block").contains(tool)) return "gathering";
    if (Set.of("swim_out", "unstick").contains(tool)) return "movement";
    if (tool.startsWith("mine") || tool.equals("chop_wood") || tool.startsWith("collect"))
      return "gathering";
    if (tool.equals("hunt_mobs")) return "combat";
    if (tool.contains("recipe") || tool.equals("search_items")) return "recipes";
    if (tool.contains("library") || tool.contains("skill")) return "skills";
    return "observation";
  }

  public static boolean mutates(String tool) {
    return Set.of("run_order", "prepare_bag", "resume_order").contains(tool)
        || Rules.WRITE.contains(tool)
        || Set.of(
                "select_slot",
                "open_inventory",
                "close_screen",
                "use_entity",
                "drop_inventory",
                "run_library_skill",
                "run_skill",
                "run_preset",
                "work_session",
                "farm_cycle",
                "craft_item",
                "smelt_item",
                "prepare_workshop",
                "transport",
                "restock")
            .contains(tool);
  }

  public static String dependency(String tool) {
    if (tool.startsWith("ae_")) return "ae2";
    if (tool.startsWith("rs_")) return "refinedstorage";
    if (Set.of(
            "search_items",
            "lookup_recipes",
            "recipe_details",
            "show_recipe",
            "jei_transfer_recipe")
        .contains(tool)) return "jei";
    return "";
  }

  public static JsonObject describe(String query, Set<String> installed) {
    var o = new JsonObject();
    o.addProperty("schema_version", 1);
    var entries = new JsonArray();
    for (String name : new TreeSet<>(Rules.TOOLS)) {
      String f = family(name), dep = dependency(name);
      if (query != null && !query.isBlank() && !name.contains(query) && !f.contains(query))
        continue;
      var r = new JsonObject();
      r.addProperty("tool", name);
      r.addProperty("family", f);
      r.addProperty("write", mutates(name));
      r.addProperty("dependency", dep);
      r.addProperty("component_present", dep.isEmpty() || installed.contains(dep));
      r.addProperty(
          "availability",
          !dep.isEmpty() && !installed.contains(dep)
              ? "missing_component"
              : "runtime_preconditions_apply");
      r.addProperty(
          "preconditions",
          "takeover for actions; current world/mode/menu/permissions checked at execution");
      r.addProperty(
          "evidence", "tool-specific actual state/receipts; returned is not business success");
      entries.add(r);
    }
    o.add("capabilities", entries);
    o.addProperty("legacy_tool_names_preserved", true);
    o.addProperty("universal_custom_machine_support", false);
    return o;
  }
}
