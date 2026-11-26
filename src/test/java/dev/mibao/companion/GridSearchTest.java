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

  @Test
  void weightedPackedReusesWorkspaceAllowsReopeningAndPreservesActions() {
    var workspace = new GridSearch.SearchWorkspace();
    // Graph: 0 -> 1 (cost 5, action 1), 0 -> 2 (cost 1, action 2), 2 -> 1 (cost 1, action 3), 1 -> 3 (cost 1, action 4).
    // Heuristic h(2)=10 overestimates slightly so node 1 is reached twice cleanly (reopening lowers g from 5 to 2).
    long k0 = GridSearch.packRelative(0, 0, 0);
    long k1 = GridSearch.packRelative(1, 0, 0);
    long k2 = GridSearch.packRelative(0, 1, 0);
    long k3 = GridSearch.packRelative(2, 0, 0);

    GridSearch.KeyNeighbours graph =
        (from, sink) -> {
          if (from == k0) {
            sink.accept(k1, 5.0, (byte) 1);
            sink.accept(k2, 1.0, (byte) 2);
          } else if (from == k2) {
            sink.accept(k1, 1.0, (byte) 3);
          } else if (from == k1) {
            sink.accept(k3, 1.0, (byte) 4);
          }
        };

    var search =
        GridSearch.weightedPacked(
            k0,
            k -> k == k3,
            graph,
            k -> k == k2 ? 0.5 : 0.0,
            GridSearch.KeyCodec.relative(10, 64, 10),
            100,
            workspace);
    while (search.state() == GridSearch.State.SEARCHING) search.advance(8, Long.MAX_VALUE);
    assertEquals(GridSearch.State.FOUND, search.state());
    assertEquals(3, search.pathLength());
    assertEquals(k2, search.pathKey(0));
    assertEquals((byte) 2, search.pathAction(0));
    assertEquals(k1, search.pathKey(1));
    assertEquals((byte) 3, search.pathAction(1));
    assertEquals(k3, search.pathKey(2));
    assertEquals((byte) 4, search.pathAction(2));
    assertEquals(new GridSearch.Cell(12, 64, 10), search.path().get(2));

    // Reuse same workspace cleanly for a second search.
    var search2 =
        GridSearch.weightedPacked(
            k0,
            k -> k == k1,
            graph,
            k -> 0.0,
            GridSearch.KeyCodec.relative(0, 0, 0),
            100,
            workspace);
    while (search2.state() == GridSearch.State.SEARCHING) search2.advance(8, Long.MAX_VALUE);
    assertEquals(GridSearch.State.FOUND, search2.state());
    assertEquals(2, search2.pathLength());
    assertEquals(k1, search2.pathKey(1));
  }

  @Test
  void closedNodeReopeningAndStaleWorkspaceCancellationAreIsolated() {
    var workspace = new GridSearch.SearchWorkspace();
    long k0 = GridSearch.packRelative(0, 0, 0);
    long k1 = GridSearch.packRelative(1, 0, 0);
    long k2 = GridSearch.packRelative(0, 1, 0);
    long k3 = GridSearch.packRelative(2, 0, 0);

    // 1 expands first (closed with g=5), relaxes k3 (f=15), then k2 expands (g=2),
    // reopens closed k1 with lower g=3, pushing k1 ahead of k3 -> optimal route k0->k2->k1->k3.
    GridSearch.KeyNeighbours reopenGraph =
        (from, sink) -> {
          if (from == k0) {
            sink.accept(k1, 5.0, (byte) 1);
            sink.accept(k2, 2.0, (byte) 2);
          } else if (from == k1) {
            sink.accept(k3, 10.0, (byte) 3);
          } else if (from == k2) {
            sink.accept(k1, 1.0, (byte) 4);
          }
        };
    var reopenSearch =
        GridSearch.weightedPacked(
            k0,
            k -> k == k3,
            reopenGraph,
            k -> k == k2 ? 10.0 : 0.0,
            GridSearch.KeyCodec.relative(0, 0, 0),
            100,
            workspace);
    assertEquals(GridSearch.State.FOUND, reopenSearch.advance(20, Long.MAX_VALUE));
    assertEquals(3, reopenSearch.pathLength());
    assertEquals(k2, reopenSearch.pathKey(0));
    assertEquals(k1, reopenSearch.pathKey(1));
    assertEquals(k3, reopenSearch.pathKey(2));

    // Stale search cancelling must not disrupt a newer search sharing workspace.
    var s1 = GridSearch.weightedPacked(k0, k -> k == k3, reopenGraph, k -> 0.0, GridSearch.KeyCodec.relative(0, 0, 0), 100, workspace);
    var s2 = GridSearch.weightedPacked(k0, k -> k == k3, reopenGraph, k -> 0.0, GridSearch.KeyCodec.relative(0, 0, 0), 100, workspace);
    s1.cancel();
    assertEquals(GridSearch.State.CANCELLED, s1.advance(10, Long.MAX_VALUE));
    assertEquals(GridSearch.State.FOUND, s2.advance(20, Long.MAX_VALUE));
  }
}
