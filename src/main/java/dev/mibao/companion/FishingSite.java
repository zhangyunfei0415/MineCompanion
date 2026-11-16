package dev.mibao.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Small loaded-water search. Shore candidates use the same support geometry as navigation. */
final class FishingSite {
  private static final int[][] SHORE_OFFSETS = {
    {3, 0}, {-3, 0}, {0, 3}, {0, -3}, {3, 3}, {3, -3}, {-3, 3}, {-3, -3}
  };
  private final BlockPos origin = Minecraft.getInstance().player.blockPosition();
  private int cursor;
  private double score = Double.MAX_VALUE;
  BlockPos shore;
  Vec3 water;

  boolean advance() {
    var mc = Minecraft.getInstance();
    long started = System.nanoTime();
    for (int budget = 0; cursor < 17 * 17 * 7 && budget < 128; budget++) {
      if (budget > 0 && System.nanoTime() - started > 1_000_000) break;
      int i = cursor++;
      var pos = origin.offset(i % 17 - 8, i / (17 * 17) - 3, i / 17 % 17 - 8);
      if (!mc.level.hasChunkAt(pos)
          || !mc.level.hasChunkAt(pos.above())
          || !mc.level.getFluidState(pos).is(FluidTags.WATER)
          || !mc.level.getFluidState(pos.above()).isEmpty()
          || !mc.level
              .getBlockState(pos.above())
              .getCollisionShape(mc.level, pos.above())
              .isEmpty()) continue;
      Vec3 target = Vec3.atLowerCornerOf(pos).add(.5, .85, .5);
      consider(Navigator.standingCell(), mc.player.getEyePosition(), target, 0);
      for (int[] offset : SHORE_OFFSETS) {
        BlockPos stand = pos.offset(offset[0], 1, offset[1]);
        if (!mc.level.hasChunkAt(stand) || !Navigator.stand(stand)) continue;
        Vec3 foot = Navigator.foot(stand);
        consider(
            stand,
            foot.add(0, mc.player.getEyeHeight(), 0),
            target,
            foot.distanceToSqr(mc.player.position()) + 1);
      }
    }
    return cursor >= 17 * 17 * 7;
  }

  private void consider(BlockPos stand, Vec3 eye, Vec3 target, double travel) {
    var mc = Minecraft.getInstance();
    if (!Navigator.stand(stand)) return;
    double distance = eye.distanceToSqr(target);
    if (distance < 9 || distance > 49 || travel >= score) return;
    var ray =
        mc.level.clip(
            new ClipContext(
                eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
    if (ray.getType() != HitResult.Type.MISS) return;
    shore = stand;
    water = target;
    score = travel;
  }
}
