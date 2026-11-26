package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class MiningSearchTest {
  @Test
  void clustersOreCellsDeterministicallyAndPlansVisitOrder() {
    var ores =
        List.of(
            new MiningRoute.Cell(10, 12, 10),
            new MiningRoute.Cell(11, 12, 10),
            new MiningRoute.Cell(2, 12, 2),
            new MiningRoute.Cell(3, 13, 2));
    var clusters = MiningSearch.cluster(ores, 1);
    assertEquals(2, clusters.size());
    assertEquals(new MiningRoute.Cell(2, 12, 2), clusters.get(0).anchor());
    assertEquals(new MiningRoute.Cell(10, 12, 10), clusters.get(1).anchor());

    var start = new MiningRoute.Cell(12, 12, 10);
    var ordered = MiningSearch.visitOrder(start, clusters, null);
    assertEquals(clusters.get(1).anchor(), ordered.get(0).anchor());
    assertEquals(clusters.get(0).anchor(), ordered.get(1).anchor());
  }

  @Test
  void reserveRejectsFloorClearanceOverlapAndClearanceOverBudget() {
    var f = new MiningRoute.Cell(0, 63, 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> new MiningSearch.Reserve(List.of(), Set.of(f), Set.of(f)));

    var hugeClear = new HashSet<MiningRoute.Cell>();
    for (int i = 0; i <= MiningSearch.MAX_CLEARANCE; i++) {
      hugeClear.add(new MiningRoute.Cell(i, 64, 0));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new MiningSearch.Reserve(List.of(), Set.of(), hugeClear));
  }

  @Test
  void reconnectAndShortestVerifiedReturnEnforceFloorSafetyAndPreferZeroNewClearance() {
    var c0 = new MiningRoute.Cell(0, 64, 0);
    var c1 = new MiningRoute.Cell(1, 64, 0);
    var c2 = new MiningRoute.Cell(2, 64, 0);
    var cBad = new MiningRoute.Cell(0, 64, 1);

    var f0 = new MiningRoute.Cell(0, 63, 0);
    var f1 = new MiningRoute.Cell(1, 63, 0);
    var f2 = new MiningRoute.Cell(2, 63, 0);

    // Unsafe shortcut destroys reserved floor f0; safe return reuses already-cleared cells.
    var unsafeArc = new MiningSearch.Arc(c2, c0, List.of(f0), List.of(), 1);
    var step2To1 = new MiningSearch.Arc(c2, c1, List.of(c1, c1.offset(0, 1, 0)), List.of(f1), 1);
    var step1To0 = new MiningSearch.Arc(c1, c0, List.of(c0, c0.offset(0, 1, 0)), List.of(f0), 1);
    var detour = new MiningSearch.Arc(c2, cBad, List.of(cBad), List.of(cBad.offset(0, -1, 0)), 1);

    var graph = MiningSearch.KnownGraph.of(List.of(unsafeArc, step2To1, step1To0, detour));
    var planned =
        MiningSearch.Reserve.of(
            Set.of(f0, f1, f2),
            Set.of(
                c0, c0.offset(0, 1, 0),
                c1, c1.offset(0, 1, 0),
                c2, c2.offset(0, 1, 0)));

    var ret = MiningSearch.shortestVerifiedReturn(graph, c2, c0, planned).orElseThrow();
    assertEquals(2, ret.arcs().size());
    assertEquals(c0, ret.arcs().get(1).to());
    assertTrue(Collections.disjoint(ret.floors(), ret.clearance()));

    var recon = MiningSearch.reconnect(graph, c2, Set.of(cBad), planned).orElseThrow();
    assertEquals(cBad, recon.arcs().get(0).to());
  }

  @Test
  void directedVisitOrderTerminatesAndReconnectKeepsNonDominatedConfluence() {
    var nodes =
        List.of(
            new MiningSearch.Cluster(List.of(new MiningRoute.Cell(0, 64, 0)), new MiningRoute.Cell(0, 64, 0)),
            new MiningSearch.Cluster(List.of(new MiningRoute.Cell(10, 64, 0)), new MiningRoute.Cell(10, 64, 0)),
            new MiningSearch.Cluster(List.of(new MiningRoute.Cell(0, 64, 10)), new MiningRoute.Cell(0, 64, 10)),
            new MiningSearch.Cluster(List.of(new MiningRoute.Cell(10, 64, 10)), new MiningRoute.Cell(10, 64, 10)),
            new MiningSearch.Cluster(List.of(new MiningRoute.Cell(5, 64, 20)), new MiningRoute.Cell(5, 64, 20)));
    var arcs =
        List.of(
            new MiningSearch.Arc(nodes.get(0).anchor(), nodes.get(3).anchor(), List.of(), List.of(), 1),
            new MiningSearch.Arc(nodes.get(3).anchor(), nodes.get(4).anchor(), List.of(), List.of(), 20),
            new MiningSearch.Arc(nodes.get(4).anchor(), nodes.get(2).anchor(), List.of(), List.of(), 1),
            new MiningSearch.Arc(nodes.get(2).anchor(), nodes.get(1).anchor(), List.of(), List.of(), 1),
            new MiningSearch.Arc(nodes.get(1).anchor(), nodes.get(3).anchor(), List.of(), List.of(), 1));
    var ordered =
        MiningSearch.visitOrder(new MiningRoute.Cell(-1, 64, 0), nodes, MiningSearch.KnownGraph.of(arcs));
    assertEquals(5, ordered.size());

    var s = new MiningRoute.Cell(0, 64, 0);
    var join = new MiningRoute.Cell(1, 64, 0);
    var g = new MiningRoute.Cell(2, 64, 0);
    var fConflict = new MiningRoute.Cell(9, 63, 9);
    var altGraph =
        MiningSearch.KnownGraph.of(
            List.of(
                new MiningSearch.Arc(s, join, List.of(), List.of(fConflict), 1),
                new MiningSearch.Arc(s, join, List.of(), List.of(), 3),
                new MiningSearch.Arc(join, g, List.of(fConflict), List.of(), 1)));
    var res = MiningSearch.reconnect(altGraph, s, Set.of(g), MiningSearch.Reserve.empty());
    assertTrue(res.isPresent());
    assertEquals(g, res.get().arcs().get(res.get().arcs().size() - 1).to());
  }
}
