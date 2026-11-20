package dev.mibao.companion;

import com.google.gson.*;

/** Persistent business outcome and a bounded human-readable excerpt. */
final class TaskReceipt {
  private TaskReceipt() {}

  static void returned(JsonObject record, String result) {
    record.addProperty("status", "returned");
    record.addProperty("ended_at", System.currentTimeMillis());
    record.add("outcome", ActionOutcome.describe(result));
    record.addProperty("requires_reobservation", Rules.actionFailed(result));
    var excerpt = TaskTimeline.clean(result);
    record.addProperty("result", excerpt);
    record.addProperty("current_phase", excerpt);
    if (!result.startsWith("{")) return;
    try {
      var payload = JsonParser.parseString(result).getAsJsonObject();
      if (record.get("tool") instanceof JsonPrimitive tool) {
        switch (tool.getAsString()) {
          case "excavate_mine_route" -> {
            if (payload.has("segments_confirmed") && payload.has("last_confirmed_stance"))
              record.add("mining_route", payload);
          }
          case "harvest_plants" -> {
            if (payload.has("cut_verified") && payload.has("pickup_state"))
              record.add("harvest", payload);
          }
          case "enchant_item", "anvil_item" -> copyFields(record, "gear", payload,
              "status", "operation", "phase", "reason_code", "detail", "item_fingerprint",
              "level_cost", "levels_spent", "materials_consumed", "screen", "suggested_tool");
          case "build_shelter", "boat_to", "elytra_land", "rest_at_bed", "recover_death_items",
              "take_vanilla_output", "harvest_honey" -> record.add("survival_result", payload);
          default -> {}
        }
      }
      if (payload.has("recovery_verified") && payload.has("evidence")) {
        copyFields(record, "recovery", payload, "status", "reason_code", "recovered_items",
            "recovery_target", "recovery_verified", "recovery_action", "confirmed_output", "evidence");
      }
    } catch (JsonParseException | IllegalStateException ignored) {
      // Legacy results can look like JSON. They still have a classified outcome and excerpt.
    }
  }

  private static void copyFields(JsonObject record, String field, JsonObject payload, String... keys) {
    var data = new JsonObject();
    for (String key : keys) if (payload.has(key)) data.add(key, payload.get(key).deepCopy());
    if (data.size() > 0) record.add(field, data);
  }

  static boolean requiresReobservation(JsonObject record) {
    if (record.has("requires_reobservation"))
      return record.get("requires_reobservation").getAsBoolean();
    if (record.get("outcome") instanceof JsonObject outcome && outcome.has("outcome")) {
      return switch (outcome.get("outcome").getAsString()) {
        case "verified", "observation" -> false;
        default -> true;
      };
    }
    if (!(record.get("result") instanceof JsonPrimitive result)) return true;
    String text = result.getAsString();
    if (text.startsWith("{") || text.startsWith("[")) {
      try {
        JsonParser.parseString(text);
      } catch (JsonParseException malformed) {
        return true;
      }
    }
    return Rules.actionFailed(text) || text.startsWith("内容已隐藏");
  }
}
