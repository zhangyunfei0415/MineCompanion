package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HarnessLeasePolicyTest {
  @Test
  void presetsExpireAndRenewAtTheirOwnIdleDeadline() {
    for (int seconds : new int[] {300, 3600, 21600, 43200, 86400, HarnessLeasePolicy.MAX_SECONDS}) {
      var s = new HarnessSession();
      s.durationSeconds(seconds);
      var id = s.connect("world", 100);
      assertTrue(s.active(99 + seconds * 1000L));
      assertTrue(s.expired(100 + seconds * 1000L));
      s.check(id, "world", 200);
      assertEquals(seconds * 1000L, s.remaining(200));
    }
  }

  @Test
  void manualLeaseDoesNotExpireButStillRejectsOtherOwnersAndWorlds() {
    var s = new HarnessSession();
    s.durationSeconds(0);
    var id = s.connect("A", 0);
    assertTrue(s.active(365L * 24 * 3600 * 1000));
    assertFalse(s.expired(365L * 24 * 3600 * 1000));
    assertEquals(-1, s.remaining(100));
    assertEquals(0, s.ttlSeconds());
    assertThrows(IllegalArgumentException.class, () -> s.connect("A", 100));
    assertThrows(IllegalArgumentException.class, () -> s.check("wrong", "A", 100));
    assertThrows(IllegalArgumentException.class, () -> s.check(id, "B", 100));
    s.revokeLease();
    assertFalse(s.active(100));
  }

  @Test
  void changingLiveLeasePreservesIdAndCannotReviveRevokedControl() {
    var s = new HarnessSession();
    var id = s.connect("A", 0);
    s.reconfigure(0, 100);
    s.check(id, "A", 100_000_000);
    assertEquals(-1, s.remaining(100_000_000));
    s.reconfigure(21600, 100_000_000);
    assertEquals(21600000, s.remaining(100_000_000));
    s.interrupt("user stop");
    s.reconfigure(0, 100_000_001);
    assertFalse(s.active(100_000_001));
  }

  @Test
  void customMinutesHaveExplicitBounds() {
    assertEquals(5400, HarnessLeasePolicy.customMinutes("90"));
    assertEquals(HarnessLeasePolicy.MAX_SECONDS, HarnessLeasePolicy.customMinutes("43200"));
    for (String v : new String[] {"0", "-1", "43201", "abc", "999999999999"})
      assertThrows(IllegalArgumentException.class, () -> HarnessLeasePolicy.customMinutes(v));
    assertEquals(60, HarnessLeasePolicy.normalize(-1));
    assertEquals(0, HarnessLeasePolicy.normalize(0));
    assertEquals("直到手动停止", HarnessLeasePolicy.label(0));
  }
}
