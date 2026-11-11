package dev.mibao.companion;

import com.google.gson.*;

/**
 * Explicit protocol facts. Does not infer provider vision support or accept arbitrary external
 * code.
 */
public final class HarnessCapabilities {
  public static JsonObject describe(int tools) {
    return describe(tools, 60);
  }

  public static JsonObject describe(int tools, int leaseSeconds) {
    var o = new JsonObject();
    o.addProperty("build", BuildInfo.ID);
    o.addProperty(
        "flat_construction",
        "preview_platform/build_platform/build_bridge/build_status; full blocks only; confirmed"
            + " floor before advancing");
    o.addProperty(
        "agent_pointer",
        "independent window-local render/poll cursor; no OS movement; game_input"
            + " move/drag/click/scroll");
    o.addProperty(
        "quest_semantic_open",
        "quest_book_open: observed QuestButton only, including offscreen; fresh quest_view"
            + " required");
    o.addProperty(
        "quest_task_submit",
        "quest_task_submit: observed task_action TaskButton only; FTB SubmitTaskMessage plus"
            + " delayed client progress/completion recheck; dependency-incomplete submits record"
            + " progress only");
    o.addProperty(
        "window_input_tool",
        "game_input; explicit full_game_control opt-in; inspect_screen required");
    o.addProperty("gui_back_tool", "one native Escape; refresh current screen afterwards");
    o.addProperty(
        "quest_widget_pagination", "offset/limit/query; max128 per page, tree budget8192");
    o.addProperty("capability_catalog_tool", true);
    o.addProperty("planning_policy_schema", 1);
    o.addProperty("external_effort_control", "advisory_only");
    o.addProperty("skill_package_schema", 1);
    o.addProperty("gui_coordinate_spaces", "gui,framebuffer; slots use gui_slot");
    o.addProperty("result_envelope_version", 1);
    o.addProperty("protocol", "authenticated_local_json_rpc_tools");
    o.addProperty("control_lease_seconds", leaseSeconds);
    o.addProperty(
        "control_lease_mode", leaseSeconds == 0 ? "manual_stop" : "renewable_idle_timeout");
    o.addProperty("manual_lease_remaining_ms", -1);
    o.addProperty("lease_max_seconds", HarnessLeasePolicy.MAX_SECONDS);
    o.addProperty("events_require_takeover", false);
    o.addProperty("events_require_lease", false);
    o.addProperty("events_scope", "current_world_and_dimension");
    o.addProperty("structured_reason_codes", true);
    o.addProperty(
        "reason_code_contract",
        "explicit bracket codes preferred; legacy text fallback; never an instruction to replay"
            + " writes");
    o.addProperty(
        "swim_recovery", "bounded normal input to nearby dry ground; not general water navigation");
    o.addProperty(
        "watch_escape_behavior",
        "pause retains authorization/lease; explicit resume; emergency stop revokes");
    o.addProperty(
        "navigation_doors",
        "wooden doors and fence gates; wait server block update; no automatic floor-trapdoor"
            + " opening");
    o.addProperty("capability_probe", true);
    o.addProperty("targeted_break_block", true);
    o.addProperty("placement_face_control", true);
    o.addProperty("game_tool_count", tools);
    o.addProperty("receipt_after_stop", true);
    o.addProperty("receipt_scope", "current_world_and_dimension");
    o.addProperty("same_request_id_returns_original_receipt", true);
    o.addProperty("status_and_receipt_require_takeover", false);
    o.addProperty("write_actions_require_takeover", true);
    o.addProperty("images_require_separate_permission", true);
    o.addProperty("screenshots_are_local_files", true);
    o.addProperty("model_api_protocol", "openai_chat_completions");
    o.addProperty("native_responses_api", true);
    o.addProperty("native_anthropic_messages", true);
    o.addProperty("native_gemini_generate_content", true);
    o.add(
        "supported_model_protocols", new com.google.gson.Gson().toJsonTree(ProviderWire.PROTOCOLS));
    o.addProperty("provider_vision_support", "unknown_until_actual_request");
    o.addProperty("arbitrary_external_code", false);
    o.addProperty("jei_recipe_query", true);
    o.addProperty("companion_context_tool", true);
    o.addProperty("historical_actions_replayed", false);
    o.addProperty("new_mod_machines", "observe_first; dedicated_client_adapter_required");
    return o;
  }

  private HarnessCapabilities() {}
}
