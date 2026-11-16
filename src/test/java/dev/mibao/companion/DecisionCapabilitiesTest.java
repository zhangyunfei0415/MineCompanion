package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class DecisionCapabilitiesTest {
  @Test
  void originalTasksAndEverydayActionsRemainAvailable() {
    assertTrue(
        TaskActions.ALLOWED.containsAll(
            Set.of(
                "fish",
                "mine_expedition",
                "illuminate_area",
                "work_session",
                "craft_item",
                "smelt_item",
                "restock",
                "transport",
                "farm_cycle",
                "build_platform",
                "build_bridge",
                "run_library_skill",
                "command_skill_run")));
    assertTrue(
        TaskActions.ALLOWED.containsAll(
            Set.of(
                "deposit",
                "fetch",
                "use_block",
                "use_entity",
                "go_to",
                "observe",
                "report_progress",
                "eat_food",
                "place_block",
                "break_block",
                "drop_inventory",
                "gui_action",
                "gui_point",
                "gui_slot")));
    assertFalse(TaskActions.ALLOWED.contains("decision_select"));
    assertFalse(TaskActions.ALLOWED.contains("task_submit"));
    assertTrue(Rules.TOOLS.containsAll(TaskActions.ALLOWED));
    for (String tool : TaskActions.ALLOWED)
      assertTrue(TaskActions.unsupported("bad").contains(tool));
    assertEquals(
        TaskActions.ALLOWED.size(),
        ReasonCodes.describe("失败：[TASK_UNSUPPORTED] bad")
            .getAsJsonArray("suggested_alternatives")
            .size());
  }

  @Test
  void httpErrorsAreSpecificAndRedacted() {
    var p =
        new DecisionWire.Profile("JEV", "http://127.0.0.1:8000/v1/systemone", "test", "secret-key");
    for (int status : new int[] {400, 422, 503}) {
      var error = DecisionFailure.describe(p, status, "{\"detail\":\"secret-key error\"}", null);
      assertEquals("DECISION_HTTP_" + status, error.get("reason_code").getAsString());
      assertFalse(error.toString().contains("secret-key"));
      assertEquals(status >= 500, error.get("retryable").getAsBoolean());
    }
    assertEquals(
        "DECISION_NETWORK",
        DecisionFailure.describe(p, 0, "", new ConnectException())
            .get("reason_code")
            .getAsString());
    assertEquals(
        "DECISION_TIMEOUT",
        DecisionFailure.describe(p, 0, "", new HttpTimeoutException("timeout"))
            .get("reason_code")
            .getAsString());
  }

  @Test
  void capacityPolicyHasAnExplicitGroundDropEscape() {
    assertTrue(DropCapacityPolicy.allowGround("allow_ground"));
    assertFalse(DropCapacityPolicy.allowGround("require_capacity"));
    assertThrows(IllegalArgumentException.class, () -> DropCapacityPolicy.allowGround("typo"));
  }

  @Test
  void deathRecoveryRequiresTheOriginalLiveAuthorization() {
    assertTrue(DeathPolicy.canResume("REASSESS", true, true, true));
    assertTrue(DeathPolicy.canResume("RESPAWN_IDLE", true, true, true));
    assertFalse(DeathPolicy.canResume("PAUSE", true, true, true));
    assertFalse(DeathPolicy.canResume("REASSESS", false, true, true));
    assertFalse(DeathPolicy.canResume("REASSESS", true, false, true));
    assertFalse(DeathPolicy.canResume("REASSESS", true, true, false));
  }

  @Test
  void localRequestUsesHttp11WithCompleteBodyAndNoUpgrade() throws Exception {
    String payload = "x".repeat(4349);
    try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      server.setSoTimeout(6000);
      var executor = Executors.newSingleThreadExecutor();
      try {
        var received =
            executor.submit(
                () -> {
                  try (var socket = server.accept()) {
                    socket.setSoTimeout(6000);
                    var input = new BufferedInputStream(socket.getInputStream());
                    var header = new ByteArrayOutputStream();
                    String text;
                    do {
                      int b = input.read();
                      if (b < 0 || header.size() > 16000) throw new IOException("invalid headers");
                      header.write(b);
                      text = header.toString(StandardCharsets.US_ASCII);
                    } while (!text.endsWith("\r\n\r\n"));
                    assertTrue(text.startsWith("POST /v1/systemone HTTP/1.1"));
                    assertFalse(text.toLowerCase().contains("upgrade:"));
                    assertEquals(
                        payload,
                        new String(input.readNBytes(payload.length()), StandardCharsets.UTF_8));
                    socket
                        .getOutputStream()
                        .write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                                .getBytes(StandardCharsets.US_ASCII));
                    return text;
                  }
                });
        var request =
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.getLocalPort() + "/v1/systemone"))
                .timeout(Duration.ofSeconds(6))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        assertEquals(
            200,
            DecisionTransport.create()
                .send(request, HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertTrue(received.get(6, TimeUnit.SECONDS).contains("Content-Length: 4349"));
      } finally {
        executor.shutdownNow();
      }
    }
  }
}
