package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class TaskReceiptTest {
  @Test
  void busySubmissionIsAQueryableUndispatchedActionReceipt() {
    var session = new HarnessSession();
    session.connect("test-world", 0);
    var receipt = session.blocked("busy-1", "signature", 3, "task_submit",
        "失败：[BUSY] 当前忙碌，尚未派发");
    var readable = ReasonCodes.enrich(receipt.deepCopy());
    assertEquals("blocked", readable.get("state").getAsString());
    assertEquals("BUSY", readable.get("reason_code").getAsString());
    assertFalse(readable.get("dispatched").getAsBoolean());
    assertSame(receipt, session.existing("busy-1", "signature"));
  }

  @Test
  void finishedHarvestKeepsCutAndPickupEvidenceSeparate() {
    var record = new JsonObject();
    record.addProperty("tool", "harvest_plants");
    TaskReceipt.returned(record, """
        {"status":"verified","business_success":true,"reason_code":"NONE",
         "cut_verified":true,"pickup_verified":false,"pickup_state":"unconfirmed"}
        """);
    assertEquals("verified", record.getAsJsonObject("outcome").get("outcome").getAsString());
    var harvest = record.getAsJsonObject("harvest");
    assertTrue(harvest.get("cut_verified").getAsBoolean());
    assertFalse(harvest.get("pickup_verified").getAsBoolean());
  }
  @Test
  void longFailureSurvivesRedactionAndArchival() {
    var raw = new JsonObject();
    raw.addProperty("status", "partial");
    raw.addProperty("business_success", false);
    raw.addProperty("reason_code", "ITEMS_UNACCOUNTED");
    raw.addProperty("detail", "x".repeat(1800));
    raw.addProperty("recovery_verified", false);
    var evidence = new JsonObject();
    evidence.addProperty("unaccounted_input", 3);
    raw.add("evidence", evidence);
    var record = new JsonObject();
    TaskReceipt.returned(record, raw.toString());
    assertEquals(1400, record.get("result").getAsString().length());
    assertTrue(TaskReceipt.requiresReobservation(record));
    assertEquals("ITEMS_UNACCOUNTED", record.getAsJsonObject("outcome").get("reason_code").getAsString());
    assertEquals(evidence, record.getAsJsonObject("recovery").getAsJsonObject("evidence"));
    record.remove("result");
    record.remove("requires_reobservation");
    assertTrue(TaskReceipt.requiresReobservation(record));
  }

  @Test
  void legacyExcerptsAndArchivedSuccessesRemainReadable() {
    var record = new JsonObject();
    record.addProperty("result", "{\"status\":\"partial\",\"detail\":\"truncated");
    assertTrue(TaskReceipt.requiresReobservation(record));
    TaskReceipt.returned(record, "{\"status\":\"verified\",\"business_success\":true}");
    record.remove("result");
    record.remove("requires_reobservation");
    assertFalse(TaskReceipt.requiresReobservation(record));
  }

  @Test
  void miningRoutePreservesTheLastStanceAndFailureAsStructuredEvidence() {
    var record = new JsonObject();
    record.addProperty("tool", "excavate_mine_route");
    TaskReceipt.returned(record, """
        {"status":"partial","business_success":false,"reason_code":"ROUTE_STEP_BLOCKED",
         "segments_confirmed":2,"last_confirmed_stance":{"x":1,"y":32,"z":-2},
         "returned_home":false}
        """);
    assertTrue(TaskReceipt.requiresReobservation(record));
    var route = record.getAsJsonObject("mining_route");
    assertEquals(2, route.get("segments_confirmed").getAsInt());
    assertEquals(-2, route.getAsJsonObject("last_confirmed_stance").get("z").getAsInt());
    assertFalse(route.get("returned_home").getAsBoolean());
  }

  @Test
  void reorderedRequestReturnsTheOriginalReceiptWhileArrayChangesConflict() {
    var session = new HarnessSession();
    session.connect("test-world", 0);
    var first = JsonParser.parseString("{\"x\":1,\"path\":{\"y\":2,\"z\":[3,4]}}").getAsJsonObject();
    var reordered = JsonParser.parseString("{\"path\":{\"z\":[3,4],\"y\":2},\"x\":1}").getAsJsonObject();
    var changed = JsonParser.parseString("{\"x\":1,\"path\":{\"y\":2,\"z\":[4,3]}}").getAsJsonObject();
    var receipt = session.begin("request", TaskIdentity.signature("go_to", first), 1, "go_to");
    assertSame(receipt, session.existing("request", TaskIdentity.signature("go_to", reordered)));
    assertThrows(IllegalArgumentException.class,
        () -> session.existing("request", TaskIdentity.signature("go_to", changed)));
  }
}
