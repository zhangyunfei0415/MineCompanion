package dev.mibao.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

/** Bounded normal-player jump/swim inputs; water surface is never claimed as solid ground. */
public final class SwimRecovery {
  private int ticks, stable;
  private BlockPos shore;
  private String error = "";
  private final int budget;

  public SwimRecovery(int ticks) {
    budget = Rules.bounded(ticks, 20, 160);
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public String error() {
    return error;
  }

  public int tick() {
    var p = mc().player;
    if (p.isInLava()) {
      stop();
      error = "[DANGEROUS_FLUID] 岩浆不按普通水中脱困处理";
      return -1;
    }
    if (mc().screen != null) {
      stop();
      error = "[SCREEN_BLOCKING] 先正常退出菜单再脱困";
      return -1;
    }
    if (!p.isInWater() && Navigator.grounded()) {
      stop();
      return ++stable >= 3 ? 1 : 0;
    }
    stable = 0;
    if (++ticks > budget) {
      stop();
      error = "[WATER_ESCAPE_FAILED] 有界上浮/靠岸未稳定落地；请检查高岸、上盖或更远的出口";
      return -1;
    }
    if (shore == null || ticks % 20 == 1) {
      shore = null;
      double best = Double.MAX_VALUE;
      var c = p.blockPosition();
      for (int dx = -6; dx <= 6; dx++)
        for (int dz = -6; dz <= 6; dz++)
          for (int dy = -1; dy <= 4; dy++) {
            if (dx * dx + dz * dz > 36) continue;
            var at = c.offset(dx, dy, dz);
            if (!mc().level.hasChunkAt(at) || !Navigator.stand(at)) continue;
            var target = Navigator.foot(at);
            boolean safe = true;
            for (int i = 1; i <= 8; i++) {
              var sample = BlockPos.containing(p.position().lerp(target, i / 8.0));
              if (!mc().level.hasChunkAt(sample)
                  || mc().level.getFluidState(sample).is(FluidTags.LAVA)) {
                safe = false;
                break;
              }
            }
            double score = p.distanceToSqr(target);
            if (safe && score < best) {
              best = score;
              shore = at.immutable();
            }
          }
    }
    boolean forward = false;
    if (shore != null) {
      Vec3 delta = Navigator.foot(shore).subtract(p.position());
      var steering =
          MovementSteering.toward(
              p.getYRot(), (float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
      p.setYRot(steering.yaw());
      p.setXRot(-15);
      forward = steering.forward() && delta.horizontalDistanceSqr() > .08;
    }
    // Holding jump while immersed is normal ascent. On dry terrain use short bounded jump pulses.
    PlayerControls.motionState(forward, p.isInWater() || ticks % 16 < 6);
    return 0;
  }

  public void stop() {
    PlayerControls.release();
  }
}
