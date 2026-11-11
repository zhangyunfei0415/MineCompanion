package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class HarnessTest {
  @Test
  void expiryRevokesOldControllerButPreservesInterruptedReceiptForReconnect() {
    var s = new HarnessSession();
    String old = s.connect("A", 0);
    var pending = s.begin("pending", "fetch:1", 1, "fetch");
    assertTrue(s.expired(HarnessSession.TTL));
    s.interrupt("lease expired");
    assertEquals("interrupted", pending.get("state").getAsString());
    String fresh = s.connect("A", HarnessSession.TTL + 1);
    assertNotEquals(old, fresh);
    assertThrows(IllegalArgumentException.class, () -> s.check(old, "A", HarnessSession.TTL + 2));
    s.check(fresh, "A", HarnessSession.TTL + 2);
    assertSame(pending, s.receipt("pending"));
    assertThrows(IllegalArgumentException.class, () -> s.begin("pending", "fetch:1", 2, "fetch"));
  }

  @Test
  void validHeartbeatExtendsLeaseWithoutChangingActionState() {
    var s = new HarnessSession();
    String lease = s.connect("A", 0);
    var r = s.begin("one", "observe", 1, "observe");
    s.check(lease, "A", 59_000);
    assertTrue(s.active(118_999));
    assertTrue(s.expired(119_000));
    assertEquals("running", r.get("state").getAsString());
  }

  @Test
  void exclusiveControllerLeaseExpiresAndCannotCrossWorld() {
    var s = new HarnessSession();
    String a = s.connect("world-A", 10);
    assertThrows(IllegalArgumentException.class, () -> s.connect("world-A", 11));
    assertThrows(IllegalArgumentException.class, () -> s.check(a, "world-B", 12));
    assertThrows(IllegalArgumentException.class, () -> s.check("wrong", "world-A", 12));
    s.check(a, "world-A", 100);
    assertTrue(s.active(60_099));
    assertTrue(s.expired(60_100));
    assertThrows(IllegalArgumentException.class, () -> s.check(a, "world-A", 60_100));
  }

  @Test
  void repeatedActionIdReturnsOriginalReceiptButChangedPayloadIsRejected() {
    var s = new HarnessSession();
    s.connect("A", 0);
    var r = s.begin("action1", "fetch:32", 4, "fetch");
    r.addProperty("state", "returned");
    r.addProperty("result", "verified 32");
    assertSame(r, s.existing("action1", "fetch:32"));
    assertThrows(IllegalArgumentException.class, () -> s.existing("action1", "fetch:64"));
    assertThrows(IllegalArgumentException.class, () -> s.begin("action1", "fetch:32", 4, "fetch"));
  }

  @Test
  void emergencyStopAndNewEpochInterruptPendingActionsWithoutReplayingThem() {
    var s = new HarnessSession();
    s.connect("A", 0);
    var a = s.begin("1", "mine", 8, "mine");
    s.invalidateEpoch(9);
    assertEquals("interrupted", a.get("state").getAsString());
    s.interrupt("stop");
    assertFalse(s.active(1));
    s.connect("A", 2);
    assertSame(a, s.existing("1", "mine"));
    s.interrupt("world change");
    s.connect("B", 3);
    assertEquals("interrupted", s.existing("1", "mine").get("state").getAsString());
  }

  @Test
  void evictedReceiptsRemainTombstoned() {
    var s = new HarnessSession();
    s.connect("A", 0);
    for (int i = 0; i < 257; i++)
      s.begin("id" + i, "x", 1, "observe").addProperty("state", "returned");
    assertThrows(IllegalArgumentException.class, () -> s.existing("id0", "x"));
    assertNotNull(s.existing("id256", "x"));
  }

  @Test
  void localJournalIsLineDelimitedBoundedAndDoesNotNeedMinecraft() throws Exception {
    Path dir = Files.createTempDirectory("companion-harness-test");
    Path file = dir.resolve("record.jsonl");
    try (var r = new HarnessRecorder(file)) {
      for (int i = 0; i < 8192; i++) {
        var e = new JsonObject();
        e.addProperty("event", i);
        r.add(e);
      }
      assertEquals(8192, r.rows());
      assertThrows(IllegalStateException.class, () -> r.add(new JsonObject()));
      assertEquals(8192, Files.readAllLines(file).size());
    } finally {
      Files.deleteIfExists(file);
      Files.delete(dir);
    }
  }

  private static HttpResponse<String> send(
      HarnessHttp server, String method, String token, String body, String origin)
      throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json");
    if (origin != null) b.header("Origin", origin);
    return HttpClient.newHttpClient()
        .send(
            b.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void actualLoopbackHttpEnforcesAuthOriginBodyLimitsAndNotificationSemantics() throws Exception {
    try (var server =
        new HarnessHttp(
            0,
            () -> "test-token",
            req -> {
              var r = new JsonObject();
              r.addProperty("ok", true);
              return CompletableFuture.completedFuture(r);
            })) {
      String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
      assertEquals(401, send(server, "POST", "wrong", body, null).statusCode());
      assertEquals(
          403, send(server, "POST", "test-token", body, "https://evil.example").statusCode());
      assertEquals(405, send(server, "GET", "test-token", "", null).statusCode());
      assertEquals(400, send(server, "POST", "test-token", "broken", null).statusCode());
      assertEquals(413, send(server, "POST", "test-token", "x".repeat(32769), null).statusCode());
      assertEquals(
          202,
          send(
                  server,
                  "POST",
                  "test-token",
                  "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                  null)
              .statusCode());
      var good = send(server, "POST", "test-token", body, null);
      assertEquals(200, good.statusCode());
      assertTrue(
          JsonParser.parseString(good.body())
              .getAsJsonObject()
              .getAsJsonObject("result")
              .get("ok")
              .getAsBoolean());
    }
  }

  @Test
  void jumpHasGroundPulseAirReleaseAndLandingCooldown() {
    var j = new JumpCycle();
    assertTrue(j.step(true, true, 0));
    assertTrue(j.step(true, true, 1));
    assertFalse(j.step(false, true, 2));
    assertFalse(j.step(false, true, 5));
    assertFalse(j.step(true, true, 7));
    assertFalse(j.step(true, true, 8));
    assertFalse(j.step(true, true, 10));
    assertTrue(j.step(true, true, 11));
  }

  @Test
  void bumpingFlatWallDoesNotJumpWithoutRaisedPathStep() {
    var j = new JumpCycle();
    for (int i = 0; i < 100; i++) assertFalse(j.step(true, false, i));
  }

  @Test
  void avatarStateUsesRealWorkAndNeverClaimsPixelArtToBeTaskSuccess() {
    assertEquals(7, AvatarState.column(false, true, true, "制作", false));
    assertEquals(4, AvatarState.column(false, false, true, "近战", true));
    assertEquals(3, AvatarState.column(false, false, true, "采矿", true));
    assertEquals(6, AvatarState.column(false, false, true, "熔炼", false));
    assertEquals(0, AvatarState.column(true, false, true, "采矿", false));
  }
}
