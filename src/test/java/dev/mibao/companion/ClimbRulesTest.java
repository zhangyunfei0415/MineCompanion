package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import dev.mibao.companion.ClimbRules.ClimbMode;
import org.junit.jupiter.api.Test;

class ClimbRulesTest {

  @Test
  void trapdoorClimbableAndDescentGuardsMatchVanillaForgeRules() {
    assertFalse(ClimbRules.isTrapdoorClimbableNow(true, false, true));
    assertTrue(ClimbRules.isTrapdoorClimbableNow(true, true, true));
    assertFalse(ClimbRules.isTrapdoorClimbableNow(true, true, false));

    assertTrue(ClimbRules.isTrapdoorClimbableForPlanning(true, true, false, true, true));
    assertFalse(ClimbRules.isTrapdoorClimbableForPlanning(true, true, false, true, false));
    // Iron trapdoors cannot be auto-opened in planning.
    assertFalse(ClimbRules.isTrapdoorClimbableForPlanning(true, false, false, true, true));
    assertTrue(ClimbRules.isTrapdoorClimbableForPlanning(true, false, true, true, true));

    assertFalse(ClimbRules.canDescendVerticallyInto(true));
    assertTrue(ClimbRules.canDescendVerticallyInto(false));

    assertFalse(ClimbRules.canClimbOntoAboveCell(true, false));
    assertTrue(ClimbRules.canClimbOntoAboveCell(true, true));
    assertTrue(ClimbRules.canClimbOntoAboveCell(false, false));

    assertTrue(ClimbRules.shouldHoldJumpOnClimbGateWait(true, false, true));
    assertFalse(ClimbRules.shouldHoldJumpOnClimbGateWait(true, false, false));
    assertFalse(ClimbRules.shouldHoldJumpOnClimbGateWait(false, true, true));

    assertTrue(ClimbRules.shouldSneakToStabilizeClimb(true, false));
    assertTrue(ClimbRules.shouldSneakToStabilizeClimb(true, false, false));
    assertFalse(ClimbRules.shouldSneakToStabilizeClimb(true, false, true));
    assertFalse(ClimbRules.shouldSneakToStabilizeClimb(true, true, false));
    assertFalse(ClimbRules.shouldSneakToStabilizeClimb(true, true));
    assertFalse(ClimbRules.shouldSneakToStabilizeClimb(false, false));

    assertTrue(ClimbRules.isScaffoldingCollisionPassable(true, true));
    assertFalse(ClimbRules.isScaffoldingCollisionPassable(true, false));
    assertFalse(ClimbRules.isScaffoldingCollisionPassable(false, true));
  }

  @Test
  void climbSteeringPreventsOffColumnBunnyHopDriftAndDetectsBounceStuck() {
    // Off-column player (like QA-3d drifting east): must center first without jumping or locking east yaw.
    var offCenter =
        ClimbRules.guideClimb(
            113.1, -60.0, 61.1, false, false, 112, -60, 60, 112, -59, 60, -90f, 0f);
    assertEquals(ClimbMode.APPROACH_CENTER, offCenter.mode());
    assertFalse(offCenter.jump());

    // Drifted far from ladder (>1.0 blocks, e.g. x=114.2) -> RECOVER_REPLAN immediately.
    var drifted =
        ClimbRules.guideClimb(
            114.2, -60.0, 61.3, false, false, 112, -60, 60, 112, -59, 60, -90f, -90f);
    assertEquals(ClimbMode.RECOVER_REPLAN, drifted.mode());
    assertFalse(drifted.forward());
    assertFalse(drifted.jump());

    // Centered at base of ladder before onClimbable(): vertical jump without sideways drift or yaw lock.
    var centeredBeforeClimb =
        ClimbRules.guideClimb(
            112.5, -60.0, 60.5, false, false, 112, -60, 60, 112, -59, 60, -90f, 15f);
    assertEquals(ClimbMode.CLIMB_UP, centeredBeforeClimb.mode());
    assertFalse(centeredBeforeClimb.forward());
    assertTrue(centeredBeforeClimb.jump());
    assertEquals(15f, centeredBeforeClimb.targetYaw());

    // Properly inside ladder climbing up -> lock facing opposite & jump.
    var climbing =
        ClimbRules.guideClimb(
            112.5, -59.5, 60.5, true, false, 112, -60, 60, 112, -59, 60, -90f, 0f);
    assertEquals(ClimbMode.CLIMB_UP, climbing.mode());
    assertTrue(climbing.forward());
    assertTrue(climbing.jump());
    assertEquals(-90f, climbing.targetYaw());

    // Vertically hopping in place must not reset stuck progress.
    assertFalse(ClimbRules.madeProgress(4.0, 3.995, -60.0, -59.98, 1));
    assertTrue(ClimbRules.madeProgress(4.0, 4.0, -60.0, -59.85, 1));

    // Off-center lateral position while climbing UP through a 1x1 hatch must steer diagonally back to center.
    var offCenterNorthUp =
        ClimbRules.guideClimb(
            112.51, -56.80, 60.26, true, false, 112, -56, 60, 112, -55, 60, -90f, -90f);
    assertEquals(ClimbMode.CLIMB_UP, offCenterNorthUp.mode());
    assertTrue(offCenterNorthUp.forward());
    assertTrue(offCenterNorthUp.jump());
    assertTrue(offCenterNorthUp.targetYaw() > -70f && offCenterNorthUp.targetYaw() < -15f);

    var offCenterSouthUp =
        ClimbRules.guideClimb(
            112.51, -56.80, 60.76, true, false, 112, -56, 60, 112, -55, 60, -90f, -90f);
    assertEquals(ClimbMode.CLIMB_UP, offCenterSouthUp.mode());
    assertTrue(offCenterSouthUp.targetYaw() > -165f && offCenterSouthUp.targetYaw() < -110f);

    // Off-center lateral position while descending through a 1x1 hatch must re-center (> 0.18 threshold).
    var offCenterDown =
        ClimbRules.guideClimb(
            112.51, -54.00, 60.26, true, false, 112, -55, 60, 112, -56, 60, -90f, 0f);
    assertEquals(ClimbMode.CLIMB_DOWN, offCenterDown.mode());
    assertTrue(offCenterDown.forward());

    var h = new ClimbRules.ClimbHysteresis();
    assertFalse(h.updateLateral(0.11));
    assertTrue(h.updateLateral(0.15));
    assertTrue(h.updateLateral(0.10));
    assertFalse(h.updateLateral(0.07));
    assertFalse(h.updateLateral(0.11));

    assertFalse(h.updateDescent(0.17));
    assertTrue(h.updateDescent(0.22));
    assertTrue(h.updateDescent(0.16));
    assertFalse(h.updateDescent(0.12));
    assertFalse(h.updateDescent(0.18));

    assertFalse(ClimbRules.canAcceptClimbableArrival(true, false, false));
    assertTrue(ClimbRules.canAcceptClimbableArrival(true, false, true));
    assertTrue(ClimbRules.canAcceptClimbableArrival(true, true, false));
    assertTrue(ClimbRules.canAcceptClimbableArrival(false, false, false));
  }
}
