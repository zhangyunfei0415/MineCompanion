package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class MiningRouteTest {
  private static final MiningRoute.Cell ENTRY = new MiningRoute.Cell(4, 64, 8);

  @Test
  void choosesGeometryFromTheObjectiveAndAvailableStraightRun() {
    assertEquals(MiningRoute.Preset.TUNNEL_1X2, MiningRoute.Preset.forObjective("tunnel", 1, 12, 24));
    assertEquals(MiningRoute.Preset.TUNNEL_3X3, MiningRoute.Preset.forObjective("tunnel", 3, 6, 24));
    assertEquals(MiningRoute.Preset.STAIRS_STRAIGHT, MiningRoute.Preset.forObjective("descend", 1, 8, 8));
    assertEquals(MiningRoute.Preset.STAIRS_SWITCHBACK, MiningRoute.Preset.forObjective("descend", 1, 8, 4));
    assertEquals(MiningRoute.Preset.STAIRS_SPIRAL_3X3, MiningRoute.Preset.forObjective("descend", 1, 8, 3));
    assertEquals(MiningRoute.Preset.BRANCH_1X2, MiningRoute.Preset.forObjective("branches", 1, 12, 24));
  }

  @Test
  void commonTunnelsPlanTheirExactCrossSectionsAndReserveTheFloor() {
    int[] crossSections = {2, 4, 9};
    for (int i = 0; i < crossSections.length; i++) {
      var route = MiningRoute.plan(ENTRY, MiningRoute.Heading.NORTH, MiningRoute.Preset.values()[i], 6, 4, 2);
      assertEquals(crossSections[i] * 6, route.plannedClearance());
      assertEquals(6, route.steps().size());
      assertEquals(new MiningRoute.Cell(4, 64, 2), route.steps().get(5).to());
      for (var step : route.steps()) {
        assertEquals(crossSections[i], step.clearance().size());
        for (var cleared : step.clearance()) assertFalse(route.floors().contains(cleared));
      }
    }
  }

  @Test
  void stairsReserveTheirPreviousFloorsAndSpiralStaysWithinThreeColumns() {
    for (var preset : new MiningRoute.Preset[] {
        MiningRoute.Preset.STAIRS_STRAIGHT, MiningRoute.Preset.STAIRS_SWITCHBACK,
        MiningRoute.Preset.STAIRS_SPIRAL_3X3}) {
      for (var direction : MiningRoute.Heading.values()) {
        var route = MiningRoute.plan(ENTRY, direction, preset, 24, 4, 2);
        var last = ENTRY;
        for (var step : route.steps()) {
          assertEquals(last, step.from());
          assertEquals(1, Math.abs(step.to().x() - last.x()) + Math.abs(step.to().z() - last.z()));
          assertTrue(last.y() - step.to().y() >= 0 && last.y() - step.to().y() <= 1);
          for (var cleared : step.clearance()) assertFalse(route.floors().contains(cleared));
          last = step.to();
        }
        assertEquals(40, last.y());
        if (preset == MiningRoute.Preset.STAIRS_SPIRAL_3X3) {
          assertEquals(ENTRY.x(), last.x());
          assertEquals(ENTRY.z(), last.z());
          var columns = new HashSet<MiningRoute.Cell>();
          for (var step : route.steps()) columns.add(new MiningRoute.Cell(step.to().x(), 0, step.to().z()));
          assertEquals(8, columns.size());
        }
      }
    }
  }

  @Test
  void branchSupportShortensOrSkipsBeforeAnyUnsupportedStepsArePlanned() {
    var shortenedAt = new MiningRoute.Cell(6, 63, 4);
    var skippedAt = new MiningRoute.Cell(3, 63, 0);
    var plan = MiningRoute.plan(ENTRY, MiningRoute.Heading.NORTH, MiningRoute.Preset.BRANCH_1X2,
        8, 4, 4, List.of(), floor -> floor.equals(shortenedAt) ? "ROUTE_SUPPORT_REQUIRED"
            : floor.equals(skippedAt) ? "UNLOADED_TARGET" : "");
    assertEquals(10, plan.steps().size());
    assertEquals(18, plan.plannedClearance());
    assertEquals(new MiningRoute.Cell(5, 64, 4), plan.steps().get(4).to());
    assertEquals(new MiningRoute.Cell(4, 64, 4), plan.steps().get(5).to());
    assertEquals(List.of(1, 0), plan.branches().stream().map(MiningRoute.Branch::planned).toList());
    assertEquals(shortenedAt, plan.branches().get(0).blockedFloor());
    assertEquals("UNLOADED_TARGET", plan.branches().get(1).limitReason());
    assertFalse(plan.floors().contains(shortenedAt));
    assertFalse(plan.floors().contains(skippedAt));
  }

  @Test
  void previouslyPreviewedBranchLimitsCannotExpandOnSubmission() {
    var caps = List.of(1, 0);
    var plan = MiningRoute.plan(ENTRY, MiningRoute.Heading.NORTH, MiningRoute.Preset.BRANCH_1X2,
        8, 4, 4, caps, floor -> "");
    assertEquals(caps, plan.branches().stream().map(MiningRoute.Branch::planned).toList());
    assertEquals("PREVIEW_LIMIT", plan.branches().get(0).limitReason());
    assertThrows(IllegalArgumentException.class,
        () -> MiningRoute.plan(ENTRY, MiningRoute.Heading.NORTH, MiningRoute.Preset.BRANCH_1X2,
            8, 4, 4, List.of(1), floor -> ""));
  }

  @Test
  void branchesWalkBackBeforeContinuingAndNeverPlanDuplicateBreaking() {
    var route = MiningRoute.plan(ENTRY, MiningRoute.Heading.NORTH, MiningRoute.Preset.BRANCH_1X2, 8, 4, 2);
    assertEquals(24, route.plannedClearance());
    assertEquals(16, route.steps().size());
    assertEquals(new MiningRoute.Cell(4, 64, 0), route.steps().get(15).to());
    assertTrue(route.steps().get(6).clearance().isEmpty());
    assertEquals(route.steps().get(3).to(), route.steps().get(7).to());
    assertThrows(IllegalArgumentException.class,
        () -> MiningRoute.plan(ENTRY, MiningRoute.Heading.NORTH, MiningRoute.Preset.TUNNEL_1X2, 25, 4, 2));
  }
}
