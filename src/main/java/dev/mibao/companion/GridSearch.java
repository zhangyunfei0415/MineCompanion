package dev.mibao.companion;

import java.util.*;
import java.util.function.*;

/**
 * Tick-budgeted A* core independent of Minecraft. Supports both legacy object-based {@link Cell}
 * graphs and zero-allocation packed {@code long}-key search via {@link #weightedPacked}.
 */
public final class GridSearch {
  public record Cell(int x, int y, int z) {}

  public record Step(Cell cell, double cost) {}

  public enum State {
    SEARCHING,
    FOUND,
    EXHAUSTED,
    CANCELLED
  }

  @FunctionalInterface
  public interface KeyGoal {
    boolean isGoal(long key);
  }

  @FunctionalInterface
  public interface KeyHeuristic {
    double estimate(long key);
  }

  @FunctionalInterface
  public interface EdgeSink {
    void accept(long toKey, double stepCost, byte action);
  }

  @FunctionalInterface
  public interface KeyNeighbours {
    void expand(long fromKey, EdgeSink sink);
  }

  @FunctionalInterface
  public interface KeyCodec {
    Cell decode(long key);

    static KeyCodec relative(int originX, int originY, int originZ) {
      return key ->
          new Cell(
              originX + unpackRelX(key), originY + unpackRelY(key), originZ + unpackRelZ(key));
    }

    static KeyCodec absolute() {
      return key -> new Cell(unpackX(key), unpackY(key), unpackZ(key));
    }
  }

  /**
   * Reusable open-addressing + indexed binary min-heap workspace for zero-GC packed A* searches.
   */
  public static final class SearchWorkspace {
    private static final int INITIAL_CAP = 1024;
    private long[] tableKeys = new long[2048];
    private int[] tableIds = new int[2048];
    private int[] tableGen = new int[2048];
    private int generation = 1;

    private long[] nodeKeys = new long[INITIAL_CAP];
    private double[] gScores = new double[INITIAL_CAP];
    private double[] fScores = new double[INITIAL_CAP];
    private int[] parents = new int[INITIAL_CAP];
    private byte[] actions = new byte[INITIAL_CAP];
    private boolean[] closed = new boolean[INITIAL_CAP];
    private int[] heap = new int[INITIAL_CAP];
    private int[] heapPos = new int[INITIAL_CAP];

    private int nodeCount;
    private int heapSize;
    private int activeToken;

    int reset(int expectedMaxNodes) {
      generation++;
      if (generation == Integer.MAX_VALUE) {
        Arrays.fill(tableGen, 0);
        generation = 1;
      }
      int minTable = Math.max(2048, Integer.highestOneBit(Math.max(1024, expectedMaxNodes * 2)) << 2);
      if (tableKeys.length < minTable) {
        tableKeys = new long[minTable];
        tableIds = new int[minTable];
        tableGen = new int[minTable];
      }
      nodeCount = 0;
      heapSize = 0;
      return ++activeToken;
    }

    private void ensureNodeCapacity(int needed) {
      if (needed <= nodeKeys.length) return;
      int cap = nodeKeys.length;
      while (cap < needed) cap <<= 1;
      nodeKeys = Arrays.copyOf(nodeKeys, cap);
      gScores = Arrays.copyOf(gScores, cap);
      fScores = Arrays.copyOf(fScores, cap);
      parents = Arrays.copyOf(parents, cap);
      actions = Arrays.copyOf(actions, cap);
      closed = Arrays.copyOf(closed, cap);
      heap = Arrays.copyOf(heap, cap);
      heapPos = Arrays.copyOf(heapPos, cap);
    }

    private void ensureTableLoad() {
      if ((nodeCount + 1) * 2 <= tableKeys.length) return;
      int oldLen = tableKeys.length;
      long[] oldKeys = tableKeys;
      int[] oldIds = tableIds;
      int[] oldGen = tableGen;
      int newLen = oldLen << 1;
      tableKeys = new long[newLen];
      tableIds = new int[newLen];
      tableGen = new int[newLen];
      int mask = newLen - 1;
      for (int i = 0; i < oldLen; i++) {
        if (oldGen[i] == generation) {
          long k = oldKeys[i];
          int slot = mix64(k) & mask;
          while (tableGen[slot] == generation) slot = (slot + 1) & mask;
          tableGen[slot] = generation;
          tableKeys[slot] = k;
          tableIds[slot] = oldIds[i];
        }
      }
    }

    int findOrInsert(long key) {
      ensureTableLoad();
      int mask = tableKeys.length - 1;
      int slot = mix64(key) & mask;
      while (tableGen[slot] == generation) {
        if (tableKeys[slot] == key) return tableIds[slot];
        slot = (slot + 1) & mask;
      }
      int id = nodeCount++;
      ensureNodeCapacity(nodeCount);
      tableGen[slot] = generation;
      tableKeys[slot] = key;
      tableIds[slot] = id;
      nodeKeys[id] = key;
      gScores[id] = Double.POSITIVE_INFINITY;
      fScores[id] = Double.POSITIVE_INFINITY;
      parents[id] = -1;
      actions[id] = 0;
      closed[id] = false;
      heapPos[id] = -1;
      return id;
    }

    void pushOrUpdate(int id, double f) {
      fScores[id] = f;
      int pos = heapPos[id];
      if (pos < 0) {
        pos = heapSize++;
        heap[pos] = id;
        heapPos[id] = pos;
      }
      siftUp(pos);
    }

    int pollMin() {
      int top = heap[0];
      heapPos[top] = -1;
      int last = heap[--heapSize];
      if (heapSize > 0) {
        heap[0] = last;
        heapPos[last] = 0;
        siftDown(0);
      }
      closed[top] = true;
      return top;
    }

    private void siftUp(int pos) {
      int id = heap[pos];
      double f = fScores[id];
      while (pos > 0) {
        int parent = (pos - 1) >>> 1;
        int pId = heap[parent];
        if (f >= fScores[pId]) break;
        heap[pos] = pId;
        heapPos[pId] = pos;
        pos = parent;
      }
      heap[pos] = id;
      heapPos[id] = pos;
    }

    private void siftDown(int pos) {
      int id = heap[pos];
      double f = fScores[id];
      int half = heapSize >>> 1;
      while (pos < half) {
        int child = (pos << 1) + 1;
        int right = child + 1;
        if (right < heapSize && fScores[heap[right]] < fScores[heap[child]]) {
          child = right;
        }
        int cId = heap[child];
        if (f <= fScores[cId]) break;
        heap[pos] = cId;
        heapPos[cId] = pos;
        pos = child;
      }
      heap[pos] = id;
      heapPos[id] = pos;
    }

    private static int mix64(long z) {
      z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
      z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
      return (int) (z ^ (z >>> 31));
    }
  }

  /** Packs signed world coordinates into 64 bits: X(21 bits), Z(21 bits), Y(22 bits). */
  public static long pack(int x, int y, int z) {
    return ((long) (x & 0x1FFFFF))
        | (((long) (z & 0x1FFFFF)) << 21)
        | (((long) (y & 0x3FFFFF)) << 42);
  }

  public static int unpackX(long key) {
    int raw = (int) (key & 0x1FFFFFL);
    return (raw << 11) >> 11;
  }

  public static int unpackZ(long key) {
    int raw = (int) ((key >>> 21) & 0x1FFFFFL);
    return (raw << 11) >> 11;
  }

  public static int unpackY(long key) {
    int raw = (int) ((key >>> 42) & 0x3FFFFFL);
    return (raw << 10) >> 10;
  }

  /** Packs relative offsets in [-512, 511] X/Z and [-512, 511] Y into 30 bits. */
  public static long packRelative(int dx, int dy, int dz) {
    return ((long) ((dx + 512) & 0x3FF))
        | (((long) ((dz + 512) & 0x3FF)) << 10)
        | (((long) ((dy + 512) & 0x3FF)) << 20);
  }

  public static int unpackRelX(long key) {
    return ((int) (key & 0x3FFL)) - 512;
  }

  public static int unpackRelZ(long key) {
    return ((int) ((key >>> 10) & 0x3FFL)) - 512;
  }

  public static int unpackRelY(long key) {
    return ((int) ((key >>> 20) & 0x3FFL)) - 512;
  }

  private record Node(Cell cell, double g, double f) {}

  private final boolean packedMode;
  private final PriorityQueue<Node> open;
  private final Map<Cell, Double> costs;
  private final Map<Cell, Cell> parent;
  private final Cell start;
  private final Predicate<Cell> goal;
  private final Function<Cell, List<Step>> weightedNeighbours;
  private final ToDoubleFunction<Cell> heuristic;

  // Packed search fields
  private final long startKey;
  private final KeyGoal keyGoal;
  private final KeyNeighbours keyNeighbours;
  private final KeyHeuristic keyHeuristic;
  private final KeyCodec keyCodec;
  private final SearchWorkspace workspace;
  private final int workspaceToken;
  private final EdgeSink edgeSink;
  private long currentExpandKey;
  private int currentExpandId;
  private double currentExpandG;
  private long[] packedPathKeys = new long[0];
  private byte[] packedPathActions = new byte[0];

  private final int totalBudget;
  private int expanded;
  private State state = State.SEARCHING;
  private List<Cell> path = List.of();
  private boolean pathMaterialized;

  public GridSearch(
      Cell start,
      Predicate<Cell> goal,
      Function<Cell, List<Cell>> neighbours,
      ToDoubleFunction<Cell> heuristic,
      int totalBudget) {
    this(
        start,
        goal,
        c -> {
          List<Cell> list = neighbours.apply(c);
          List<Step> steps = new ArrayList<>(list.size());
          for (Cell n : list) steps.add(new Step(n, 1.0 + Math.abs(n.y() - c.y()) * .5));
          return steps;
        },
        heuristic,
        totalBudget,
        true);
  }

  private GridSearch(
      Cell start,
      Predicate<Cell> goal,
      Function<Cell, List<Step>> weightedNeighbours,
      ToDoubleFunction<Cell> heuristic,
      int totalBudget,
      boolean weightedMarker) {
    this.packedMode = false;
    this.open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
    this.costs = new HashMap<>();
    this.parent = new HashMap<>();
    this.start = start;
    this.goal = goal;
    this.weightedNeighbours = weightedNeighbours;
    this.heuristic = heuristic;
    this.totalBudget = totalBudget;
    this.startKey = 0L;
    this.keyGoal = null;
    this.keyNeighbours = null;
    this.keyHeuristic = null;
    this.keyCodec = null;
    this.workspace = null;
    this.workspaceToken = 0;
    this.edgeSink = null;
    costs.put(start, 0.0);
    open.add(new Node(start, 0, heuristic.applyAsDouble(start)));
    if (goal.test(start)) state = State.FOUND;
  }

  private GridSearch(
      long startKey,
      KeyGoal keyGoal,
      KeyNeighbours keyNeighbours,
      KeyHeuristic keyHeuristic,
      KeyCodec keyCodec,
      int totalBudget,
      SearchWorkspace workspace) {
    this.packedMode = true;
    this.open = null;
    this.costs = null;
    this.parent = null;
    this.start = null;
    this.goal = null;
    this.weightedNeighbours = null;
    this.heuristic = null;
    this.startKey = startKey;
    this.keyGoal = keyGoal;
    this.keyNeighbours = keyNeighbours;
    this.keyHeuristic = keyHeuristic;
    this.keyCodec = keyCodec != null ? keyCodec : KeyCodec.absolute();
    this.totalBudget = totalBudget;
    this.workspace = workspace != null ? workspace : new SearchWorkspace();
    this.workspaceToken = this.workspace.reset(Math.min(Math.max(256, totalBudget), 65536));
    int rootId = this.workspace.findOrInsert(startKey);
    this.workspace.gScores[rootId] = 0.0;
    this.workspace.parents[rootId] = -1;
    this.workspace.pushOrUpdate(rootId, keyHeuristic.estimate(startKey));
    if (keyGoal.isGoal(startKey)) {
      this.state = State.FOUND;
    }
    this.edgeSink =
        (toKey, stepCost, action) -> {
          int nextId = this.workspace.findOrInsert(toKey);
          double nextG = currentExpandG + Math.max(0.25, stepCost);
          if (nextG + 1e-12 < this.workspace.gScores[nextId]) {
            this.workspace.gScores[nextId] = nextG;
            this.workspace.parents[nextId] = currentExpandId;
            this.workspace.actions[nextId] = action;
            this.workspace.closed[nextId] = false;
            this.workspace.pushOrUpdate(nextId, nextG + this.keyHeuristic.estimate(toKey));
          }
        };
  }

  public static GridSearch weighted(
      Cell start,
      Predicate<Cell> goal,
      Function<Cell, List<Step>> weightedNeighbours,
      ToDoubleFunction<Cell> heuristic,
      int totalBudget) {
    return new GridSearch(start, goal, weightedNeighbours, heuristic, totalBudget, true);
  }

  public static GridSearch weightedPacked(
      long startKey,
      KeyGoal goal,
      KeyNeighbours neighbours,
      KeyHeuristic heuristic,
      KeyCodec codec,
      int totalBudget,
      SearchWorkspace workspace) {
    return new GridSearch(startKey, goal, neighbours, heuristic, codec, totalBudget, workspace);
  }

  public State state() {
    return state;
  }

  public int pathLength() {
    return packedMode ? packedPathKeys.length : path.size();
  }

  public long pathKey(int index) {
    if (packedMode) return packedPathKeys[index];
    Cell c = path.get(index);
    return pack(c.x(), c.y(), c.z());
  }

  public byte pathAction(int index) {
    return packedMode ? packedPathActions[index] : 0;
  }

  public List<Cell> path() {
    if (!packedMode || pathMaterialized) return path;
    var out = new ArrayList<Cell>(packedPathKeys.length);
    for (long k : packedPathKeys) out.add(keyCodec.decode(k));
    path = List.copyOf(out);
    pathMaterialized = true;
    return path;
  }

  public int expanded() {
    return expanded;
  }

  public void cancel() {
    state = State.CANCELLED;
    if (open != null) open.clear();
    if (workspace != null && workspace.activeToken == workspaceToken) {
      workspace.heapSize = 0;
    }
  }

  public State advance(int nodes, long nanos) {
    if (state != State.SEARCHING) return state;
    if (packedMode) return advancePacked(nodes, nanos);
    long begin = System.nanoTime();
    int popped = 0;
    while (!open.isEmpty() && popped++ < nodes) {
      if (expanded >= totalBudget) {
        state = State.EXHAUSTED;
        return state;
      }
      Node n = open.poll();
      if (n.g > costs.getOrDefault(n.cell, Double.MAX_VALUE)) continue;
      if (goal.test(n.cell)) {
        List<Cell> result = new ArrayList<>();
        for (Cell c = n.cell; !c.equals(start); c = parent.get(c)) result.add(c);
        Collections.reverse(result);
        path = List.copyOf(result);
        state = State.FOUND;
        return state;
      }
      expanded++;
      for (Step step : weightedNeighbours.apply(n.cell)) {
        Cell next = step.cell();
        double stepCost = Math.max(0.25, step.cost());
        double g = n.g + stepCost + Math.abs(next.y() - n.cell.y()) * .35;
        if (g < costs.getOrDefault(next, Double.MAX_VALUE)) {
          costs.put(next, g);
          parent.put(next, n.cell);
          open.add(new Node(next, g, g + heuristic.applyAsDouble(next)));
        }
      }
      if (System.nanoTime() - begin >= nanos) break;
    }
    if (open.isEmpty()) state = State.EXHAUSTED;
    return state;
  }

  private State advancePacked(int nodes, long nanos) {
    if (workspace.activeToken != workspaceToken) {
      state = State.CANCELLED;
      return state;
    }
    long begin = System.nanoTime();
    int popped = 0;
    while (workspace.heapSize > 0 && popped++ < nodes) {
      if (expanded >= totalBudget) {
        state = State.EXHAUSTED;
        return state;
      }
      int id = workspace.pollMin();
      long key = workspace.nodeKeys[id];
      if (keyGoal.isGoal(key)) {
        materializePackedPath(id);
        state = State.FOUND;
        return state;
      }
      expanded++;
      currentExpandId = id;
      currentExpandKey = key;
      currentExpandG = workspace.gScores[id];
      keyNeighbours.expand(key, edgeSink);
      if (System.nanoTime() - begin >= nanos) break;
    }
    if (workspace.heapSize == 0) state = State.EXHAUSTED;
    return state;
  }

  private void materializePackedPath(int goalId) {
    int count = 0;
    for (int cur = goalId; workspace.parents[cur] >= 0; cur = workspace.parents[cur]) count++;
    packedPathKeys = new long[count];
    packedPathActions = new byte[count];
    int idx = count - 1;
    for (int cur = goalId; workspace.parents[cur] >= 0; cur = workspace.parents[cur]) {
      packedPathKeys[idx] = workspace.nodeKeys[cur];
      packedPathActions[idx] = workspace.actions[cur];
      idx--;
    }
    pathMaterialized = false;
  }
}
