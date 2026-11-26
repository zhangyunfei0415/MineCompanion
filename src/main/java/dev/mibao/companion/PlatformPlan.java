package dev.mibao.companion;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.ToDoubleBiFunction;

/** Pure bounded horizontal geometry. No world writes or optimistic walkable cells. */
public final class PlatformPlan {
  public record Cell(int x, int z) {
    public Cell offset(int x, int z) {
      return new Cell(this.x + x, this.z + z);
    }

    public List<Cell> neighbors() {
      return List.of(offset(1, 0), offset(-1, 0), offset(0, 1), offset(0, -1));
    }
  }

  public record Area(int x, int y, int z, int width, int depth) {
    public List<Cell> cells() {
      var out = new ArrayList<Cell>();
      for (int row = 0; row < depth; row++)
        for (int col = 0; col < width; col++)
          out.add(new Cell(x + (row % 2 == 0 ? col : width - 1 - col), z + row));
      return out;
    }
  }

  public static Area rectangle(int x, int y, int z, int width, int depth) {
    if (width < 1 || width > 32 || depth < 1 || depth > 32)
      throw new IllegalArgumentException("[BUILD_BOUNDS] 平台每边1..32格，单批最多1024格");
    return new Area(x, y, z, width, depth);
  }

  public static Area bridge(int x, int y, int z, String direction, int length, int width) {
    if (length < 1 || length > 64 || width < 1 || width > 5)
      throw new IllegalArgumentException("[BUILD_BOUNDS] 桥长1..64格、宽1..5格");
    int dx = 0, dz = 0;
    switch (direction) {
      case "north" -> dz = -1;
      case "south" -> dz = 1;
      case "east" -> dx = 1;
      case "west" -> dx = -1;
      default ->
          throw new IllegalArgumentException("[ARGUMENT_INVALID] direction为north/south/east/west");
    }
    int sx = -dz, sz = dx, lo = -(width / 2), hi = lo + width - 1;
    int a = x + sx * lo, b = x + dx * (length - 1) + sx * hi;
    int c = z + sz * lo, d = z + dz * (length - 1) + sz * hi;
    return new Area(Math.min(a, b), y, Math.min(c, d), Math.abs(a - b) + 1, Math.abs(c - d) + 1);
  }

  public static Map<Cell, Cell> reachable(Cell start, Predicate<Cell> allowed, int budget) {
    var parents = new LinkedHashMap<Cell, Cell>();
    var queue = new ArrayDeque<Cell>();
    if (!allowed.test(start)) return parents;
    parents.put(start, start);
    queue.add(start);
    while (!queue.isEmpty() && parents.size() < budget) {
      var current = queue.removeFirst();
      for (var next : current.neighbors()) {
        if (parents.size() >= budget) break;
        if (!parents.containsKey(next) && allowed.test(next)) {
          parents.put(next, current);
          queue.addLast(next);
        }
      }
    }
    return parents;
  }

  public static List<Cell> path(Map<Cell, Cell> parents, Cell end) {
    if (!parents.containsKey(end)) return List.of();
    var out = new ArrayList<Cell>();
    var at = end;
    for (int i = 0; i <= parents.size(); i++) {
      out.add(at);
      var previous = parents.get(at);
      if (previous.equals(at)) {
        Collections.reverse(out);
        return out;
      }
      at = previous;
    }
    throw new IllegalArgumentException("[BUILD_PATH] 路径父链异常");
  }

  public static double overlap(double x, double z, double half, Cell cell) {
    return Math.max(0, Math.min(x + half, cell.x + 1) - Math.max(x - half, cell.x))
        * Math.max(0, Math.min(z + half, cell.z + 1) - Math.max(z - half, cell.z));
  }

  @FunctionalInterface
  public interface Floor {
    boolean walkable(Cell cell);
  }

  public record Layout(Area area, List<Cell> cells) {
    public static Layout of(Area area) {
      return new Layout(area, List.copyOf(new LinkedHashSet<>(area.cells())));
    }
  }

  public record WalkTree(Cell start, Map<Cell, Cell> parents, Map<Cell, Integer> depths) {
    public boolean contains(Cell cell) {
      return parents.containsKey(cell);
    }

    public int distance(Cell cell) {
      return depths.getOrDefault(cell, -1);
    }

    public List<Cell> path(Cell end) {
      return PlatformPlan.path(parents, end);
    }
  }

  public record Placement(
      Cell target, Cell anchor, int supportNeighbors, int routeSteps, double cost) {}

  private record TargetAnchor(Cell target, Cell anchor) {}

  /** Single-BFS reachability tree recording both parent pointers and exact hop distances. */
  public static WalkTree walkTree(Cell start, Predicate<Cell> allowed, int budget) {
    var parents = new LinkedHashMap<Cell, Cell>();
    var depths = new LinkedHashMap<Cell, Integer>();
    var queue = new ArrayDeque<Cell>();
    if (budget > 0 && allowed.test(start)) {
      parents.put(start, start);
      depths.put(start, 0);
      queue.add(start);
      while (!queue.isEmpty() && parents.size() < budget) {
        var current = queue.removeFirst();
        int nextDepth = depths.get(current) + 1;
        for (var next : current.neighbors()) {
          if (parents.size() >= budget) break;
          if (!parents.containsKey(next) && allowed.test(next)) {
            parents.put(next, current);
            depths.put(next, nextDepth);
            queue.addLast(next);
          }
        }
      }
    }
    return new WalkTree(
        start, Collections.unmodifiableMap(parents), Collections.unmodifiableMap(depths));
  }

  /**
   * Chooses the best next placement using single-BFS depth lookup, support-first ordering (cells
   * with more existing walkable neighbors before dead-end cantilevers), and {@code (target, anchor)}
   * deduplication.
   */
  public static Optional<Placement> nextPlacement(
      Layout layout,
      WalkTree tree,
      Floor floor,
      Predicate<Cell> openAir,
      BiPredicate<Cell, Cell> validStance,
      ToDoubleBiFunction<Cell, Cell> tieCost) {
    Placement best = null;
    for (var target : layout.cells()) {
      if (!openAir.test(target)) continue;
      Cell n0 = target.offset(1, 0), n1 = target.offset(-1, 0), n2 = target.offset(0, 1), n3 = target.offset(0, -1);
      boolean w0 = floor.walkable(n0), w1 = floor.walkable(n1), w2 = floor.walkable(n2), w3 = floor.walkable(n3);
      int supportNeighbors = (w0 ? 1 : 0) + (w1 ? 1 : 0) + (w2 ? 1 : 0) + (w3 ? 1 : 0);
      if (supportNeighbors == 0) continue;

      for (int k = 0; k < 4; k++) {
        boolean walkableAnchor = switch (k) { case 0 -> w0; case 1 -> w1; case 2 -> w2; default -> w3; };
        if (!walkableAnchor) continue;
        Cell anchor = switch (k) { case 0 -> n0; case 1 -> n1; case 2 -> n2; default -> n3; };
        if (!tree.contains(anchor) || !validStance.test(target, anchor)) continue;
        int steps = tree.distance(anchor);
        double tie = tieCost.applyAsDouble(target, anchor);
        if (best == null || betterPlacement(supportNeighbors, steps, tie, best)) {
          best = new Placement(target, anchor, supportNeighbors, steps, tie);
        }
      }
    }
    return Optional.ofNullable(best);
  }

  private static boolean betterPlacement(
      int supportNeighbors, int steps, double tie, Placement current) {
    if (supportNeighbors != current.supportNeighbors()) {
      return supportNeighbors > current.supportNeighbors();
    }
    if (steps != current.routeSteps()) {
      return steps < current.routeSteps();
    }
    return tie < current.cost();
  }
}
