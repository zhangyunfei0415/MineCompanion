package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import dev.mibao.companion.BlueprintScaffoldPlanner.Mode;
import dev.mibao.companion.BlueprintScaffoldPlanner.Pos;
import dev.mibao.companion.BlueprintScaffoldPlanner.WorldView;
import java.util.*;
import org.junit.jupiter.api.Test;

class PlacementAndBlueprintSectorTest {

  @Test
  void aimConvergenceAndHitPointSectorCatchFortyFiveDegreeFlip() {
    assertTrue(FaceHitMath.yawConverged(90.2f, 90.0f, 90.0f));
    assertTrue(FaceHitMath.yawConverged(-179.6f, -179.7f, 180.0f));
    assertFalse(FaceHitMath.yawConverged(88.0f, 90.0f, 90.0f));
    assertFalse(FaceHitMath.yawConverged(90.1f, 88.5f, 90.0f));

    // Eye stands east of target, looking WEST, but edge hit shifts north enough to trigger 45-degree flip.
    assertFalse(FaceHitMath.hitPointInLookSector(10.5, 10.5, 10.0, 9.6, "west"));
    assertTrue(FaceHitMath.hitPointInLookSector(11.5, 10.5, 10.0, 10.5, "west"));
  }

  @Test
  void planClusterEnforcesFacingSectorsAndPreservesPrimaryTargetCoverage() {
    // Reproduces QA-2 steps 61/62: two oak_stairs[facing=south] at (6,-57,5) and (5,-57,5)
    // requiring player stand on the north side (z < 5). Ground is walkable everywhere at y=-60.
    Pos step61 = new Pos(6, -57, 5);
    Pos step62 = new Pos(5, -57, 5);

    assertTrue(BlueprintScaffoldPlanner.stanceMatchesRequiredSide(6, 3, step61, "north"));
    assertFalse(BlueprintScaffoldPlanner.stanceMatchesRequiredSide(6, 7, step61, "north"));

    WorldView flatWorld =
        new WorldView() {
          @Override
          public boolean isLoaded(int x, int y, int z) {
            return true;
          }

          @Override
          public boolean isFreeAir(int x, int y, int z) {
            return y >= -60 && !(y == -57 && (x == 5 || x == 6) && z == 5);
          }

          @Override
          public boolean isWalkableStand(int x, int y, int z) {
            return y == -60 && isFreeAir(x, y, z) && isFreeAir(x, y + 1, z);
          }

          @Override
          public boolean hasSturdyTopFace(int x, int y, int z) {
            return y <= -61;
          }
        };

    var sides = Map.of(step61, "north", step62, "north");
    var plan =
        BlueprintScaffoldPlanner.planCluster(
            flatWorld,
            List.of(step61, step62),
            new Pos(4, -60, 2),
            Mode.SCAFFOLDING,
            4.2,
            Set.of(),
            step61,
            sides);

    assertTrue(plan.validLifo());
    assertTrue(plan.uncoveredTargets().isEmpty(), "Both stairs must be covered: " + plan.uncoveredTargets());
    assertFalse(plan.poses().isEmpty());
    for (var pose : plan.poses()) {
      for (var covered : pose.coveredTargets()) {
        assertTrue(
            BlueprintScaffoldPlanner.stanceMatchesRequiredSide(
                pose.stand().x(), pose.stand().z(), covered, "north"),
            "Scaffold stand pose must satisfy required north sector for " + covered);
      }
    }
    // Stand Y must be -58 (eyeY = -56.38 >= -57.05) so scaffolding reaches top face at y=-57.
    for (var pose : plan.poses()) {
      assertEquals(-58, pose.stand().y(), "Roof corner standY must place eyes above target block");
      assertTrue(pose.stand().y() + 1.62 >= -57.0 + 0.05);
    }
    assertTrue(
        plan.buildOrder().stream().anyMatch(c -> c.wy() == -59),
        "Must place scaffolding at wy=-59 for standY=-58");
  }
}
