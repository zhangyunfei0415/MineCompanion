package dev.mibao.companion;

import java.util.*;
import java.util.function.Predicate;

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
}
