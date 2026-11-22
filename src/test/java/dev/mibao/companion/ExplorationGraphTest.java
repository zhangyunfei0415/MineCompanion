package dev.mibao.companion;

import static dev.mibao.companion.ExplorationGraph.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplorationGraphTest {
  @TempDir Path root;

  private static Cell x(int x) { return new Cell(x, 64, 0); }

  @Test void compressesWalkedLinesAndPreservesInteriorRoutesAndNamedAnchors() {
    var graph = new ExplorationGraph();
    for (int i = 0; i < 20; i++) graph.walk(x(i), x(i + 1), false, 1);
    assertEquals(2, graph.nodes().size());
    assertEquals(1, graph.edges().size());
    assertEquals(17, graph.route(x(17), Set.of(x(0))).size());
    graph.mark(x(9), Kind.ENTRY, "西门", List.of("sign:西门"), 2);
    assertEquals(3, graph.nodes().size());
    assertEquals(2, graph.edges().size());
    assertEquals(9, graph.route(x(0), Set.of(x(9))).size());
    graph.walk(x(8), x(9), false, 3);
    assertEquals(2, graph.edges().size());
    assertEquals("西门", graph.node(x(9)).name());
  }

  @Test void jsonPreservesMinedEdgesFrontiersAndCrossDimensionJumps() {
    var graph = new ExplorationGraph();
    graph.walk(x(0), x(1), true, 1);
    graph.opening(x(1), x(2), 1);
    graph.transition(x(0), x(100), "minecraft:the_nether", EdgeKind.WARP_TRANSITION, 2);
    var gson = new Gson();
    var snapshot = gson.fromJson(gson.toJson(graph.snapshot()), Snapshot.class);
    var restored = ExplorationGraph.from(snapshot);
    assertEquals(graph.snapshot(), restored.snapshot());
    assertEquals(Kind.FRONTIER, restored.node(x(2)).kind());
    assertTrue(restored.edges().stream().anyMatch(Edge::mined_segment));
    assertTrue(restored.edges().stream().anyMatch(e -> e.kind() == EdgeKind.WARP_TRANSITION
        && e.cells().isEmpty() && e.destination_dimension().equals("minecraft:the_nether")));
  }

  @Test void evictsUnimportantNodesFirstAndBoundsStoredTopology() {
    var graph = new ExplorationGraph();
    graph.mark(x(0), Kind.ENTRY, "入口", List.of(), 1);
    for (int i = 1; i < MAX_NODES + 50; i++) graph.mark(x(i), Kind.PATH, null, List.of(), i);
    assertEquals(MAX_NODES, graph.nodes().size());
    assertNotNull(graph.node(x(0)));
    assertNull(graph.node(x(1)));
    assertNotNull(graph.node(x(MAX_NODES + 49)));
  }

  @Test void shardsAreIsolatedAndReopenAfterQueuedSave() throws Exception {
    var a = new ExplorationStore.Key("world-a", "minecraft:overworld");
    var b = new ExplorationStore.Key("world-a", "minecraft:the_nether");
    var c = new ExplorationStore.Key("world-b", "minecraft:overworld");
    assertNotEquals(ExplorationStore.path(root, a), ExplorationStore.path(root, b));
    assertNotEquals(ExplorationStore.path(root, a), ExplorationStore.path(root, c));
    var graph = ExplorationStore.load(root, a).get(5, TimeUnit.SECONDS).graph();
    graph.walk(x(0), x(1), false, 1);
    var save = ExplorationStore.save(root, a, graph.snapshot());
    var close = ExplorationStore.release(root, a);
    try {
      var reopened = ExplorationStore.load(root, a).get(5, TimeUnit.SECONDS);
      assertTrue(save.isDone() && !save.isCompletedExceptionally() && close.isDone());
      assertTrue(reopened.writable());
      assertEquals(graph.snapshot(), reopened.graph().snapshot());
      assertTrue(ExplorationStore.load(root, b).get(5, TimeUnit.SECONDS).graph().nodes().isEmpty());
      assertFalse(Files.exists(ExplorationStore.path(root, b)));
    } finally {
      ExplorationStore.release(root, a).get(5, TimeUnit.SECONDS);
      ExplorationStore.release(root, b).get(5, TimeUnit.SECONDS);
    }
  }

  @Test void recordsObservedAnchorsFromMultipleSidesCompressesBothEndsAndListsLayers() {
    var graph = new ExplorationGraph();
    Cell a = new Cell(0, 64, 0), f = new Cell(1, 64, 0), b = new Cell(2, 64, 0);
    graph.mark(a, Kind.ENTRY, "西", List.of(), 1);
    graph.mark(b, Kind.SAFE_POINT, "东", List.of(), 1);
    graph.opening(a, f, 2);
    graph.opening(b, f, 3);
    long revBefore = graph.revision();
    graph.opening(a, f, 4);
    assertEquals(revBefore, graph.revision());
    assertEquals(Set.of(a, b), graph.adjacentObservedAnchors(f));

    for (int i = 0; i < 6; i++) graph.walk(new Cell(i, 70, 0), new Cell(i + 1, 70, 0), false, 1);
    var route = graph.route(new Cell(0, 70, 0), Set.of(new Cell(6, 70, 0)));
    graph.update(route.get(2), State.BLOCKED, "gate", 2);
    assertTrue(graph.edges().stream().anyMatch(e -> e.state() == State.BLOCKED));
    graph.walk(new Cell(2, 70, 0), new Cell(3, 70, 0), false, 9);
    assertEquals(1, graph.edges().stream().filter(e -> e.from().y() == 70 && e.returnable()).count());
    assertEquals(9L, graph.edges().stream().filter(e -> e.from().y() == 70).findFirst().orElseThrow().last_seen());
    assertEquals(List.of(64, 70), graph.layers());
  }
}
