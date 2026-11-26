package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import dev.mibao.companion.TaskCancelPolicy.Route;
import org.junit.jupiter.api.Test;

class TaskCancelPolicyTest {

  @Test
  void routingGivesPrecedenceToGatewayThenExactInflightThenAliases() {
    assertEquals(
        Route.GATEWAY_CANCEL,
        TaskCancelPolicy.resolve("active", true, false, "scaffold"));
    assertEquals(
        Route.CANCEL_INFLIGHT_REQUEST,
        TaskCancelPolicy.resolve("active", false, true, "foreground"));
    assertEquals(
        Route.ABORT_SCAFFOLD,
        TaskCancelPolicy.resolve("scaffold", false, false, "scaffold"));
    assertEquals(
        Route.NOT_FOUND,
        TaskCancelPolicy.resolve("scaffold", false, false, "blueprint"));
    assertEquals(
        Route.ABORT_BLUEPRINT,
        TaskCancelPolicy.resolve("blueprint", false, false, "blueprint"));
    assertEquals(
        Route.CANCEL_ACTIVE_FOREGROUND,
        TaskCancelPolicy.resolve("active", false, false, "foreground"));
    assertEquals(
        Route.NOT_FOUND,
        TaskCancelPolicy.resolve("req-unknown", false, false, "foreground"));
  }

  @Test
  void harnessSessionCancelsRunningInflightAtomicallyAndIsIdempotent() {
    var session = new HarnessSession();
    session.durationSeconds(60);
    session.connect("world|overworld", 1000L);
    var receipt = session.begin("req-1", "sig-1", 3L, "go_to");
    session.markInflight("req-1", 3L);

    assertTrue(session.isInflight("req-1", "world|overworld", 3L));
    assertFalse(session.isInflight("req-1", "world|nether", 3L));
    assertFalse(session.isInflight("req-1", "world|overworld", 4L));

    assertTrue(session.cancelRunning("req-1", "world|overworld", 3L, "失败：[CANCELLED] 已取消"));
    assertEquals("cancelled", receipt.get("state").getAsString());
    assertFalse(session.isInflight("req-1", "world|overworld", 3L));
    assertFalse(session.cancelRunning("req-1", "world|overworld", 3L, "duplicate"));
  }
}
