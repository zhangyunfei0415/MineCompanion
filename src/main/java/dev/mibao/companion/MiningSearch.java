package dev.mibao.companion;

import java.util.*;

/**
 * Pure 3D bounded mining topology &amp; reserve planner. Groups scattered ore cells into clusters,
 * plans bounded multi-patch visit orders, reconnects components over verified arcs, and enforces
 * {@code floors ∩ clearance == ∅} with at most 384 unique cleared blocks.
 */
public final class MiningSearch {
  public static final int MAX_CLEARANCE = 384;

  public record Arc(
      MiningRoute.Cell from,
      MiningRoute.Cell to,
      List<MiningRoute.Cell> clearance,
      List<MiningRoute.Cell> floor,
      int cost) {
    public Arc(
        MiningRoute.Cell from,
        MiningRoute.Cell to,
        List<MiningRoute.Cell> clearance,
        List<MiningRoute.Cell> floor) {
      this(from, to, clearance, floor, 1);
    }

    public Arc {
      if (cost < 0) throw new IllegalArgumentException("Arc cost must be non-negative");
      clearance = List.copyOf(new LinkedHashSet<>(clearance));
      floor = List.copyOf(new LinkedHashSet<>(floor));
    }
  }

  public record KnownGraph(Map<MiningRoute.Cell, List<Arc>> outgoing) {
    public static KnownGraph of(Collection<Arc> arcs) {
      var grouped = new HashMap<MiningRoute.Cell, List<Arc>>();
      for (var arc : arcs) {
        grouped.computeIfAbsent(arc.from(), k -> new ArrayList<>()).add(arc);
      }
      var immutable = new HashMap<MiningRoute.Cell, List<Arc>>(grouped.size() * 2);
      for (var e : grouped.entrySet()) {
        e.getValue()
            .sort(
                Comparator.comparingInt((Arc a) -> a.cost())
                    .thenComparingInt(a -> a.to().y())
                    .thenComparingInt(a -> a.to().x())
                    .thenComparingInt(a -> a.to().z()));
        immutable.put(e.getKey(), List.copyOf(e.getValue()));
      }
      return new KnownGraph(Map.copyOf(immutable));
    }

    public List<Arc> edgesFrom(MiningRoute.Cell cell) {
      return outgoing.getOrDefault(cell, List.of());
    }
  }

  public record Patch(
      MiningRoute.Cell anchor,
      List<MiningRoute.Cell> cells,
      List<MiningRoute.Cell> extraClearance,
      List<MiningRoute.Cell> extraFloors) {
    public Patch(MiningRoute.Cell anchor, List<MiningRoute.Cell> cells) {
      this(anchor, cells, List.of(), List.of());
    }
  }

  public record Cluster(List<MiningRoute.Cell> cells, MiningRoute.Cell anchor) {}

  public record Reserve(
      List<Arc> arcs, Set<MiningRoute.Cell> floors, Set<MiningRoute.Cell> clearance) {
    public Reserve {
      floors = Set.copyOf(floors);
      clearance = Set.copyOf(clearance);
      arcs = List.copyOf(arcs);
      if (!Collections.disjoint(floors, clearance)) {
        throw new IllegalArgumentException("[ROUTE_CONFLICT] Reserve violates floors ∩ clearance = ∅");
      }
      if (clearance.size() > MAX_CLEARANCE) {
        throw new IllegalArgumentException("[ROUTE_TOO_LARGE] Reserve exceeds 384 unique cleared blocks");
      }
    }

    public static Reserve empty() {
      return new Reserve(List.of(), Set.of(), Set.of());
    }

    public static Reserve of(Collection<MiningRoute.Cell> floors, Collection<MiningRoute.Cell> clearance) {
      return new Reserve(List.of(), Set.copyOf(floors), Set.copyOf(clearance));
    }

    public boolean accepts(Arc arc) {
      for (var f : arc.floor()) {
        if (clearance.contains(f)) return false;
      }
      int newClears = 0;
      var seenInArc = new HashSet<MiningRoute.Cell>();
      for (var c : arc.clearance()) {
        if (floors.contains(c)) return false;
        if (!clearance.contains(c) && seenInArc.add(c)) newClears++;
      }
      return Collections.disjoint(Set.copyOf(arc.floor()), seenInArc)
          && clearance.size() + newClears <= MAX_CLEARANCE;
    }

    public Reserve append(Arc arc) {
      var nextArcs = new ArrayList<Arc>(arcs.size() + 1);
      nextArcs.addAll(arcs);
      nextArcs.add(arc);
      var nextFloors = new HashSet<>(floors);
      nextFloors.addAll(arc.floor());
      var nextClearance = new HashSet<>(clearance);
      nextClearance.addAll(arc.clearance());
      return new Reserve(nextArcs, nextFloors, nextClearance);
    }

    public Reserve appendAll(List<Arc> more) {
      var nextArcs = new ArrayList<Arc>(arcs.size() + more.size());
      nextArcs.addAll(arcs);
      var nextFloors = new HashSet<>(floors);
      var nextClearance = new HashSet<>(clearance);
      for (var arc : more) {
        nextArcs.add(arc);
        nextFloors.addAll(arc.floor());
        nextClearance.addAll(arc.clearance());
      }
      return new Reserve(nextArcs, nextFloors, nextClearance);
    }
  }

  private MiningSearch() {}

  /** Clusters 3D ore cells within Chebyshev distance {@code maxGap} deterministically. */
  public static List<Cluster> cluster(Collection<MiningRoute.Cell> cells, int maxGap) {
    if (maxGap < 1) throw new IllegalArgumentException("maxGap must be >= 1");
    if (cells == null || cells.isEmpty()) return List.of();
    var unique = new LinkedHashSet<>(cells);
    var sorted = new ArrayList<>(unique);
    sorted.sort(
        Comparator.comparingInt(MiningRoute.Cell::y)
            .thenComparingInt(MiningRoute.Cell::x)
            .thenComparingInt(MiningRoute.Cell::z));

    var visited = new HashSet<MiningRoute.Cell>(sorted.size() * 2);
    var clusters = new ArrayList<Cluster>();
    var queue = new ArrayDeque<MiningRoute.Cell>();

    for (var seed : sorted) {
      if (!visited.add(seed)) continue;
      var members = new ArrayList<MiningRoute.Cell>();
      queue.add(seed);
      while (!queue.isEmpty()) {
        var cur = queue.removeFirst();
        members.add(cur);
        for (var candidate : sorted) {
          if (visited.contains(candidate)) continue;
          if (chebyshev(cur, candidate) <= maxGap) {
            visited.add(candidate);
            queue.addLast(candidate);
          }
        }
      }
      members.sort(
          Comparator.comparingInt(MiningRoute.Cell::y)
              .thenComparingInt(MiningRoute.Cell::x)
              .thenComparingInt(MiningRoute.Cell::z));
      clusters.add(new Cluster(List.copyOf(members), members.get(0)));
    }
    return List.copyOf(clusters);
  }

  /** Orders clusters using verified graph distances (fallback 3D weighted distance) + 2-opt. */
  public static List<Cluster> visitOrder(
      MiningRoute.Cell start, List<Cluster> clusters, KnownGraph graph) {
    if (clusters == null || clusters.size() <= 1) return clusters == null ? List.of() : List.copyOf(clusters);

    int n = clusters.size();
    double[][] pairCost = new double[n + 1][n + 1];
    MiningRoute.Cell[] anchors = new MiningRoute.Cell[n + 1];
    anchors[0] = start;
    for (int i = 0; i < n; i++) anchors[i + 1] = clusters.get(i).anchor();

    for (int i = 0; i <= n; i++) {
      Map<MiningRoute.Cell, Integer> dists =
          graph == null ? Map.of() : dijkstraDistances(graph, anchors[i]);
      for (int j = 0; j <= n; j++) {
        if (i == j) continue;
        Integer gDist = dists.get(anchors[j]);
        pairCost[i][j] = gDist != null ? gDist.doubleValue() : weightedSpatialCost(anchors[i], anchors[j]);
      }
    }

    // Nearest-neighbor construction from index 0 (start).
    boolean[] used = new boolean[n + 1];
    int[] order = new int[n];
    int cur = 0;
    for (int step = 0; step < n; step++) {
      int best = -1;
      double bestCost = Double.MAX_VALUE;
      for (int j = 1; j <= n; j++) {
        if (!used[j] && pairCost[cur][j] < bestCost) {
          bestCost = pairCost[cur][j];
          best = j;
        }
      }
      used[best] = true;
      order[step] = best;
      cur = best;
    }

    // Exact full-tour directed 2-opt refinement with bounded pass cap.
    double curTotal = tourCost(order, pairCost);
    boolean improved = true;
    int passes = 0;
    while (improved && passes++ < 64) {
      improved = false;
      for (int i = 0; i < n - 1 && !improved; i++) {
        for (int j = i + 1; j < n; j++) {
          reverse(order, i, j);
          double nextTotal = tourCost(order, pairCost);
          if (nextTotal + 1e-9 < curTotal) {
            curTotal = nextTotal;
            improved = true;
            break;
          }
          reverse(order, i, j);
        }
      }
    }

    var result = new ArrayList<Cluster>(n);
    for (int idx : order) result.add(clusters.get(idx - 1));
    return List.copyOf(result);
  }

  private static double tourCost(int[] order, double[][] pairCost) {
    if (order.length == 0) return 0.0;
    double total = pairCost[0][order[0]];
    for (int k = 1; k < order.length; k++) {
      total += pairCost[order[k - 1]][order[k]];
    }
    return total;
  }

  private record ReconnectState(
      MiningRoute.Cell cell,
      int cost,
      List<Arc> path,
      Set<MiningRoute.Cell> floors,
      Set<MiningRoute.Cell> clearance) {}

  private static boolean isDominated(
      ReconnectState candidate, List<ReconnectState> existing, boolean strictOnly) {
    for (var old : existing) {
      if (old == candidate) continue;
      if (!candidate.floors().containsAll(old.floors())
          || !candidate.clearance().containsAll(old.clearance())) continue;
      if (strictOnly ? old.cost() < candidate.cost() : old.cost() <= candidate.cost()) {
        return true;
      }
    }
    return false;
  }

  /** Reconnects from {@code start} to any target in {@code goals} respecting existing reserve. */
  public static Optional<Reserve> reconnect(
      KnownGraph graph,
      MiningRoute.Cell start,
      Set<MiningRoute.Cell> goals,
      Reserve existing) {
    if (graph == null || start == null || goals == null || goals.isEmpty() || existing == null) {
      return Optional.empty();
    }
    if (goals.contains(start)) return Optional.of(existing);

    var pq =
        new PriorityQueue<ReconnectState>(
            Comparator.comparingInt(ReconnectState::cost)
                .thenComparingInt(s -> s.clearance().size())
                .thenComparingInt(s -> s.path().size()));
    var frontier = new HashMap<MiningRoute.Cell, List<ReconnectState>>();
    var startState =
        new ReconnectState(start, 0, List.of(), existing.floors(), existing.clearance());
    pq.add(startState);
    frontier.put(start, new ArrayList<>(List.of(startState)));

    int expanded = 0;
    while (!pq.isEmpty() && expanded++ < 4096) {
      var cur = pq.poll();
      if (goals.contains(cur.cell())) {
        var combinedArcs = new ArrayList<Arc>(existing.arcs().size() + cur.path().size());
        combinedArcs.addAll(existing.arcs());
        combinedArcs.addAll(cur.path());
        return Optional.of(new Reserve(combinedArcs, cur.floors(), cur.clearance()));
      }
      if (isDominated(cur, frontier.getOrDefault(cur.cell(), List.of()), true)) continue;

      for (var arc : graph.edgesFrom(cur.cell())) {
        if (!Collections.disjoint(cur.clearance(), arc.floor())) continue;
        if (!Collections.disjoint(cur.floors(), arc.clearance())) continue;
        var arcFloors = Set.copyOf(arc.floor());
        var arcClears = Set.copyOf(arc.clearance());
        if (!Collections.disjoint(arcFloors, arcClears)) continue;

        var nextFloors = new HashSet<>(cur.floors());
        nextFloors.addAll(arcFloors);
        var nextClearance = new HashSet<>(cur.clearance());
        nextClearance.addAll(arcClears);
        if (!Collections.disjoint(nextFloors, nextClearance)) continue;
        if (nextClearance.size() > MAX_CLEARANCE) continue;

        int nextCost = cur.cost() + arc.cost();
        var nextPath = new ArrayList<Arc>(cur.path().size() + 1);
        nextPath.addAll(cur.path());
        nextPath.add(arc);
        var nextState =
            new ReconnectState(
                arc.to(),
                nextCost,
                nextPath,
                Collections.unmodifiableSet(nextFloors),
                Collections.unmodifiableSet(nextClearance));
        var cellFrontier = frontier.computeIfAbsent(arc.to(), k -> new ArrayList<>());
        if (isDominated(nextState, cellFrontier, false)) continue;
        cellFrontier.removeIf(
            old ->
                old.cost() >= nextCost
                    && old.floors().containsAll(nextState.floors())
                    && old.clearance().containsAll(nextState.clearance()));
        if (cellFrontier.size() >= 8) continue;
        cellFrontier.add(nextState);
        pq.add(nextState);
      }
    }
    return Optional.empty();
  }

  /**
   * Computes shortest verified return from {@code current} to {@code home}, prioritizing arcs with
   * zero unverified clearance (already cleared or pass-through) and always enforcing
   * {@code floors ∩ clearance = ∅} and {@code <= 384} unique cleared blocks.
   */
  public static Optional<Reserve> shortestVerifiedReturn(
      KnownGraph graph,
      MiningRoute.Cell current,
      MiningRoute.Cell home,
      Reserve planned) {
    if (graph == null || current == null || home == null || planned == null) {
      return Optional.empty();
    }
    if (current.equals(home)) return Optional.of(planned);

    // Phase 1: strictly reuse verified-clear / empty-clearance arcs first.
    var cleanEdges = new ArrayList<Arc>();
    for (var list : graph.outgoing().values()) {
      for (var arc : list) {
        if (planned.clearance().containsAll(arc.clearance())) {
          cleanEdges.add(arc);
        }
      }
    }
    var zeroClearResult = reconnect(KnownGraph.of(cleanEdges), current, Set.of(home), planned);
    if (zeroClearResult.isPresent()) return zeroClearResult;

    // Phase 2: fallback allowing safe incremental clearance within existing reserve invariants.
    return reconnect(graph, current, Set.of(home), planned);
  }

  private static int chebyshev(MiningRoute.Cell a, MiningRoute.Cell b) {
    return Math.max(
        Math.abs(a.x() - b.x()), Math.max(Math.abs(a.y() - b.y()), Math.abs(a.z() - b.z())));
  }

  private static double weightedSpatialCost(MiningRoute.Cell a, MiningRoute.Cell b) {
    return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) + Math.abs(a.y() - b.y()) * 2.0;
  }

  private static Map<MiningRoute.Cell, Integer> dijkstraDistances(
      KnownGraph graph, MiningRoute.Cell start) {
    var dist = new HashMap<MiningRoute.Cell, Integer>();
    record Node(MiningRoute.Cell cell, int cost) {}
    var pq = new PriorityQueue<Node>(Comparator.comparingInt(Node::cost));
    dist.put(start, 0);
    pq.add(new Node(start, 0));
    while (!pq.isEmpty()) {
      var cur = pq.poll();
      if (cur.cost() > dist.getOrDefault(cur.cell(), Integer.MAX_VALUE)) continue;
      for (var arc : graph.edgesFrom(cur.cell())) {
        int next = cur.cost() + arc.cost();
        if (next < dist.getOrDefault(arc.to(), Integer.MAX_VALUE)) {
          dist.put(arc.to(), next);
          pq.add(new Node(arc.to(), next));
        }
      }
    }
    return dist;
  }

  private static void reverse(int[] arr, int i, int j) {
    while (i < j) {
      int tmp = arr[i];
      arr[i] = arr[j];
      arr[j] = tmp;
      i++;
      j--;
    }
  }
}
