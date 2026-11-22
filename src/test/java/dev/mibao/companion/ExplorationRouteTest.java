package dev.mibao.companion;

import static dev.mibao.companion.ExplorationGraph.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ExplorationRouteTest {
  private static Cell c(int x, int y, int z) { return new Cell(x, y, z); }

  @Test void walksStairsInEitherDirectionButNeverAssumesTheReverseOfADropOrWarp() {
    var graph = new ExplorationGraph();
    Cell entry = c(0, 64, 0), upper = c(1, 65, 0), lower = c(2, 61, 0), exit = c(200, 64, 0);
    graph.walk(entry, upper, false, 1);
    graph.transition(upper, lower, "", EdgeKind.ONE_WAY_DROP, 2);
    graph.transition(lower, exit, "", EdgeKind.WARP_TRANSITION, 3);
    assertEquals(1, graph.route(upper, Set.of(entry)).size());
    assertEquals(1, graph.route(entry, Set.of(upper)).size());
    assertTrue(graph.route(lower, Set.of(entry)).isEmpty());
    assertTrue(graph.route(entry, Set.of(lower)).isEmpty());
    assertTrue(graph.route(exit, Set.of(lower)).isEmpty());
  }

  @Test void blockageSplitsOnlyTheFailedLinkAndFindsVerifiedDetour() {
    var graph = new ExplorationGraph();
    Cell a = c(0, 64, 0), b = c(1, 64, 0), c = c(2, 64, 0), d = c(3, 64, 0);
    graph.walk(a, b, false, 1);
    graph.walk(b, c, false, 1);
    graph.walk(c, d, false, 1);
    var route = graph.route(a, Set.of(d));
    graph.update(route.get(1), State.BLOCKED, "closed", 2);
    assertEquals(1, graph.route(b, Set.of(a)).size());
    assertEquals(1, graph.route(c, Set.of(d)).size());
    assertTrue(graph.route(b, Set.of(d)).isEmpty());
    Cell downA = c(1, 64, 1), downD = c(2, 64, 1);
    graph.walk(b, downA, false, 3);
    graph.walk(downA, downD, false, 3);
    graph.walk(downD, c, false, 3);
    assertEquals(4, graph.route(b, Set.of(d)).size());
    graph.walk(b, c, false, 4);
    assertEquals(2, graph.route(b, Set.of(d)).size());
  }

  @Test void observedFrontierAndMechanismDoNotBecomeSafeReturnEdges() {
    var graph = new ExplorationGraph();
    Cell a = c(0, 64, 0), b = c(1, 64, 0);
    graph.mark(a, Kind.SAFE_POINT, null, java.util.List.of(), 1);
    graph.opening(a, b, 1);
    assertTrue(graph.route(a, Set.of(b)).isEmpty());
    graph.transition(a, b, "", EdgeKind.ONE_WAY_MECHANISM, 2);
    assertTrue(graph.route(b, Set.of(a)).isEmpty());
    graph.walk(a, b, false, 3);
    assertEquals(1, graph.route(b, Set.of(a)).size());
  }

  @Test void miningOnlyTagsWalkingLinksAdjacentToTheClearedCell() {
    var graph = new ExplorationGraph();
    for (int i = 0; i < 6; i++) graph.walk(c(i, 64, 0), c(i + 1, 64, 0), false, 1);
    graph.tagCleared(c(3, 64, 0), 2);
    var route = graph.route(c(0, 64, 0), Set.of(c(6, 64, 0)));
    assertEquals(6, route.size());
    assertEquals(2, route.stream().filter(s -> s.edge().mined_segment()).count());
    assertFalse(route.get(0).edge().mined_segment());
    assertFalse(route.get(5).edge().mined_segment());
  }
}
