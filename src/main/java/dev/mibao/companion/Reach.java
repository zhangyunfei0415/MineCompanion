package dev.mibao.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/** Use the first actual outline hit and its face, rather than synthesizing a hit through a wall. */
public final class Reach {
  public static BlockHitResult faceFrom(BlockPos pos, Vec3 eye, Direction face) {
    var mc = Minecraft.getInstance();
    if (!mc.level.hasChunkAt(pos)) return null;
    double range = mc.gameMode.getPickRange() - .05;
    if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > (range + 1) * (range + 1)) return null;
    var shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
    if (shape.isEmpty()) {
      var fluid = hitFrom(pos, eye);
      return fluid != null && fluid.getDirection() == face ? fluid : null;
    }
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
            && eye.distanceTo(hit.getLocation()) <= range) return hit;
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
    var mc = Minecraft.getInstance();
    if (!mc.level.hasChunkAt(target) || !mc.level.getBlockState(target).canBeReplaced()) return null;
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
      if (replace != null && (onlyFace == null || replace.getDirection() == onlyFace)) return replace;
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
      var hit = faceFrom(support, eye, face);
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
            && eye.distanceTo(fluidHit.getLocation())
                <= mc.gameMode.getPickRange() - .05) return fluidHit;
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
