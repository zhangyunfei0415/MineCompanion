package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SurvivalPolicyTest {
  @Test
  void vanillaSafetyFloorsArePreserved() {
    assertEquals(5, SurvivalPolicy.critical(20));
    assertEquals(10, SurvivalPolicy.work(20));
    assertEquals(12, SurvivalPolicy.combat(20));
  }

  @Test
  void hundredHealthPlayerDoesNotWorkDownToFiveHealth() {
    assertEquals(25, SurvivalPolicy.critical(100));
    assertEquals(40, SurvivalPolicy.work(100));
    assertEquals(50, SurvivalPolicy.combat(100));
  }

  @Test
  void thresholdsDoNotBecomeLessConservativeWithSmallMaximum() {
    assertTrue(SurvivalPolicy.critical(8) >= 5);
    assertTrue(SurvivalPolicy.combat(8) >= SurvivalPolicy.work(8));
  }
}
