package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TargetRecoveryTest {
  @Test
  void targetCoolsDownAndGetsOnlyOneRetry() {
    var r = new TargetRecovery<String>(2, 8);
    assertTrue(r.available("a", 0));
    r.reject("a", 0, 40, "blocked");
    assertFalse(r.available("a", 39));
    assertTrue(r.waiting(39));
    assertTrue(r.available("a", 40));
    r.reject("a", 40, 40, "blocked again");
    assertFalse(r.available("a", 1000));
    assertFalse(r.waiting(1000));
    assertEquals("blocked again", r.lastReason());
  }

  @Test
  void anotherTargetCanRunDuringCooldownAndMemoryIsBounded() {
    var r = new TargetRecovery<String>(2, 2);
    r.reject("a", 0, 40, "a");
    assertTrue(r.available("b", 1));
    r.reject("b", 1, 40, "b");
    r.reject("c", 2, 40, "c");
    assertTrue(r.full());
    assertEquals(2, r.size());
  }
}
