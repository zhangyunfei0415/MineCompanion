package dev.mibao.companion;

import com.google.gson.JsonObject;

/** Versioned business result, independent of display wording and transport completion. */
public record ActionResult(Status status, Code code, String detail, JsonObject evidence) {
  public enum Status { verified, partial, unverified, rejected, cancelled }
  public enum Code {
    NONE, USE_UNCONFIRMED, ENTITY_USE_UNCONFIRMED, NO_CAPACITY,
    INVALID_START_STANCE, NO_PATH, MENU_NOT_SYNCED, STALE_VIEW, POLICY_DENIED
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
