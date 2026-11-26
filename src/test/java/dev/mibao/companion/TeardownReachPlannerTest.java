package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import dev.mibao.companion.TeardownReachPlanner.ExitAction;
import dev.mibao.companion.TeardownReachPlanner.PlanMode;
import org.junit.jupiter.api.Test;

class TeardownReachPlannerTest {

  @Test
  void teardownDistanceBandsRespectWorkPlanLimitAndUnloadedSafety() {
    var unloaded = TeardownReachPlanner.plan(5.0, false);
    assertEquals(PlanMode.TOO_FAR, unloaded.mode());
    assertTrue(unloaded.reason().contains("UNLOADED_TARGET"));

    var close = TeardownReachPlanner.plan(5.0, true);
    assertEquals(PlanMode.LOCAL_MINE, close.mode());
    assertEquals(9, close.mineRadius());

    var edge = TeardownReachPlanner.plan(18.0, true);
    assertEquals(PlanMode.LOCAL_MINE, edge.mode());
    assertEquals(22, edge.mineRadius());

    var mid = TeardownReachPlanner.plan(18.1, true);
    assertEquals(PlanMode.APPROACH_THEN_MINE, mid.mode());
    assertEquals(24, mid.mineRadius());

    var farEdge = TeardownReachPlanner.plan(96.0, true);
    assertEquals(PlanMode.APPROACH_THEN_MINE, farEdge.mode());

    var tooFar = TeardownReachPlanner.plan(134.0, true);
    assertEquals(PlanMode.TOO_FAR, tooFar.mode());
    assertTrue(tooFar.reason().contains("SCAFFOLD_TOO_FAR"));
  }

  @Test
  void climbExitDescendsFromGroundedScaffoldTopAndNeverMarksAirborneSafe() {
    assertEquals(
        ExitAction.BLOCKED,
        TeardownReachPlanner.evaluateClimbExit(false, false, true, false, false, true, 0, 0, 80));

    // Mid-air off scaffold is never SAFE.
    assertEquals(
        ExitAction.BLOCKED,
        TeardownReachPlanner.evaluateClimbExit(true, false, false, false, false, true, 0, 0, 80));

    // Standing atop a 6-block scaffolding tower has onGround=true AND belowIsScaffolding=true -> DESCEND.
    assertEquals(
        ExitAction.DESCEND,
        TeardownReachPlanner.evaluateClimbExit(true, true, true, true, true, false, 0, 0, 100));

    // Once at ground with solid non-scaffold support below, step out when adjacent solid floor exists.
    assertEquals(
        ExitAction.STEP_OUT,
        TeardownReachPlanner.evaluateClimbExit(true, true, true, false, true, true, 30, 0, 100));

    // Safely off scaffold on ground -> SAFE.
    assertEquals(
        ExitAction.SAFE,
        TeardownReachPlanner.evaluateClimbExit(true, false, true, false, false, false, 30, 0, 100));

    assertTrue(
        TeardownReachPlanner.descentBudgetTicks(14) > TeardownReachPlanner.descentBudgetTicks(4));

    // Solid step-off required for same column at feet-below, feet, or head; cleared on adjacent column.
    assertTrue(TeardownReachPlanner.needsSolidStepOff(86, -58, 126, 86, -59, 126));
    assertTrue(TeardownReachPlanner.needsSolidStepOff(86, -59, 126, 86, -59, 126));
    assertTrue(TeardownReachPlanner.needsSolidStepOff(86, -60, 126, 86, -59, 126));
    assertFalse(TeardownReachPlanner.needsSolidStepOff(85, -58, 126, 86, -59, 126));
    assertFalse(TeardownReachPlanner.needsSolidStepOff(86, -56, 126, 86, -59, 126));

    assertTrue(TeardownReachPlanner.stepOffVerified(true, false, false, false));
    assertFalse(TeardownReachPlanner.stepOffVerified(false, false, false, false));
    assertFalse(TeardownReachPlanner.stepOffVerified(true, true, false, false));
    assertFalse(TeardownReachPlanner.stepOffVerified(true, false, true, false));
    assertFalse(TeardownReachPlanner.stepOffVerified(true, false, false, true));

    assertTrue(TeardownReachPlanner.shouldCollectTeardownDrops(true, 16, true));
    assertFalse(TeardownReachPlanner.shouldCollectTeardownDrops(false, 16, true));
    assertFalse(TeardownReachPlanner.shouldCollectTeardownDrops(true, 0, true));
    assertFalse(TeardownReachPlanner.shouldCollectTeardownDrops(true, 16, false));
  }
}
