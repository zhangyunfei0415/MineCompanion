package dev.mibao.companion;

import net.minecraft.client.Minecraft;

/** The original client world and control generation for a continuous operation. */
record ActionContext(Object world, String scope, String dimension, long epoch) {
  static ActionContext capture() {
    var world = Minecraft.getInstance().level;
    if (world == null) throw new IllegalArgumentException("[WORLD_REQUIRED] 请先进入世界");
    return new ActionContext(world, Store.scope(), world.dimension().location().toString(), Agent.epoch);
  }

  boolean current() {
    return world == Minecraft.getInstance().level
        && scope.equals(Store.scope())
        && epoch == Agent.epoch;
  }
}
