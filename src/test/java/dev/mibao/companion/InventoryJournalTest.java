package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class InventoryJournalTest {
  private JsonObject snapshot(String session, String... stacks) {
    var o = new JsonObject();
    o.addProperty("session", session);
    var a = new JsonArray();
    for (String stack : stacks) a.add(JsonParser.parseString(stack));
    o.add("slots", a);
    return o;
  }

  @Test
  void pickupIsRecordedWithoutClaimingProduction() {
    var before = snapshot("a");
    var after = snapshot("a", "{'fingerprint':'iron#A','count':32}");
    var journal = InventoryJournal.changes(before, after, "a", 100);
    assertEquals(
        "observed_change_unattributed",
        journal.get(0).getAsJsonObject().get("source").getAsString());
    assertEquals(
        32, journal.get(0).getAsJsonObject().getAsJsonObject("delta").get("iron#A").getAsInt());
  }

  @Test
  void nbtVariantsRemainDistinctAcrossSaveReload() {
    var before = snapshot("a", "{'fingerprint':'gun#A','count':1}");
    var after = snapshot("a", "{'fingerprint':'gun#B','count':1}");
    var text = InventoryJournal.changes(before, after, "a", 100).toString();
    var d =
        JsonParser.parseString(text)
            .getAsJsonArray()
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("delta");
    assertEquals(-1, d.get("gun#A").getAsInt());
    assertEquals(1, d.get("gun#B").getAsInt());
  }

  @Test
  void rearrangingAndMergingDoesNotChangeQuantity() {
    var before =
        snapshot(
            "a",
            "{'fingerprint':'iron','count':32,'inventory_slot':9}",
            "{'fingerprint':'iron','count':32,'inventory_slot':35}");
    var after = snapshot("a", "{'fingerprint':'iron','count':64,'inventory_slot':9}");
    assertTrue(InventoryJournal.changes(before, after, "a", 100).isEmpty());
  }

  @Test
  void durabilityChangeIsNotProduction() {
    var before =
        snapshot(
            "a",
            "{'fingerprint':'sword#damage0','quantity_fingerprint':'sword#identity','count':1}");
    var after =
        snapshot(
            "a",
            "{'fingerprint':'sword#damage1','quantity_fingerprint':'sword#identity','count':1}");
    assertTrue(InventoryJournal.changes(before, after, "a", 100).isEmpty());
  }

  @Test
  void cursorTransferIsNotLossOrExternalSupply() {
    var before = snapshot("a", "{'fingerprint':'iron','count':32}");
    var lifted = snapshot("a");
    lifted.add("cursor", JsonParser.parseString("{'fingerprint':'iron','count':32}"));
    assertTrue(InventoryJournal.changes(before, lifted, "a", 100).isEmpty());
  }

  @Test
  void reconnectDoesNotAttributeOfflineChanges() {
    var before = snapshot("old");
    var after = snapshot("new", "{'fingerprint':'diamond','count':64}");
    assertTrue(InventoryJournal.changes(before, after, "new", 100).isEmpty());
  }

  @Test
  void journalIsBoundedAndSurvivesJsonRoundTrip() {
    var old = snapshot("a");
    for (int i = 1; i <= 30; i++) {
      var next = snapshot("a", "{'fingerprint':'iron','count':" + i + "}");
      next.add("changes", InventoryJournal.changes(old, next, "a", i));
      old = JsonParser.parseString(next.toString()).getAsJsonObject();
    }
    assertEquals(24, old.getAsJsonArray("changes").size());
    assertEquals(7, old.getAsJsonArray("changes").get(0).getAsJsonObject().get("at").getAsInt());
  }
}
