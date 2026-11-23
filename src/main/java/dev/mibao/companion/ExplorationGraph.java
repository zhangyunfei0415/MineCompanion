package dev.mibao.companion;

import java.util.*;

/** World-local evidence. An observed opening and a walked passage have different meanings. */
final class ExplorationGraph {
  static final int MAX_NODES = 4096, MAX_EDGES = 8192, MAX_CELLS = 64;
  enum Kind { PATH, ENTRY, SAFE_POINT, JUNCTION, STAIRS, WORK_FACE, HAZARD, FRONTIER }
  enum EdgeKind { BIDIRECTIONAL, ONE_WAY_DROP, ONE_WAY_MECHANISM, WARP_TRANSITION }
  enum State { OBSERVED, VERIFIED, STALE, BLOCKED }

  record Cell(int x, int y, int z) {
    String id() { return x + "," + y + "," + z; }
    long distanceSquared(Cell other) {
      long dx = (long) x - other.x, dy = (long) y - other.y, dz = (long) z - other.z;
      return dx * dx + dy * dy + dz * dz;
    }
  }

  record Node(Cell cell, Kind kind, String name, List<String> landmark_hints, long last_seen) {
    Node { landmark_hints = List.copyOf(landmark_hints); }
    boolean important() { return !name.isBlank() || kind != Kind.PATH; }
  }

  record Edge(Cell from, Cell to, String destination_dimension, EdgeKind kind, State state,
              List<Cell> cells, boolean mined_segment, long last_seen, String reason) {
    Edge { cells = List.copyOf(cells); }
    boolean returnable() { return kind == EdgeKind.BIDIRECTIONAL && state == State.VERIFIED; }
  }

  record Snapshot(List<Node> nodes, List<Edge> edges) {
    Snapshot {
      nodes = List.copyOf(nodes);
      edges = List.copyOf(edges);
      if (nodes.size() > MAX_NODES || edges.size() > MAX_EDGES)
        throw new IllegalArgumentException("Exploration shard exceeds capacity");
    }
  }

  record Step(Edge edge, Cell from, Cell to) {}
  private record Key(Cell from, Cell to, String dimension, EdgeKind kind) {}
  private record Pending(Cell cell, int cost) {}
  private record Leg(Edge edge, int from, int to) {
    int cost() {
      return Math.abs(to - from) + Math.abs(edge.cells.get(to).y - edge.cells.get(from).y);
    }
  }

  private final LinkedHashMap<Cell, Node> nodes = new LinkedHashMap<>();
  private final LinkedHashMap<Key, Edge> edges = new LinkedHashMap<>();
  private final Map<Cell, Set<Key>> incident = new HashMap<>();
  private final Map<Cell, Set<Key>> interiors = new HashMap<>();
  /** Cells the current walk/transition is linking; trim() must not evict them mid-operation. */
  private final Set<Cell> pinned = new HashSet<>();
  private long revision;

  long revision() { return revision; }
  Node node(Cell cell) { return nodes.get(cell); }
  Collection<Node> nodes() { return Collections.unmodifiableCollection(nodes.values()); }
  Collection<Edge> edges() { return Collections.unmodifiableCollection(edges.values()); }
  List<Integer> layers() {
    return nodes.values().stream().map(n -> n.cell().y()).distinct().sorted().toList();
  }
  Set<Cell> adjacentObservedAnchors(Cell frontier) {
    var anchors = new LinkedHashSet<Cell>();
    for (Key k : incident.getOrDefault(frontier, Set.of())) {
      Edge e = edges.get(k);
      if (e == null || e.kind != EdgeKind.BIDIRECTIONAL || e.state != State.OBSERVED) continue;
      Cell other = e.from.equals(frontier) ? e.to : e.from;
      if (walked(other)) anchors.add(other);
    }
    return anchors;
  }
  boolean walked(Cell cell) {
    Node n = node(cell);
    return n != null && n.kind != Kind.FRONTIER || interiors.containsKey(cell);
  }
  Snapshot snapshot() { return new Snapshot(new ArrayList<>(nodes.values()), new ArrayList<>(edges.values())); }

  static ExplorationGraph from(Snapshot snapshot) {
    var graph = new ExplorationGraph();
    for (Node n : snapshot.nodes) {
      if (n.cell == null || n.kind == null || n.name == null || n.name.length() > 64
          || n.landmark_hints.size() > 4) throw new IllegalArgumentException("Invalid exploration node");
      graph.nodes.put(n.cell, n);
    }
    for (Edge e : snapshot.edges) {
      if (e.from == null || e.to == null || e.kind == null || e.state == null
          || e.destination_dimension == null || e.reason == null || !graph.nodes.containsKey(e.from)
          || e.destination_dimension.isEmpty() && !graph.nodes.containsKey(e.to)
          || e.cells.size() > MAX_CELLS || !validGeometry(e))
        continue; // Drop a single dangling/invalid edge instead of discarding the whole shard.
      graph.put(e);
    }
    return graph;
  }

  private static boolean validGeometry(Edge edge) {
    if (edge.kind != EdgeKind.BIDIRECTIONAL) return edge.cells.isEmpty();
    var cells = edge.cells;
    if (!edge.destination_dimension.isEmpty() || cells.size() < 2
        || !cells.get(0).equals(edge.from) || !cells.get(cells.size() - 1).equals(edge.to)) return false;
    for (int i = 1; i < cells.size(); i++) {
      Cell a = cells.get(i - 1), b = cells.get(i);
      if (Math.abs((long) a.x - b.x) + Math.abs((long) a.z - b.z) != 1
          || Math.abs((long) a.y - b.y) > 1) return false;
    }
    return true;
  }

  Node mark(Cell cell, Kind kind, String name, List<String> hints, long now) {
    splitAt(cell);
    Node previous = node(cell);
    var n = new Node(cell, kind, name != null ? name.strip() : previous == null ? "" : previous.name,
        hints.isEmpty() && previous != null ? previous.landmark_hints : hints, now);
    nodes.put(cell, n);
    revision++;
    trim();
    return n;
  }

  private void ensure(Cell c, long now) {
    Node n = node(c);
    if (n == null || n.kind == Kind.FRONTIER) mark(c, Kind.PATH, null, List.of(), now);
  }

  void walk(Cell from, Cell to, boolean mined, long now) {
    if (from.equals(to)) return;
    // Rewalking an interior step keeps its corridor compressed.
    for (Key key : touching(from)) {
      Edge edge = edges.get(key);
      int i = edge.cells.indexOf(from), j = edge.cells.indexOf(to);
      if (edge.returnable() && Math.abs(i - j) == 1 && j >= 0 && (!mined || edge.mined_segment)) return;
    }
    pinned.add(from);
    pinned.add(to);
    try {
      ensure(from, now);
      ensure(to, now);
      var edge = new Edge(from, to, "", EdgeKind.BIDIRECTIONAL, State.VERIFIED, List.of(from, to), mined, now, "");
      if (!validGeometry(edge)) throw new IllegalArgumentException("Expected contiguous walking step");
      if (node(from) == null || node(to) == null) return;
      Edge current = edges.get(key(edge));
      if (current == null || !current.returnable() || mined && !current.mined_segment) put(edge);
    } finally {
      pinned.clear();
    }
    compress(from);
    compress(to);
    trim();
  }

  void opening(Cell from, Cell to, long now) {
    if (!walked(from) || interiors.containsKey(to)) return;
    Node existingTo = node(to);
    if (existingTo != null && existingTo.kind() != Kind.FRONTIER) return;
    var edge = new Edge(from, to, "", EdgeKind.BIDIRECTIONAL, State.OBSERVED, List.of(from, to), false, now, "");
    if (!validGeometry(edge)) return;
    if (existingTo != null && edges.containsKey(key(edge))) return;
    splitAt(from);
    if (existingTo == null) mark(to, Kind.FRONTIER, null, List.of(), now);
    if (node(from) != null && node(to) != null && !edges.containsKey(key(edge))) put(edge);
    trim();
  }

  void transition(Cell from, Cell to, String destinationDimension, EdgeKind kind, long now) {
    pinned.add(from);
    if (destinationDimension.isEmpty()) pinned.add(to);
    try {
      ensure(from, now);
      if (destinationDimension.isEmpty()) ensure(to, now);
      if (node(from) == null || destinationDimension.isEmpty() && node(to) == null) return;
      put(new Edge(from, to, destinationDimension, kind, State.OBSERVED, List.of(), false, now, ""));
    } finally {
      pinned.clear();
    }
    trim();
  }

  void tagCleared(Cell cell, long now) {
    for (Key key : touching(cell)) {
      Edge edge = edges.get(key);
      if (edge == null || !edge.returnable() || edge.mined_segment) continue;
      int i = edge.cells.indexOf(cell);
      // A cleared cell labels only the incoming/outgoing walking links touching the actual cut.
      for (int j : new int[] {i - 1, i + 1}) {
        if (j < 0 || j >= edge.cells.size()) continue;
        Cell neighbor = edge.cells.get(j);
        splitAt(cell);
        splitAt(neighbor);
        var link = edges.get(key(slice(edge, List.of(cell, neighbor))));
        if (link != null && link.returnable())
          put(new Edge(link.from, link.to, "", link.kind, link.state, link.cells, true, now, ""));
      }
    }
    trim();
  }

  private static Key key(Edge e) {
    return e.kind == EdgeKind.BIDIRECTIONAL && e.from.id().compareTo(e.to.id()) > 0
        ? new Key(e.to, e.from, "", e.kind) : new Key(e.from, e.to, e.destination_dimension, e.kind);
  }

  private void put(Edge e) {
    Key key = key(e);
    remove(key);
    edges.put(key, e);
    incident.computeIfAbsent(e.from, ignored -> new LinkedHashSet<>()).add(key);
    if (e.destination_dimension.isEmpty()) incident.computeIfAbsent(e.to, ignored -> new LinkedHashSet<>()).add(key);
    for (int i = 1; i < e.cells.size() - 1; i++)
      interiors.computeIfAbsent(e.cells.get(i), ignored -> new LinkedHashSet<>()).add(key);
    revision++;
  }

  private void remove(Key key) {
    Edge e = edges.remove(key);
    if (e == null) return;
    unindex(incident, e.from, key);
    if (e.destination_dimension.isEmpty()) unindex(incident, e.to, key);
    for (int i = 1; i < e.cells.size() - 1; i++) unindex(interiors, e.cells.get(i), key);
    revision++;
  }

  private static void unindex(Map<Cell, Set<Key>> index, Cell cell, Key key) {
    var values = index.get(cell);
    if (values != null && values.remove(key) && values.isEmpty()) index.remove(cell);
  }

  private void splitAt(Cell cell) {
    for (Key k : List.copyOf(interiors.getOrDefault(cell, Set.of()))) {
      Edge e = edges.get(k);
      int index = e.cells.indexOf(cell);
      remove(k);
      put(slice(e, e.cells.subList(0, index + 1)));
      put(slice(e, e.cells.subList(index, e.cells.size())));
      nodes.putIfAbsent(cell, new Node(cell, Kind.PATH, "", List.of(), e.last_seen));
    }
  }

  private static Edge slice(Edge e, List<Cell> cells) {
    return new Edge(cells.get(0), cells.get(cells.size() - 1), "", e.kind, e.state, cells,
        e.mined_segment, e.last_seen, e.reason);
  }

  private void compress(Cell midpoint) {
    Node n = node(midpoint);
    var incidentKeys = incident.getOrDefault(midpoint, Set.of());
    if (n == null || n.important() || incidentKeys.size() != 2) return;
    var iterator = incidentKeys.iterator();
    Key ka = iterator.next(), kb = iterator.next();
    Edge a = edges.get(ka), b = edges.get(kb);
    if (!a.returnable() || !b.returnable() || a.mined_segment != b.mined_segment) return;
    var left = ordered(a, a.to.equals(midpoint));
    var right = ordered(b, b.from.equals(midpoint));
    if (left.size() + right.size() - 1 > MAX_CELLS) return;
    Cell before = left.get(left.size() - 2), after = right.get(1);
    if (before.y != midpoint.y || after.y != midpoint.y
        || before.x + after.x != midpoint.x * 2 || before.z + after.z != midpoint.z * 2) return;
    var cells = new ArrayList<>(left);
    cells.addAll(right.subList(1, right.size()));
    long seen = Math.max(a.last_seen, b.last_seen);
    remove(ka);
    remove(kb);
    nodes.remove(midpoint);
    put(new Edge(cells.get(0), cells.get(cells.size() - 1), "", b.kind, b.state, cells,
        b.mined_segment, seen, b.reason));
  }

  private static List<Cell> ordered(Edge edge, boolean forward) {
    if (forward) return edge.cells;
    var reverse = new ArrayList<>(edge.cells);
    Collections.reverse(reverse);
    return reverse;
  }

  void update(Step step, State state, String reason, long now) {
    Edge edge = edges.get(key(step.edge));
    // Sampling may have split a corridor while the job was walking it.
    if (edge == null) {
      for (Key candidate : touching(step.from)) {
        Edge part = edges.get(candidate);
        int i = part.cells.indexOf(step.from), j = part.cells.indexOf(step.to);
        if (i >= 0 && j >= 0 && Math.abs(i - j) == 1) {
          edge = part;
          break;
        }
      }
    }
    if (edge == null) return;
    // Invalidate only the failing link so a junction in the walked prefix remains reachable.
    if (edge.kind == EdgeKind.BIDIRECTIONAL && edge.cells.size() > 2) {
      splitAt(step.from);
      splitAt(step.to);
      edge = edges.get(key(slice(edge, List.of(step.from, step.to))));
      if (edge == null) return;
    }
    put(new Edge(edge.from, edge.to, edge.destination_dimension, edge.kind, state, edge.cells,
        edge.mined_segment, now, reason));
    trim();
  }


  private Set<Key> touching(Cell cell) {
    var keys = new LinkedHashSet<>(incident.getOrDefault(cell, Set.of()));
    keys.addAll(interiors.getOrDefault(cell, Set.of()));
    return keys;
  }

  List<Step> route(Cell start, Set<Cell> goals) {
    if (goals.isEmpty() || !walked(start)) return List.of();
    var queue = new PriorityQueue<Pending>(Comparator.comparingInt(Pending::cost));
    Map<Cell, Integer> best = new HashMap<>();
    Map<Cell, Leg> previous = new HashMap<>();
    Map<Key, List<Integer>> interiorGoals = new HashMap<>();
    for (Cell goal : goals) {
      for (Key key : interiors.getOrDefault(goal, Set.of())) {
        interiorGoals.computeIfAbsent(key, ignored -> new ArrayList<>()).add(edges.get(key).cells.indexOf(goal));
      }
    }
    best.put(start, 0);
    queue.add(new Pending(start, 0));
    while (!queue.isEmpty()) {
      var current = queue.remove();
      Cell cell = current.cell;
      if (current.cost != best.get(cell)) continue;
      if (goals.contains(cell)) {
        List<Step> path = new ArrayList<>();
        for (Leg leg = previous.get(cell); leg != null; leg = previous.get(leg.edge.cells.get(leg.from))) {
          int delta = Integer.signum(leg.to - leg.from);
          for (int i = leg.to; i != leg.from; i -= delta) {
            path.add(new Step(leg.edge, leg.edge.cells.get(i - delta), leg.edge.cells.get(i)));
          }
        }
        Collections.reverse(path);
        return path;
      }
      for (Key key : touching(cell)) {
        Edge edge = edges.get(key);
        if (!edge.returnable()) continue;
        int at = edge.cells.indexOf(cell);
        var exits = new ArrayList<>(interiorGoals.getOrDefault(key, List.of()));
        exits.add(0);
        exits.add(edge.cells.size() - 1);
        for (int next : exits) {
          if (next == at) continue;
          Cell to = edge.cells.get(next);
          var leg = new Leg(edge, at, next);
          int cost = current.cost + leg.cost();
          if (cost < best.getOrDefault(to, Integer.MAX_VALUE)) {
            best.put(to, cost);
            previous.put(to, leg);
            queue.add(new Pending(to, cost));
          }
        }
      }
    }
    return List.of();
  }

  void trim() {
    while (nodes.size() > MAX_NODES) {
      Node victim = nodes.values().stream().filter(n -> !n.important() && !pinned.contains(n.cell)).findFirst()
          .orElseGet(() -> nodes.values().stream().filter(n -> n.kind != Kind.ENTRY
              && n.kind != Kind.SAFE_POINT && n.name.isBlank() && !pinned.contains(n.cell)).findFirst()
              .orElseGet(() -> nodes.values().stream().filter(n -> !pinned.contains(n.cell)).findFirst()
                  .orElseGet(() -> nodes.values().iterator().next())));
      for (Key key : List.copyOf(incident.getOrDefault(victim.cell, Set.of()))) remove(key);
      nodes.remove(victim.cell);
      revision++;
    }
    while (edges.size() > MAX_EDGES) {
      Key victim = edges.entrySet().stream().filter(e -> !e.getValue().returnable()).findFirst()
          .map(Map.Entry::getKey).orElseGet(() -> edges.keySet().iterator().next());
      remove(victim);
    }
  }

  /**
   * Re-checks STALE / BLOCKED bidirectional edges against live terrain. If temporary obstacles
   * (doors opened, blocks cleared) are now passable, heals them back to VERIFIED / OBSERVED.
   */
  int revalidateEdges(java.util.function.Predicate<Edge> isClear, long now) {
    int healed = 0;
    for (Edge e : List.copyOf(edges.values())) {
      if (e.kind != EdgeKind.BIDIRECTIONAL
          || (e.state != State.STALE && e.state != State.BLOCKED)) continue;
      if (!isClear.test(e)) continue;
      Node nf = node(e.from);
      Node nt = node(e.to);
      State restored =
          (nf != null && nf.kind == Kind.FRONTIER) || (nt != null && nt.kind == Kind.FRONTIER)
              ? State.OBSERVED
              : State.VERIFIED;
      put(
          new Edge(
              e.from,
              e.to,
              e.destination_dimension,
              e.kind,
              restored,
              e.cells,
              e.mined_segment,
              now,
              ""));
      healed++;
    }
    if (healed > 0) trim();
    return healed;
  }

  Cell oldestWalkedCell() {
    for (Node n : nodes.values()) {
      if (n.kind != Kind.FRONTIER && n.kind != Kind.HAZARD) return n.cell;
    }
    return null;
  }

  Map<Long, Integer> chunkVisitCounts() {
    Map<Long, Integer> counts = new HashMap<>();
    for (Node n : nodes.values()) {
      if (n.kind != Kind.FRONTIER && n.kind != Kind.HAZARD) {
        counts.merge(ExplorationRoamPolicy.chunkKey(n.cell.x(), n.cell.z()), 1, Integer::sum);
      }
    }
    for (Edge e : edges.values()) {
      if (!e.returnable()) continue;
      for (int i = 1; i < e.cells.size() - 1; i++) {
        Cell c = e.cells.get(i);
        counts.merge(ExplorationRoamPolicy.chunkKey(c.x(), c.z()), 1, Integer::sum);
      }
    }
    return counts;
  }

  Map<Long, Integer> sectorVisitCounts() {
    Map<Long, Integer> counts = new HashMap<>();
    for (Node n : nodes.values()) {
      if (n.kind != Kind.FRONTIER && n.kind != Kind.HAZARD) {
        counts.merge(ExplorationRoamPolicy.sectorKey(n.cell.x(), n.cell.z()), 1, Integer::sum);
      }
    }
    for (Edge e : edges.values()) {
      if (!e.returnable()) continue;
      for (int i = 1; i < e.cells.size() - 1; i++) {
        Cell c = e.cells.get(i);
        counts.merge(ExplorationRoamPolicy.sectorKey(c.x(), c.z()), 1, Integer::sum);
      }
    }
    return counts;
  }
}
