package dev.mibao.companion;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Loaded client collision evidence for a player-sized landing, without server access. */
final class LandingSafety {
  static JsonObject inspect(double x, double y, double z) {
    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 落点坐标必须为有限数值");
    var mc = Minecraft.getInstance();
    var o = new JsonObject();
    o.addProperty("x", x);
    o.addProperty("y", y);
    o.addProperty("z", z);
    boolean currentPosition = mc.player.position().distanceToSqr(x, y, z) < .000001;
    var box =
        mc.player
            .getBoundingBox()
            .move(x - mc.player.getX(), y - mc.player.getY(), z - mc.player.getZ())
            .deflate(.0001);
    var support = box.move(0, -.08, 0);
    for (BlockPos p :
        BlockPos.betweenClosed(
            BlockPos.containing(support.minX, support.minY, support.minZ),
            BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
      if (!mc.level.hasChunkAt(p)) {
        o.addProperty("safe", false);
        o.addProperty("reason_code", "LANDING_UNKNOWN");
        return o;
      }
    }
    boolean clear = mc.level.noCollision(mc.player, box);
    boolean supported = !mc.level.noCollision(mc.player, support);
    var feet = BlockPos.containing(x, y, z);
    var below = feet.below();
    var blockers = new com.google.gson.JsonArray();
    boolean hazard = false;
    for (var p :
        BlockPos.betweenClosed(
            BlockPos.containing(support.minX, support.minY, support.minZ),
            BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
      var state = mc.level.getBlockState(p);
      String id =
          net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
      if (!clear && blockers.size() < 8
          && state.getCollisionShape(mc.level, p).toAabbs().stream()
              .anyMatch(shape -> shape.move(p.getX(), p.getY(), p.getZ()).intersects(box))) {
        var blocker = new JsonObject();
        blocker.addProperty("x", p.getX());
        blocker.addProperty("y", p.getY());
        blocker.addProperty("z", p.getZ());
        blocker.addProperty("block", id);
        blockers.add(blocker);
      }
      hazard |=
          !state.getFluidState().isEmpty()
              || java.util.Set.of(
                      "minecraft:fire",
                      "minecraft:soul_fire",
                      "minecraft:cactus",
                      "minecraft:magma_block",
                      "minecraft:nether_portal",
                      "minecraft:end_portal")
                  .contains(id);
    }
    o.addProperty("safe", clear && supported && !hazard && y >= mc.level.getMinBuildHeight());
    o.addProperty("at_current_position", currentPosition);
    o.addProperty("position_context", currentPosition ? "AT_CURRENT_POSITION" : "TARGET_POSITION");
    o.addProperty("clear_body", clear);
    o.addProperty("supported", supported);
    o.addProperty("hazard", hazard);
    if (!clear) {
      o.add("blocked_by", blockers);
      o.addProperty("suggested_tool", "column_probe");
    }
    o.addProperty(
        "support_block",
        net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(mc.level.getBlockState(below).getBlock())
            .toString());
    o.addProperty(
        "reason_code",
        !clear
            ? "LANDING_OBSTRUCTED"
            : !supported
                ? "LANDING_NO_SUPPORT"
                : hazard ? "LANDING_HAZARD" : currentPosition ? "AT_CURRENT_POSITION" : "NONE");
    o.addProperty("evidence", "current_loaded_client_collision_only");
    return o;
  }
}
