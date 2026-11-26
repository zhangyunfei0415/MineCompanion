package dev.mibao.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/** Use the first actual outline hit and its face, rather than synthesizing a hit through a wall. */
public final class Reach {
  public static BlockHitResult faceFrom(BlockPos pos, Vec3 eye, Direction face) {
    return faceFrom(pos, eye, face, hit -> true);
  }

  public static BlockHitResult faceAt(
      BlockPos pos, Vec3 eye, Direction face, double u, double v) {
    var mc = Minecraft.getInstance();
    if (!mc.level.hasChunkAt(pos)) return null;
    double range = mc.gameMode.getPickRange() - .05;
    if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > (range + 1) * (range + 1)) return null;
    var shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
    if (shape.isEmpty()) return faceFrom(pos, eye, face);
    var bounds = shape.bounds();
    double ex = eye.x - pos.getX(), ey = eye.y - pos.getY(), ez = eye.z - pos.getZ();
    if (face == Direction.EAST && ex <= bounds.maxX
        || face == Direction.WEST && ex >= bounds.minX
        || face == Direction.UP && ey <= bounds.maxY
        || face == Direction.DOWN && ey >= bounds.minY
        || face == Direction.SOUTH && ez <= bounds.maxZ
        || face == Direction.NORTH && ez >= bounds.minZ) return null;
    var aabbs = shape.toAabbs();
    java.util.List<FaceHitMath.Box> boxes = new java.util.ArrayList<>(aabbs.size());
    for (var b : aabbs) boxes.add(new FaceHitMath.Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
    FaceHitMath.Box boundsBox =
        new FaceHitMath.Box(
            bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    FaceHitMath.LocalPoint lp = FaceHitMath.resolveFaceTarget(face.getName(), u, v, boxes);
    Vec3 aimPoint = new Vec3(pos.getX() + lp.dx(), pos.getY() + lp.dy(), pos.getZ() + lp.dz());
    Vec3 delta = aimPoint.subtract(eye);
    double distToAim = delta.length();
    if (distToAim < 1e-4) return null;
    // Extend past the target point (matching vanilla/StorageDrawers WorldUtils.rayTraceEyes) so
    // tiny face offsets never fall short of a recessed pane such as StorageDrawers slots.
    Vec3 end = eye.add(delta.scale((Math.min(range + 0.25, distToAim + 0.5)) / distToAim));
    var hit =
        mc.level.clip(
            new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
    if (hit.getType() != HitResult.Type.BLOCK
        || !hit.getBlockPos().equals(pos)
        || hit.getDirection() != face
        || eye.distanceTo(hit.getLocation()) > range) return null;
    Vec3 h = hit.getLocation();
    FaceHitMath.UV actualUv =
        FaceHitMath.projectToUV(
            face.getName(), h.x - pos.getX(), h.y - pos.getY(), h.z - pos.getZ(), boundsBox);
    if (!FaceHitMath.matchesUV(new FaceHitMath.UV(u, v), actualUv, 0.12)) return null;
    return hit;
  }

  private static BlockHitResult faceFrom(
      BlockPos pos, Vec3 eye, Direction face, java.util.function.Predicate<BlockHitResult> accept) {
    var mc = Minecraft.getInstance();
    if (!mc.level.hasChunkAt(pos)) return null;
    double range = mc.gameMode.getPickRange() - .05;
    if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > (range + 1) * (range + 1)) return null;
    var shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
    if (shape.isEmpty()) {
      var fluid = hitFrom(pos, eye);
      return fluid != null && fluid.getDirection() == face && accept.test(fluid) ? fluid : null;
    }
    var centered = faceAt(pos, eye, face, 0.5, 0.5);
    if (centered != null && accept.test(centered)) return centered;
    var boxes = shape.toAabbs();
    int budget = 0;
    for (var box : boxes) {
      if (++budget > 16) break;
      double ex = eye.x - pos.getX(), ey = eye.y - pos.getY(), ez = eye.z - pos.getZ();
      if (face == Direction.EAST && ex <= box.maxX
          || face == Direction.WEST && ex >= box.minX
          || face == Direction.UP && ey <= box.maxY
          || face == Direction.DOWN && ey >= box.minY
          || face == Direction.SOUTH && ez <= box.maxZ
          || face == Direction.NORTH && ez >= box.minZ) continue;
      for (double[] uv : new double[][] {{.5, .5}, {.2, .2}, {.8, .2}, {.2, .8}, {.8, .8}}) {
        double x = (box.minX + box.maxX) / 2,
            y = (box.minY + box.maxY) / 2,
            z = (box.minZ + box.maxZ) / 2;
        switch (face.getAxis()) {
          case X -> {
            x = face == Direction.EAST ? box.maxX - .0001 : box.minX + .0001;
            y = box.minY + (box.maxY - box.minY) * uv[0];
            z = box.minZ + (box.maxZ - box.minZ) * uv[1];
          }
          case Y -> {
            y = face == Direction.UP ? box.maxY - .0001 : box.minY + .0001;
            x = box.minX + (box.maxX - box.minX) * uv[0];
            z = box.minZ + (box.maxZ - box.minZ) * uv[1];
          }
          case Z -> {
            z = face == Direction.SOUTH ? box.maxZ - .0001 : box.minZ + .0001;
            x = box.minX + (box.maxX - box.minX) * uv[0];
            y = box.minY + (box.maxY - box.minY) * uv[1];
          }
        }
        var hit =
            mc.level.clip(
                new ClipContext(
                    eye,
                    new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z),
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    mc.player));
        if (hit.getType() == HitResult.Type.BLOCK
            && hit.getBlockPos().equals(pos)
            && hit.getDirection() == face
            && eye.distanceTo(hit.getLocation()) <= range
            && accept.test(hit)) return hit;
      }
    }
    return null;
  }

  public static BlockHitResult placement(BlockPos target) {
    return placementFrom(target, Minecraft.getInstance().player.getEyePosition());
  }

  /** Ray-trace a real neighboring face whose normal places into the requested empty cell. */
  public static BlockHitResult placementFrom(BlockPos target, Vec3 eye) {
    return placementFrom(target, eye, null);
  }

  public static BlockHitResult placementFrom(BlockPos target, Vec3 eye, Direction onlyFace) {
    return placementFrom(target, eye, onlyFace, hit -> true);
  }

  static BlockHitResult placementFrom(
      BlockPos target,
      Vec3 eye,
      Direction onlyFace,
      java.util.function.Predicate<BlockHitResult> accept) {
    var mc = Minecraft.getInstance();
    if (!mc.level.hasChunkAt(target) || !mc.level.getBlockState(target).canBeReplaced())
      return null;
    double half = mc.player.getBbWidth() / 2.0, feet = eye.y - mc.player.getEyeHeight();
    if (new AABB(
            eye.x - half,
            feet,
            eye.z - half,
            eye.x + half,
            feet + mc.player.getBbHeight(),
            eye.z + half)
        .intersects(new AABB(target))) return null;
    if (!mc.level.getBlockState(target).isAir()) {
      BlockHitResult replace = hitFrom(target, eye);
      if (replace != null
          && (onlyFace == null || replace.getDirection() == onlyFace)
          && accept.test(replace)) return replace;
    }
    for (Direction face :
        onlyFace == null
            ? new Direction[] {
              Direction.UP,
              Direction.NORTH,
              Direction.SOUTH,
              Direction.WEST,
              Direction.EAST,
              Direction.DOWN
            }
            : new Direction[] {onlyFace}) {
      BlockPos support = target.relative(face.getOpposite());
      if (!mc.level.hasChunkAt(support)) continue;
      var shape = mc.level.getBlockState(support).getShape(mc.level, support);
      if (shape.isEmpty()) continue;
      if (face.getAxis().isHorizontal()) {
        for (double vSample : new double[] {0.78, 0.22}) {
          var subHit = faceAt(support, eye, face, 0.5, vSample);
          if (subHit != null && support.relative(face).equals(target) && accept.test(subHit))
            return subHit;
        }
      }
      var hit = faceFrom(support, eye, face, accept);
      if (hit != null && support.relative(face).equals(target)) return hit;
    }
    return null;
  }

  public static BlockHitResult top(BlockPos pos) {
    return topFrom(pos, Minecraft.getInstance().player.getEyePosition());
  }

  public static BlockHitResult topFrom(BlockPos pos, Vec3 eye) {
    return faceFrom(pos, eye, Direction.UP);
  }

  public static BlockHitResult hit(BlockPos pos) {
    return hitFrom(pos, Minecraft.getInstance().player.getEyePosition());
  }

  public static BlockHitResult mine(BlockPos pos) {
    return mineFrom(pos, Minecraft.getInstance().player.getEyePosition());
  }

  public static BlockHitResult mineFrom(BlockPos pos, Vec3 eye) {
    BlockHitResult direct = hitFrom(pos, eye);
    if (direct != null) return direct;
    var mc = Minecraft.getInstance();
    if (mc.level == null || !mc.level.hasChunkAt(pos)) return null;
    double range = mc.gameMode.getPickRange() - .05;
    for (Direction dir : Direction.values()) {
      BlockHitResult faceHit = faceFrom(pos, eye, dir);
      if (faceHit != null
          && faceHit.getBlockPos().equals(pos)
          && eye.distanceTo(faceHit.getLocation()) <= range) {
        return faceHit;
      }
    }
    return null;
  }

  public static BlockHitResult hitFrom(BlockPos pos, Vec3 eye) {
    var mc = Minecraft.getInstance();
    if (!mc.level.hasChunkAt(pos)) return null;
    var state = mc.level.getBlockState(pos);
    var shape = state.getShape(mc.level, pos);
    if (shape.isEmpty()) {
      if (!state.getFluidState().isEmpty()) {
        var fluidHit =
            mc.level.clip(
                new ClipContext(
                    eye,
                    Vec3.atCenterOf(pos),
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.SOURCE_ONLY,
                    mc.player));
        if (fluidHit.getType() == HitResult.Type.BLOCK
            && fluidHit.getBlockPos().equals(pos)
            && eye.distanceTo(fluidHit.getLocation()) <= mc.gameMode.getPickRange() - .05)
          return fluidHit;
      }
      return null;
    }
    Vec3 center = shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
    double reach = mc.gameMode.getPickRange();
    for (int i = 0; i < 7; i++) {
      Vec3 aim = center;
      if (i > 0) {
        Direction d = Direction.values()[i - 1];
        aim = center.add(d.getStepX() * .25, d.getStepY() * .25, d.getStepZ() * .25);
      }
      BlockHitResult hit =
          mc.level.clip(
              new ClipContext(
                  eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
      if (hit.getType() == HitResult.Type.BLOCK
          && hit.getBlockPos().equals(pos)
          && eye.distanceTo(hit.getLocation()) <= reach - .05) return hit;
    }
    return null;
  }
}
