package dev.mibao.companion;

import java.util.*;
import java.util.function.Predicate;

/** Conservative nearby pickup stance, not an entity removal or inventory receipt. */
public final class PickupGeometry {
  public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

  public static boolean touches(
      double x, double y, double z, double width, double height, Box item) {
    double reach = width / 2 + 1;
    return x - reach < item.maxX
        && x + reach > item.minX
        && z - reach < item.maxZ
        && z + reach > item.minZ
        && y - .5 < item.maxY
        && y + height + .5 > item.minY;
  }

  public static Set<GridSearch.Cell> stances(
      Box item, double width, double height, Predicate<GridSearch.Cell> safe) {
    return stances(item, width, height, c -> c.y(), safe);
  }

  public static Set<GridSearch.Cell> stances(
      Box item,
      double width,
      double height,
      java.util.function.ToDoubleFunction<GridSearch.Cell> surface,
      Predicate<GridSearch.Cell> safe) {
    Set<GridSearch.Cell> out = new HashSet<>();
    int x = (int) Math.floor((item.minX + item.maxX) / 2),
        y = (int) Math.floor(item.minY),
        z = (int) Math.floor((item.minZ + item.maxZ) / 2);
    for (int dx = -2; dx <= 2; dx++)
      for (int dz = -2; dz <= 2; dz++)
        for (int dy = -2; dy <= 1; dy++) {
          var p = new GridSearch.Cell(x + dx, y + dy, z + dz);
          double feet = surface.applyAsDouble(p);
          if (Double.isFinite(feet)
              && touches(p.x() + .5, feet, p.z() + .5, width, height, item)
              && safe.test(p)) out.add(p);
        }
    return Set.copyOf(out);
  }

  public static boolean inArea(
      double cx, double cy, double cz, double x, double y, double z, int radius) {
    return (x - cx) * (x - cx) + (z - cz) * (z - cz) <= radius * (double) radius
        && Math.abs(y - cy) <= radius;
  }

  public static boolean nearBroken(double bx, double by, double bz, double x, double y, double z) {
    return (x - bx) * (x - bx) + (z - bz) * (z - bz) <= 16 && y >= by - 12 && y <= by + 3;
  }
}
