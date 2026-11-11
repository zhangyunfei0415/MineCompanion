package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PickupGeometryTest {
  private static final PickupGeometry.Box DROP =
      new PickupGeometry.Box(82.365, 95, 42, 82.615, 95.25, 42.25);

  @Test
  void overheadLogDoesNotRequireStandingInTheDropCell() {
    var blocked = new GridSearch.Cell(82, 95, 42);
    var beside = new GridSearch.Cell(83, 95, 42);
    var goals = PickupGeometry.stances(DROP, .6, 1.8, beside::equals);
    assertEquals(Set.of(beside), goals);
    assertFalse(goals.contains(blocked));
    assertTrue(PickupGeometry.touches(83.5, 95, 42.5, .6, 1.8, DROP));
  }

  @Test
  void searchCanFinishBesideTheDropWithoutEnteringItsBlockedCell() {
    var start = new GridSearch.Cell(85, 95, 42);
    var end = new GridSearch.Cell(83, 95, 42);
    var goals = PickupGeometry.stances(DROP, .6, 1.8, end::equals);
    var search =
        new GridSearch(
            start,
            goals::contains,
            p -> p.x() > 83 ? List.of(new GridSearch.Cell(p.x() - 1, 95, 42)) : List.of(),
            p -> Math.abs(p.x() - 83),
            20);
    while (search.state() == GridSearch.State.SEARCHING) search.advance(2, Long.MAX_VALUE);
    assertEquals(GridSearch.State.FOUND, search.state());
    assertEquals(end, search.path().get(search.path().size() - 1));
    assertFalse(search.path().contains(new GridSearch.Cell(82, 95, 42)));
  }

  @Test
  void sixBlockFallIsInRangeWhileDistantTargetsStayOutside() {
    assertTrue(PickupGeometry.inArea(88.5, 101, 36.5, 82.49, 95, 42.125, 16));
    assertTrue(PickupGeometry.nearBroken(82.5, 101, 42.5, 82.49, 95, 42.125));
    assertFalse(PickupGeometry.nearBroken(82.5, 101, 42.5, 88, 95, 42.125));
    assertFalse(PickupGeometry.nearBroken(82.5, 101, 42.5, 82.49, 88, 42.125));
    assertFalse(PickupGeometry.inArea(0, 100, 0, 17, 100, 0, 16));
    assertFalse(PickupGeometry.inArea(0, 100, 0, 0, 83, 0, 16));
  }

  @Test
  void verticalMismatchAndRemoteStancesDoNotCountAsArrival() {
    assertFalse(PickupGeometry.touches(83.5, 92, 42.5, .6, 1.8, DROP));
    assertFalse(PickupGeometry.touches(85.5, 95, 42.5, .6, 1.8, DROP));
    assertTrue(PickupGeometry.stances(DROP, .6, 1.8, p -> false).isEmpty());
  }

  @Test
  void negativeCoordinatesUseFloorInsteadOfTruncation() {
    var box = new PickupGeometry.Box(-1.1, 64, -.1, -.9, 64.25, .1);
    var cell = new GridSearch.Cell(-2, 64, 0);
    assertEquals(Set.of(cell), PickupGeometry.stances(box, .6, 1.8, cell::equals));
  }
}
