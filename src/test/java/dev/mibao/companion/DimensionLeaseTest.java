package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DimensionLeaseTest {
  @Test
  void sameServerTransitionPreservesLeaseButInvalidatesPendingActionsAndOldScope() {
    var session = new HarnessSession();
    session.durationSeconds(3600);
    String lease = session.connect("server|overworld", 100);
    session.begin("old", "place", 7, "place_block");
    session.rebindDimension("server|nether", 8);
    assertEquals("interrupted", session.receipt("old").get("state").getAsString());
    assertThrows(
        IllegalArgumentException.class, () -> session.check(lease, "server|overworld", 200));
    assertDoesNotThrow(() -> session.check(lease, "server|nether", 200));
    assertTrue(session.receipt("old", "server|nether").get("historical_context").getAsBoolean());
  }

  @Test
  void transitionDoesNotReviveARevokedLease() {
    var session = new HarnessSession();
    String lease = session.connect("server|overworld", 100);
    session.revokeLease();
    session.rebindDimension("server|nether", 2);
    assertFalse(session.active(200));
    assertThrows(IllegalArgumentException.class, () -> session.check(lease, "server|nether", 200));
  }
}
