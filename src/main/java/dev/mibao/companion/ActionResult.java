package dev.mibao.companion;

import com.google.gson.JsonObject;

/** Versioned business result, independent of display wording and transport completion. */
public record ActionResult(Status status, Code code, String detail, JsonObject evidence) {
  public enum Status {
    verified,
    partial,
    unverified,
    rejected,
    cancelled
  }

  public enum Code {
    NONE,
    USE_UNCONFIRMED,
    ENTITY_USE_UNCONFIRMED,
    NO_CAPACITY,
    INVALID_START_STANCE,
    NO_PATH,
    MENU_NOT_SYNCED,
    STALE_VIEW,
    POLICY_DENIED,
    INCOMPLETE_COVERAGE,
    FISHING_UNCONFIRMED,
    TIME_BUDGET,
    MINING_CARGO_CHANGED,
    MINING_PICKUP_UNCONFIRMED,
    MINING_NO_PROGRESS,
    MINING_RETURN_FAILED,
    MINING_FOOD_UNCONFIRMED,
    MINING_FOOD_REQUIRED,
    MINING_NO_PATH,
    MINING_TOOL_REQUIRED,
    MINING_TOOL_PREPARATION_FAILED,
    MINING_SUPPLY_FAILED,
    MINING_TRANSFER_LIMIT,
    MINING_TRANSFER_UNCONFIRMED,
    MINING_STAGE_FAILED,
    MINING_TARGET_INCOMPLETE
  }

  public String json() {
    JsonObject result = new JsonObject();
    result.addProperty("schema_version", 2);
    result.addProperty("status", status.name());
    result.addProperty("reason_code", code.name());
    result.addProperty("detail", detail);
    result.addProperty("business_success", status == Status.verified);
    result.addProperty("retryable", false);
    result.add("evidence", evidence == null ? new JsonObject() : evidence.deepCopy());
    return result.toString();
  }
}
