package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class HarnessEventHubAndHttpTest {

  @Test
  void eventHubFiltersKindsHandlesTimeoutsLimitsWaitersAndScopeResync() throws Exception {
    try (var hub = new HarnessEventHub()) {
      var first = hub.publish("w1|overworld", "chat", "hi", null);
      long seq1 = first.get("sequence").getAsLong();

      // Long poll waits for player_mention specifically (ignores chat event)
      var fut = hub.waitFor("w1|overworld", seq1, Set.of("player_mention"), 5000L);
      hub.publish("w1|overworld", "chat", "noise", null);
      assertFalse(fut.isDone());

      var extra = new JsonObject();
      extra.addProperty("mention_id", "m-123");
      hub.publish("w1|overworld", "player_mention", "Alice: hi", extra);
      JsonObject page = fut.get(2, TimeUnit.SECONDS);
      assertEquals(1, page.getAsJsonArray("events").size());
      assertEquals(
          "m-123",
          page.getAsJsonArray("events").get(0).getAsJsonObject().get("mention_id").getAsString());

      // Timeout returns clean empty page
      long cur = hub.nextSequence();
      JsonObject timedOut =
          hub.waitFor("w1|overworld", cur, Set.of("player_mention"), 1000L)
              .get(3, TimeUnit.SECONDS);
      assertTrue(timedOut.get("timed_out").getAsBoolean());
      assertEquals(0, timedOut.getAsJsonArray("events").size());

      // Waiter cap protects server resources
      List<CompletableFuture<JsonObject>> holders = new ArrayList<>();
      for (int i = 0; i < HarnessEventHub.MAX_WAITERS; i++) {
        holders.add(hub.waitFor("w1|overworld", cur, Set.of(), 5000L));
      }
      var overflow = hub.waitFor("w1|overworld", cur, Set.of(), 5000L);
      assertTrue(overflow.isCompletedExceptionally());

      // Scope change wakes pending waiters with generation_changed
      hub.updateScope("w1|the_nether");
      JsonObject resync = holders.get(0).get(2, TimeUnit.SECONDS);
      assertTrue(resync.get("generation_changed").getAsBoolean());
    }
  }

  @Test
  void realLoopbackHttpDoesNotStarveControlCallsWhileFourWaitEventsAreHanging() throws Exception {
    var hub = new HarnessEventHub();
    var exec = Executors.newCachedThreadPool();
    HarnessHttp http =
        new HarnessHttp(
            0,
            () -> "secret-token",
            req -> {
              var params = req.getAsJsonObject("params");
              String name = params.get("name").getAsString();
              var args = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
              if ("wait_events".equals(name)) {
                long after = args.has("after") ? args.get("after").getAsLong() : 0L;
                long timeout = args.has("timeout_ms") ? args.get("timeout_ms").getAsLong() : 5000L;
                Set<String> kinds = new HashSet<>();
                if (args.has("kinds")) kinds.add(args.get("kinds").getAsString());
                return hub.waitFor("s|overworld", after, kinds, timeout).thenApply(HarnessEventHubAndHttpTest::wrapText);
              }
              var res = new JsonObject();
              res.addProperty("tool", name);
              res.addProperty("ok", true);
              return CompletableFuture.completedFuture(wrapText(res));
            });

    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
      URI uri = URI.create("http://127.0.0.1:" + http.port() + "/mcp");

      // Hang all MAX_WAITERS (4) concurrent long-polls (> the 2-thread request pool!)
      List<Future<JsonObject>> waits = new ArrayList<>();
      for (int i = 0; i < HarnessEventHub.MAX_WAITERS; i++) {
        final int idx = i;
        waits.add(
            exec.submit(
                () ->
                    postRpc(
                        client,
                        uri,
                        idx + 1,
                        "wait_events",
                        "{\"after\":0,\"timeout_ms\":5000,\"kinds\":\"player_mention\"}")));
      }
      Thread.sleep(150);

      // Control calls MUST return quickly without waiting for wait_events to time out
      long t0 = System.nanoTime();
      JsonObject st = postRpc(client, uri, 100, "status", "{}");
      JsonObject hb = postRpc(client, uri, 101, "heartbeat", "{\"lease\":\"l\"}");
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
      assertTrue(elapsedMs < 1500, "Control calls stalled behind long-polls: " + elapsedMs + "ms");
      assertEquals("status", st.get("tool").getAsString());
      assertEquals("heartbeat", hb.get("tool").getAsString());

      // Now publish a mention and confirm all 4 hanging wait_events wake up with the exact event
      var extra = new JsonObject();
      extra.addProperty("mention_id", "m-wakeup");
      hub.publish("s|overworld", "player_mention", "Bob: hello", extra);
      for (Future<JsonObject> f : waits) {
        JsonObject page = f.get(3, TimeUnit.SECONDS);
        assertEquals(1, page.getAsJsonArray("events").size());
        assertEquals(
            "m-wakeup",
            page.getAsJsonArray("events")
                .get(0)
                .getAsJsonObject()
                .get("mention_id")
                .getAsString());
      }
    } finally {
      http.close();
      hub.close();
      exec.shutdownNow();
    }
  }

  private static JsonObject wrapText(JsonObject payload) {
    var o = new JsonObject();
    var arr = new com.google.gson.JsonArray();
    var item = new JsonObject();
    item.addProperty("type", "text");
    item.addProperty("text", payload.toString());
    arr.add(item);
    o.add("content", arr);
    return o;
  }

  private static JsonObject postRpc(
      HttpClient client, URI uri, int id, String toolName, String argsJson) throws Exception {
    String body =
        "{\"jsonrpc\":\"2.0\",\"id\":"
            + id
            + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
            + toolName
            + "\",\"arguments\":"
            + argsJson
            + "}}";
    HttpRequest req =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer secret-token")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resp.statusCode());
    JsonObject rpc = JsonParser.parseString(resp.body()).getAsJsonObject();
    String text =
        rpc.getAsJsonObject("result")
            .getAsJsonArray("content")
            .get(0)
            .getAsJsonObject()
            .get("text")
            .getAsString();
    return JsonParser.parseString(text).getAsJsonObject();
  }
}
