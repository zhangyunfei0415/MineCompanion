package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GoalApproachTest {
  @Test
  void recordedEdgeCellCannotBeAFalseZeroLengthGoal() {
    assertTrue(ArrivalGate.near(94.5, 87, 60.5, 94.5, 87, 62.5, false));
    assertFalse(ArrivalGate.near(94.0116303958, 87, 60.5012796818, 94.5, 87, 62.5, false));
    assertFalse(ArrivalGate.candidate(94.5, 87, 60.5, 94.5, 87, 62.5, false));
    assertTrue(ArrivalGate.candidate(95.5, 87, 61.5, 94.5, 87, 62.5, false));
  }

  @Test
  void InteriorGoalHasRoomForWaypointTolerance() {
    for (int i = 0; i < 360; i++) {
      double a = Math.toRadians(i);
      double x = 1.5 + .399 * Math.cos(a), z = .399 * Math.sin(a);
      assertTrue(ArrivalGate.near(x, 87, z, 0, 87, 0, false));
    }
  }

  @Test
  void finalPickupOrInteractionWaypointMustMeetActualGoal() {
    assertFalse(ArrivalGate.advanceWaypoint(.01, true, true, false));
    assertTrue(ArrivalGate.advanceWaypoint(.01, true, true, true));
    assertTrue(ArrivalGate.advanceWaypoint(.01, true, false, false));
    assertFalse(ArrivalGate.advanceWaypoint(.01, false, true, true));
  }

  @Test
  void candidatesStillRespectHeightAndExactStances() {
    assertFalse(ArrivalGate.candidate(0, 86, 0, 0, 87, 0, false));
    assertTrue(ArrivalGate.candidate(.2, 87, 0, 0, 87, 0, true));
    assertFalse(ArrivalGate.candidate(.5, 87, 0, 0, 87, 0, true));
  }
}
