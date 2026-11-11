package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class ObservationFramesTest {
  private static JsonObject j(String s) {
    return JsonParser.parseString(s).getAsJsonObject();
  }

  @Test
  void replayReconstructsInventoryChangeAndRemovedFieldsWithoutMutatingEarlierFrame() {
    var frames = new ObservationFrames();
    var initial = j("{\"epoch\":1,\"screen\":\"world\",\"inventory\":[1],\"old\":true}");
    var full = frames.encode(initial, 0, false);
    var next = j("{\"epoch\":1,\"screen\":\"world\",\"inventory\":[2]}");
    var delta = frames.encode(next, 500, false);
    assertEquals("observation_delta", delta.get("kind").getAsString());
    assertFalse(delta.getAsJsonObject("changes").has("screen"));
    assertEquals(
        next,
        ObservationFrames.apply(
            ObservationFrames.apply(null, 0, full), full.get("frame").getAsLong(), delta));
    assertTrue(initial.has("old"));
  }

  @Test
  void gapIsRejectedAndPeriodicOrMenuScopeChangesCreateFullCheckpoints() {
    var frames = new ObservationFrames();
    var first = frames.encode(j("{\"epoch\":1,\"screen\":\"world\"}"), 0, false);
    var delta = frames.encode(j("{\"epoch\":1,\"screen\":\"world\"}"), 500, false);
    assertThrows(IllegalArgumentException.class, () -> ObservationFrames.apply(j("{}"), 0, delta));
    assertEquals(
        "observation",
        frames
            .encode(j("{\"epoch\":1,\"screen\":\"chest\"}"), 600, false)
            .get("kind")
            .getAsString());
    assertEquals(
        "observation",
        frames
            .encode(j("{\"epoch\":2,\"screen\":\"chest\"}"), 700, false)
            .get("kind")
            .getAsString());
    assertEquals(
        "observation",
        frames
            .encode(j("{\"epoch\":2,\"screen\":\"chest\"}"), 10700, false)
            .get("kind")
            .getAsString());
  }

  @Test
  void repeatedLargeSnapshotsEncodeAsSmallDeltas() {
    var frames = new ObservationFrames();
    var o = j("{\"epoch\":1,\"screen\":\"world\"}");
    o.addProperty("inventory", "x".repeat(20000));
    frames.encode(o, 0, false);
    assertTrue(frames.encode(o, 500, false).toString().length() < 200);
  }
}
