package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class GridSearchTest {
  private static GridSearch.Cell c(int x, int z) {
    return new GridSearch.Cell(x, 0, z);
  }

  private static Function<GridSearch.Cell, List<GridSearch.Cell>> grid(
      Set<GridSearch.Cell> blocked) {
    return p -> {
      List<GridSearch.Cell> out = new ArrayList<>();
      for (var n :
          List.of(
              c(p.x() + 1, p.z()), c(p.x() - 1, p.z()), c(p.x(), p.z() + 1), c(p.x(), p.z() - 1)))
        if (Math.abs(n.x()) <= 10 && Math.abs(n.z()) <= 10 && !blocked.contains(n)) out.add(n);
      return out;
    };
  }

  @Test
  void targetCanBeAnInteractionStanceInsteadOfTheSolidContainer() {
    var solid = c(4, 0);
    var stance = c(3, 0);
    var search =
        new GridSearch(
            c(0, 0),
            stance::equals,
            grid(Set.of(solid)),
            p -> Math.abs(p.x() - 3) + Math.abs(p.z()),
            1000);
    while (search.state() == GridSearch.State.SEARCHING) search.advance(2, Long.MAX_VALUE);
    assertEquals(GridSearch.State.FOUND, search.state());
    assertEquals(stance, search.path().get(search.path().size() - 1));
    assertFalse(search.path().contains(solid));
  }

  @Test
  void workIsBoundedAcrossTicksAndSearchCanBeCancelled() {
    var goal = c(9, 9);
    var s = new GridSearch(c(0, 0), goal::equals, grid(Set.of()), p -> 0, 1000);
    s.advance(3, Long.MAX_VALUE);
    assertTrue(s.expanded() <= 3);
    assertEquals(GridSearch.State.SEARCHING, s.state());
    s.cancel();
    int count = s.expanded();
    assertEquals(GridSearch.State.CANCELLED, s.advance(100, Long.MAX_VALUE));
    assertEquals(count, s.expanded());
  }

  @Test
  void beingAtTheGoalIsSuccessWithAnEmptyPath() {
    var s = new GridSearch(c(0, 0), c(0, 0)::equals, grid(Set.of()), p -> 0, 1);
    assertEquals(GridSearch.State.FOUND, s.state());
    assertTrue(s.path().isEmpty());
  }

  @Test
  void blockedTerrainIsAvoidedAndTotalBudgetTerminates() {
    var s =
        new GridSearch(
            c(0, 0),
            c(3, 0)::equals,
            grid(Set.of(c(1, 0), c(2, 0))),
            p -> Math.abs(p.x() - 3) + Math.abs(p.z()),
            1000);
    while (s.state() == GridSearch.State.SEARCHING) s.advance(8, Long.MAX_VALUE);
    assertEquals(GridSearch.State.FOUND, s.state());
    assertFalse(s.path().contains(c(1, 0)));
    assertTrue(s.path().size() > 3);
    var capped = new GridSearch(c(0, 0), c(9, 9)::equals, grid(Set.of()), p -> 0, 4);
    while (capped.state() == GridSearch.State.SEARCHING) capped.advance(2, Long.MAX_VALUE);
    assertEquals(GridSearch.State.EXHAUSTED, capped.state());
    assertTrue(capped.expanded() <= 4);
  }
}
