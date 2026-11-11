package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RecoveryWindowTest {
  @Test
  void lowHealthStartsOneMealAndWaitsWithoutRepeatedEating() {
    var w = new RecoveryWindow();
    assertEquals(RecoveryWindow.Step.EAT, w.step(0, 21, 100, 17, true, true));
    assertTrue(w.active());
    assertEquals(RecoveryWindow.Step.WAIT, w.step(1000, 21, 100, 17, true, true));
    assertEquals(RecoveryWindow.Step.HELP, w.step(45000, 21, 100, 17, true, true));
  }

  @Test
  void recoveryNeedsWorkThresholdAndDoesNotReplayOldWork() {
    var w = new RecoveryWindow();
    assertEquals(RecoveryWindow.Step.WAIT, w.step(0, 4, 20, 20, false, true));
    assertEquals(RecoveryWindow.Step.WAIT, w.step(1000, 6, 20, 20, false, true));
    assertEquals(RecoveryWindow.Step.RECOVERED, w.step(2000, 10, 20, 20, false, true));
    assertFalse(w.active());
    assertEquals(RecoveryWindow.Step.NONE, w.step(3000, 10, 20, 20, false, true));
  }

  @Test
  void foodGivenLaterIsAcceptedWithinSameBoundedEpisode() {
    var w = new RecoveryWindow();
    assertEquals(RecoveryWindow.Step.HELP, w.step(0, 4, 20, 10, false, true));
    assertEquals(RecoveryWindow.Step.EAT, w.step(1000, 4, 20, 10, true, true));
    assertEquals(RecoveryWindow.Step.HELP, w.step(46000, 4, 20, 10, true, true));
  }

  @Test
  void unknownMenuPreventsAutomaticMealButCanBeClosed() {
    var w = new RecoveryWindow();
    assertEquals(RecoveryWindow.Step.HELP, w.step(0, 4, 20, 10, true, false));
    assertEquals(RecoveryWindow.Step.EAT, w.step(1000, 4, 20, 10, true, true));
  }

  @Test
  void stopResetDoesNotLeavePendingRecovery() {
    var w = new RecoveryWindow();
    w.step(0, 4, 20, 10, true, true);
    w.reset();
    assertFalse(w.active());
    assertEquals(RecoveryWindow.Step.NONE, w.step(1000, 20, 20, 20, true, true));
  }

  @Test
  void reducedMaximumCanRecoverAtFullHealth() {
    var w = new RecoveryWindow();
    w.step(0, 4, 8, 20, false, true);
    assertEquals(RecoveryWindow.Step.RECOVERED, w.step(1000, 8, 8, 20, false, true));
  }
}
