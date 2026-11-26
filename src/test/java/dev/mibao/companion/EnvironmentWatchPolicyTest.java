package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EnvironmentWatchPolicyTest {
  @Test
  void staysSilentInDimensionsWithoutSkyLight() {
    var snap = EnvironmentWatchPolicy.evaluate(false, 18000L, true, true, 5, 1000L, 2000L);
    assertFalse(snap.skyLight());
    assertEquals(0, snap.toJson().size());
  }

  @Test
  void detectsDuskNightThunderAndPhantomRiskInOverworld() {
    var dusk = EnvironmentWatchPolicy.evaluate(true, 12300L, false, false, 0, 0L, 5000L);
    assertEquals("dusk", dusk.phase());
    assertFalse(dusk.nightDanger());
    assertFalse(dusk.alert().isEmpty());

    var phantom = EnvironmentWatchPolicy.evaluate(true, 15000L, false, false, 3, 0L, 5000L);
    assertTrue(phantom.nightDanger());
    assertTrue(phantom.phantomRisk());

    var storm = EnvironmentWatchPolicy.evaluate(true, 6000L, true, true, 0, 0L, 5000L);
    assertEquals("thunder", storm.weather());
    assertTrue(storm.alert().contains("雷暴"));
  }
}
