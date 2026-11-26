package dev.mibao.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

/** Normal rotation packet, followed by bounded convergence ticks before a single interaction. */
final class AimGate {
  private Vec3 aim;
  private int ticks;
  private int totalTicks;

  void reset() {
    aim = null;
    ticks = 0;
    totalTicks = 0;
  }

  boolean timedOut() {
    return totalTicks > 20;
  }

  static float wrapDegrees(float deg) {
    return FaceHitMath.wrapDegrees(deg);
  }

  static boolean converged(float currentYaw, float prevYaw, float desiredYaw) {
    return FaceHitMath.yawConverged(currentYaw, prevYaw, desiredYaw);
  }

  boolean ready(Vec3 target) {
    var p = Minecraft.getInstance().player;
    totalTicks++;
    if (aim == null || aim.distanceToSqr(target) > .0001) {
      aim = target;
      ticks = 0;
      Actions.look(target);
      if (p.connection != null) {
        p.connection.send(
            new ServerboundMovePlayerPacket.Rot(p.getYRot(), p.getXRot(), p.onGround()));
      }
      return false;
    }
    Actions.look(target);
    Vec3 eye = p.getEyePosition();
    float desiredYaw = (float) Math.toDegrees(Math.atan2(-(target.x - eye.x), target.z - eye.z));
    if (++ticks >= 2 && converged(p.getYRot(), p.yRotO, desiredYaw)) {
      if (p.connection != null) {
        p.connection.send(
            new ServerboundMovePlayerPacket.Rot(p.getYRot(), p.getXRot(), p.onGround()));
      }
      return true;
    }
    return false;
  }
}
