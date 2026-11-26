package dev.mibao.companion;

import java.util.*;

/**
 * Pure-Java face-local coordinate math mapping visual (u, v) or semantic sub-slots onto block
 * bounding boxes across all six cardinal faces. Zero {@code net.minecraft.*} imports.
 */
public final class FaceHitMath {
  public record UV(double u, double v) {}

  public record LocalPoint(double dx, double dy, double dz) {}

  public record Box(
      double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

  public static final Set<String> SUB_SLOTS =
      Set.of(
          "center",
          "top",
          "bottom",
          "left",
          "right",
          "top_left",
          "top_right",
          "bottom_left",
          "bottom_right");

  private FaceHitMath() {}

  public static float wrapDegrees(float deg) {
    float f = deg % 360.0f;
    if (f >= 180.0f) f -= 360.0f;
    if (f < -180.0f) f += 360.0f;
    return f;
  }

  public static boolean yawConverged(float currentYaw, float prevYaw, float desiredYaw) {
    return Math.abs(wrapDegrees(currentYaw - desiredYaw)) < 1.0f
        && Math.abs(wrapDegrees(currentYaw - prevYaw)) < 0.5f;
  }

  public static boolean hitPointInLookSector(
      double eyeX, double eyeZ, double hitX, double hitZ, String requiredLookSide) {
    if (requiredLookSide == null || requiredLookSide.isBlank()) return true;
    double dx = hitX - eyeX;
    double dz = hitZ - eyeZ;
    if (Math.hypot(dx, dz) < 0.15) return false;
    return switch (requiredLookSide) {
      case "north" -> -dz >= Math.abs(dx) + 0.05;
      case "south" -> dz >= Math.abs(dx) + 0.05;
      case "west" -> -dx >= Math.abs(dz) + 0.05;
      case "east" -> dx >= Math.abs(dz) + 0.05;
      default -> true;
    };
  }

  public static boolean hasExplicitSubHit(Double hitU, Double hitV, String subSlot) {
    return hitU != null || hitV != null || (subSlot != null && !subSlot.isBlank());
  }

  public static UV resolveUV(Double hitU, Double hitV, String subSlot) {
    UV base = fromSubSlot(subSlot);
    double u = hitU != null ? validateUnit("hit_u", hitU) : base.u();
    double v = hitV != null ? validateUnit("hit_v", hitV) : base.v();
    return new UV(clampEdge(u), clampEdge(v));
  }

  public static UV fromSubSlot(String subSlot) {
    if (subSlot == null || subSlot.isBlank()) return new UV(0.5, 0.5);
    return switch (subSlot.strip().toLowerCase(Locale.ROOT)) {
      case "center" -> new UV(0.5, 0.5);
      case "top", "upper" -> new UV(0.5, 0.78);
      case "bottom", "lower" -> new UV(0.5, 0.22);
      case "left" -> new UV(0.22, 0.5);
      case "right" -> new UV(0.78, 0.5);
      case "top_left", "upper_left" -> new UV(0.25, 0.75);
      case "top_right", "upper_right" -> new UV(0.75, 0.75);
      case "bottom_left", "lower_left" -> new UV(0.25, 0.25);
      case "bottom_right", "lower_right" -> new UV(0.75, 0.25);
      default -> throw new IllegalArgumentException(
          "[INVALID_ARGUMENT] sub_slot须为 center/top/bottom/left/right/top_left/top_right/bottom_left/bottom_right");
    };
  }

  private static double validateUnit(String name, double val) {
    if (!Double.isFinite(val) || val < 0.0 || val > 1.0)
      throw new IllegalArgumentException("[INVALID_ARGUMENT] " + name + " 须在 0.0..1.0 之间");
    return val;
  }

  private static double clampEdge(double v) {
    return Math.max(0.02, Math.min(0.98, v));
  }

  /**
   * Converts visual face coordinates (u left->right, v bottom->top when looking directly at the
   * named block face) into block-local offsets inside the provided AABB bounds.
   */
  public static LocalPoint toLocal(
      String faceName,
      double u,
      double v,
      double minX,
      double minY,
      double minZ,
      double maxX,
      double maxY,
      double maxZ) {
    if (faceName == null || faceName.isBlank())
      throw new IllegalArgumentException("[INVALID_ARGUMENT] face 不能为空");
    double cu = clampEdge(u);
    double cv = clampEdge(v);
    double sx = Math.max(1e-4, maxX - minX);
    double sy = Math.max(1e-4, maxY - minY);
    double sz = Math.max(1e-4, maxZ - minZ);
    double eps = Math.min(0.0001, Math.min(sx, Math.min(sy, sz)) * 0.05);
    String f = faceName.strip().toLowerCase(Locale.ROOT);
    if (f.equals("top")) f = "up";
    return switch (f) {
      case "north" -> new LocalPoint(maxX - sx * cu, minY + sy * cv, minZ + eps);
      case "south" -> new LocalPoint(minX + sx * cu, minY + sy * cv, maxZ - eps);
      case "east" -> new LocalPoint(maxX - eps, minY + sy * cv, maxZ - sz * cu);
      case "west" -> new LocalPoint(minX + eps, minY + sy * cv, minZ + sz * cu);
      case "up" -> new LocalPoint(minX + sx * cu, maxY - eps, maxZ - sz * cv);
      case "down" -> new LocalPoint(minX + sx * cu, minY + eps, minZ + sz * cv);
      default -> throw new IllegalArgumentException(
          "[INVALID_ARGUMENT] face须为 up/down/north/south/east/west");
    };
  }

  /**
   * Resolves a visual (u, v) coordinate over the outer bounds of a multi-box VoxelShape onto the
   * frontmost sub-box covering that tangential point (e.g. StorageDrawers recessed front face inside
   * its outer trim frame).
   */
  public static LocalPoint resolveFaceTarget(String faceName, double u, double v, List<Box> boxes) {
    if (boxes == null || boxes.isEmpty()) return toLocal(faceName, u, v, 0, 0, 0, 1, 1, 1);
    Box bounds = union(boxes);
    LocalPoint outer =
        toLocal(
            faceName,
            u,
            v,
            bounds.minX(),
            bounds.minY(),
            bounds.minZ(),
            bounds.maxX(),
            bounds.maxY(),
            bounds.maxZ());
    String f = normalizeFace(faceName);
    double tol = 1e-4;
    Box best = null;
    for (Box b : boxes) {
      boolean covers =
          switch (f) {
            case "north", "south" ->
                outer.dx() >= b.minX() - tol
                    && outer.dx() <= b.maxX() + tol
                    && outer.dy() >= b.minY() - tol
                    && outer.dy() <= b.maxY() + tol;
            case "east", "west" ->
                outer.dz() >= b.minZ() - tol
                    && outer.dz() <= b.maxZ() + tol
                    && outer.dy() >= b.minY() - tol
                    && outer.dy() <= b.maxY() + tol;
            case "up", "down" ->
                outer.dx() >= b.minX() - tol
                    && outer.dx() <= b.maxX() + tol
                    && outer.dz() >= b.minZ() - tol
                    && outer.dz() <= b.maxZ() + tol;
            default -> false;
          };
      if (!covers) continue;
      if (best == null || isInFrontAlong(f, b, best)) best = b;
    }
    if (best == null) return outer;
    double eps =
        Math.min(
            0.0001,
            Math.min(best.maxX() - best.minX(), Math.min(best.maxY() - best.minY(), best.maxZ() - best.minZ()))
                * 0.05);
    return switch (f) {
      case "north" -> new LocalPoint(outer.dx(), outer.dy(), best.minZ() + eps);
      case "south" -> new LocalPoint(outer.dx(), outer.dy(), best.maxZ() - eps);
      case "west" -> new LocalPoint(best.minX() + eps, outer.dy(), outer.dz());
      case "east" -> new LocalPoint(best.maxX() - eps, outer.dy(), outer.dz());
      case "down" -> new LocalPoint(outer.dx(), best.minY() + eps, outer.dz());
      case "up" -> new LocalPoint(outer.dx(), best.maxY() - eps, outer.dz());
      default -> outer;
    };
  }

  /** Projects a block-local hit point back onto normalized visual (u, v) over {@code bounds}. */
  public static UV projectToUV(
      String faceName, double localX, double localY, double localZ, Box bounds) {
    Box b = bounds == null ? new Box(0, 0, 0, 1, 1, 1) : bounds;
    double sx = Math.max(1e-4, b.maxX() - b.minX());
    double sy = Math.max(1e-4, b.maxY() - b.minY());
    double sz = Math.max(1e-4, b.maxZ() - b.minZ());
    String f = normalizeFace(faceName);
    double u;
    double v;
    switch (f) {
      case "north" -> {
        u = (b.maxX() - localX) / sx;
        v = (localY - b.minY()) / sy;
      }
      case "south" -> {
        u = (localX - b.minX()) / sx;
        v = (localY - b.minY()) / sy;
      }
      case "east" -> {
        u = (b.maxZ() - localZ) / sz;
        v = (localY - b.minY()) / sy;
      }
      case "west" -> {
        u = (localZ - b.minZ()) / sz;
        v = (localY - b.minY()) / sy;
      }
      case "up" -> {
        u = (localX - b.minX()) / sx;
        v = (b.maxZ() - localZ) / sz;
      }
      case "down" -> {
        u = (localX - b.minX()) / sx;
        v = (localZ - b.minZ()) / sz;
      }
      default -> throw new IllegalArgumentException(
          "[INVALID_ARGUMENT] face须为 up/down/north/south/east/west");
    }
    return new UV(Math.max(0.0, Math.min(1.0, u)), Math.max(0.0, Math.min(1.0, v)));
  }

  public static boolean matchesUV(UV expected, UV actual, double maxDelta) {
    if (expected == null || actual == null) return false;
    return Math.abs(expected.u() - actual.u()) <= maxDelta
        && Math.abs(expected.v() - actual.v()) <= maxDelta;
  }

  public static Box union(List<Box> boxes) {
    double minX = Double.POSITIVE_INFINITY,
        minY = Double.POSITIVE_INFINITY,
        minZ = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY,
        maxY = Double.NEGATIVE_INFINITY,
        maxZ = Double.NEGATIVE_INFINITY;
    for (Box b : boxes) {
      if (b.minX() < minX) minX = b.minX();
      if (b.minY() < minY) minY = b.minY();
      if (b.minZ() < minZ) minZ = b.minZ();
      if (b.maxX() > maxX) maxX = b.maxX();
      if (b.maxY() > maxY) maxY = b.maxY();
      if (b.maxZ() > maxZ) maxZ = b.maxZ();
    }
    return new Box(minX, minY, minZ, maxX, maxY, maxZ);
  }

  private static String normalizeFace(String faceName) {
    if (faceName == null || faceName.isBlank())
      throw new IllegalArgumentException("[INVALID_ARGUMENT] face 不能为空");
    String f = faceName.strip().toLowerCase(Locale.ROOT);
    return f.equals("top") ? "up" : f;
  }

  private static boolean isInFrontAlong(String face, Box cand, Box best) {
    return switch (face) {
      case "north" -> cand.minZ() < best.minZ() - 1e-6;
      case "south" -> cand.maxZ() > best.maxZ() + 1e-6;
      case "west" -> cand.minX() < best.minX() - 1e-6;
      case "east" -> cand.maxX() > best.maxX() + 1e-6;
      case "down" -> cand.minY() < best.minY() - 1e-6;
      case "up" -> cand.maxY() > best.maxY() + 1e-6;
      default -> false;
    };
  }
}
