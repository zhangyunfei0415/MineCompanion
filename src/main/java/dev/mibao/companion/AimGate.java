package dev.mibao.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

/** Normal rotation packet, followed by separate client ticks before a single interaction. */
final class AimGate {
  private Vec3 aim;
  private int ticks;

  boolean ready(Vec3 target) {
    var p = Minecraft.getInstance().player;
    if (aim == null || aim.distanceToSqr(target) > .0001) {
      aim = target;
      ticks = 0;
      Actions.look(target);
      p.connection.send(
          new ServerboundMovePlayerPacket.Rot(p.getYRot(), p.getXRot(), p.onGround()));
      return false;
    }
    Actions.look(target);
    return ++ticks >= 3;
  }
}
