package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

/** Steers a player-controlled boat over loaded, level water using its ordinary movement keys. */
final class BoatRouteJob extends Actions.Job {
  private final Boat boat;
  private final Vec3 destination;
  private final double waterY;
  private boolean forward, left, right;

  BoatRouteJob(JsonObject args, Consumer<String> done) {
    super(done);
    var mc = Minecraft.getInstance();
    if (!(mc.player.getVehicle() instanceof Boat mounted) || mounted.getControllingPassenger() != mc.player)
      throw new IllegalArgumentException("[NOT_MOUNTED] 请先坐到船的驾驶座");
    boat = mounted;
    var target = Actions.target(args);
    destination = Vec3.atBottomCenterOf(target);
    waterY = boat.getY();
    if (Math.abs(target.getY() - waterY) > 2
        || distance(destination, boat.position()) > 32)
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 船只接受32格内同一水面的目标");
    if (!navigable(destination, boat.position()))
      throw new IllegalArgumentException("[WATER_ROUTE_UNVERIFIED] 目标不是已观察的可通行水面");
  }

  @Override void tick() {
    var mc = Minecraft.getInstance();
    if (mc.screen != null || mc.player.getVehicle() != boat || boat.getControllingPassenger() != mc.player) {
      result("RIDING_INTERRUPTED", "驾驶中断，请核对当前骑乘状态");
      return;
    }
    if (++ticks > 360 || Math.abs(boat.getY() - waterY) > 1.25) {
      result("WATER_ROUTE_INCOMPLETE", "船未到目标水域或水面高度发生变化");
      return;
    }
    Vec3 here = boat.position();
    double remaining = distance(destination, here);
    Actions.status = "乘船 · 离目标水面 " + Math.round(remaining) + " 格";
    if (remaining <= 1.8) {
      result("NONE", "已到目标水域附近，船保持骑乘，可核对岸边后下船");
      return;
    }
    var travel = new Vec3(destination.x - here.x, 0, destination.z - here.z).normalize();
    var ahead = here.add(travel.scale(Math.min(2.5, remaining)));
    if (!navigable(ahead, here)) {
      result("WATER_ROUTE_BLOCKED", "前方浅滩、障碍或区块未加载");
      return;
    }
    float yaw = (float) Math.toDegrees(Math.atan2(-travel.x, travel.z));
    float turn = Mth.wrapDegrees(yaw - boat.getYRot());
    boolean go = Math.abs(turn) < 24;
    boolean turnLeft = turn < -5;
    boolean turnRight = turn > 5;
    if (go != forward || turnLeft != left || turnRight != right) {
      PlayerControls.release();
      forward = go;
      left = turnLeft;
      right = turnRight;
    }
    // Reassert holds after a cooperative pause only when this same job is running again.
    if (forward) PlayerControls.hold(mc.options.keyUp);
    if (left) PlayerControls.hold(mc.options.keyLeft);
    if (right) PlayerControls.hold(mc.options.keyRight);
  }

  private boolean navigable(Vec3 point, Vec3 origin) {
    var box = boat.getBoundingBox().move(point.x - origin.x, 0, point.z - origin.z);
    int surface = (int) Math.floor(waterY - .15);
    for (var pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, surface, box.minZ),
        BlockPos.containing(box.maxX, surface, box.maxZ))) {
      if (!world.hasChunkAt(pos) || !world.getWorldBorder().isWithinBounds(pos)
          || !world.getFluidState(pos).is(FluidTags.WATER)) return false;
    }
    return world.noCollision(boat, box.deflate(.02));
  }

  private static double distance(Vec3 a, Vec3 b) {
    return Math.hypot(a.x - b.x, a.z - b.z);
  }

  private void result(String code, String detail) {
    var result = VehicleJob.state();
    result.addProperty("business_success", code.equals("NONE"));
    result.addProperty("status", code.equals("NONE") ? "verified" : "partial");
    result.addProperty("reason_code", code);
    result.addProperty("detail", detail);
    result.addProperty("distance_to_target", distance(destination, boat.position()));
    finish(result.toString());
  }
}
