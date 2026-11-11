package dev.mibao.companion;

import com.google.gson.*;

/** Central effective permissions. Read-only to API/Harness: only the player edits authorization. */
public final class PermissionView {
  private PermissionView() {}

  public static JsonObject current() {
    var c = Store.config;
    var o = new JsonObject();
    boolean full = c.fullGameControl || "FULL".equals(c.permission);
    o.addProperty("configured_mode", c.permission);
    o.addProperty("mode", c.fullGameControl ? "FULL" : c.permission);
    o.addProperty("full_game_control", c.fullGameControl);
    o.addProperty("game_freedom", full);
    o.addProperty("permission_is_not_capability", true);
    o.addProperty(
        "capability_check",
        "capability_probe / capability_catalog / machine_adapters; FULL does not supply server OP"
            + " or missing execution adapters");
    o.addProperty("auto_open_doors", c.autoOpenDoors);
    o.addProperty("explicit_resource_hunt", c.huntResources);
    o.addProperty("per_action_approval", !c.fullGameControl && "ASK".equals(c.permission));
    o.addProperty("mine", full || c.allowMine);
    o.addProperty("plant_and_place", full || c.allowPlace);
    o.addProperty("hunt", full || c.allowHunt);
    o.addProperty("generic_input", full || c.allowInput);
    o.addProperty("containers", full || c.allowContainers);
    o.addProperty("gui", full || c.allowGui);
    o.addProperty("drop", full || c.allowDrop);
    o.addProperty("entity_use", full || c.allowEntityUse);
    o.addProperty("background_input", c.backgroundInput);
    o.addProperty("network_learning", c.web);
    o.addProperty("screenshots", c.harnessImages);
    o.addProperty("public_replies", c.publicReply);
    o.addProperty("chat_typing", c.allowChatTyping);
    o.addProperty("chat_submit", c.allowChatSubmit);
    o.add("commands", CommandSettings.observe());
    o.addProperty("help_notifications", c.helpNotifications);
    o.addProperty("low_health_guard", !c.testIgnoreLowHealth);
    o.addProperty("finish_local_after_disconnect", c.harnessFinishLocal);
    o.add("planning_policy", RuntimeStrategy.view());
    o.addProperty("execution_style", c.executionStyle);
    o.addProperty(
        "verification_style",
        ExecutionStyle.fluent(c.executionStyle)
            ? "prefer_server_updates_then_refresh_or_reopen"
            : "always_reopen_standard_transfers");
    o.addProperty("self_defense", c.selfDefense);
    o.addProperty("preemptive_hostiles", c.proactiveDefense);
    o.addProperty("attack_named", c.attackNamed);
    o.addProperty("attack_players", c.attackPlayers);
    o.addProperty("rule", "FULL免审批和有限模式子开关；真实物理范围、菜单同步、急停与效果核验仍生效。权限无法实现尚未支持的工具。");
    return o;
  }
}
