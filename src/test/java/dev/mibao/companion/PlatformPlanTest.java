package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PlatformPlanTest {
  @Test
  void rectangleIsBoundedUniqueAndSerpentine() {
    var cells = PlatformPlan.rectangle(10, 70, -20, 3, 2).cells();
    assertEquals(6, new HashSet<>(cells).size());
    assertEquals(new PlatformPlan.Cell(10, -20), cells.get(0));
    assertEquals(new PlatformPlan.Cell(12, -19), cells.get(3));
    assertEquals(new PlatformPlan.Cell(10, -19), cells.get(5));
    assertThrows(IllegalArgumentException.class, () -> PlatformPlan.rectangle(0, 0, 0, 33, 1));
    assertThrows(IllegalArgumentException.class, () -> PlatformPlan.rectangle(0, 0, 0, 1, 0));
  }

  @Test
  void bridgeAllDirectionsKeepLengthWidthAndStart() {
    for (String direction : List.of("north", "south", "east", "west")) {
      var area = PlatformPlan.bridge(0, 70, 0, direction, 16, 3);
      assertEquals(48, area.cells().size());
      assertTrue(area.cells().contains(new PlatformPlan.Cell(0, 0)));
      if (direction.equals("north")) assertEquals(-15, area.z());
      if (direction.equals("west")) assertEquals(-15, area.x());
    }
    assertThrows(IllegalArgumentException.class, () -> PlatformPlan.bridge(0, 0, 0, "up", 16, 1));
    assertThrows(
        IllegalArgumentException.class, () -> PlatformPlan.bridge(0, 0, 0, "north", 65, 1));
  }

  @Test
  void pathsNeverCrossUnknownOrMissingFloor() {
    var a = new PlatformPlan.Cell(0, 0);
    var b = new PlatformPlan.Cell(2, 0);
    var floor = Set.of(a, b);
    assertFalse(PlatformPlan.reachable(a, floor::contains, 32).containsKey(b));
    floor = Set.of(a, new PlatformPlan.Cell(1, 0), b);
    var result = PlatformPlan.reachable(a, floor::contains, 32);
    assertEquals(List.of(a, new PlatformPlan.Cell(1, 0), b), PlatformPlan.path(result, b));
  }

  @Test
  void searchBudgetAndInvalidStartAreBounded() {
    var start = new PlatformPlan.Cell(0, 0);
    assertEquals(10, PlatformPlan.reachable(start, c -> true, 10).size());
    assertTrue(PlatformPlan.reachable(start, c -> false, 10).isEmpty());
  }

  @Test
  void edgeCoverageDistinguishesCrouchedOverhangAndVoid() {
    var floor = new PlatformPlan.Cell(0, 0);
    assertEquals(.36, PlatformPlan.overlap(.5, .5, .3, floor), .00001);
    assertTrue(PlatformPlan.overlap(1.1, .5, .3, floor) > .07);
    assertEquals(0, PlatformPlan.overlap(1.31, .5, .3, floor), .00001);
    assertTrue(PlatformPlan.overlap(1.1, 1.1, .3, floor) < .07);
  }

  @Test
  void placementDoesNotAcceptPredictionOrWrongConsumption() {
    assertFalse(PlacementProof.accepted(true, false, true, 64, 63, 63));
    assertFalse(PlacementProof.accepted(true, true, true, 64, 63, 64));
    assertFalse(PlacementProof.accepted(true, true, true, 64, 62, 62));
    assertTrue(PlacementProof.accepted(true, true, true, 64, 63, 63));
  }

  @Test
  void buildWritesRequirePlacementPermissionButPreviewIsReadonly() {
    assertTrue(Rules.WRITE.contains("build_platform"));
    assertTrue(Rules.WRITE.contains("build_bridge"));
    assertFalse(Rules.WRITE.contains("preview_platform"));
    assertFalse(Rules.WRITE.contains("build_status"));
    assertFalse(Rules.limitedDenial("build_platform", true, false, true).isBlank());
  }
}
