package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** A short gliding approach to a loaded landing field. Starts after the player leaves the ground. */
final class ElytraRouteJob extends Actions.Job {
  private final BlockPos landing;
  private final Vec3 destination;
  private final boolean boost;
  private boolean opened, boostRequested;
  private String boostReceipt = "";

  ElytraRouteJob(JsonObject args, Consumer<String> done) {
    super(done);
    TaskReadiness.requireWorld(true);
    landing = Actions.target(args);
    destination = Vec3.atBottomCenterOf(landing);
    var p = Minecraft.getInstance().player;
    if (p.onGround() || p.isPassenger() || p.isInWater() || p.isInLava())
      throw new IllegalArgumentException("[FLIGHT_START_REQUIRED] 先从安全高台离地，或在已滑翔时开始");
    var elytra = p.getItemBySlot(EquipmentSlot.CHEST);
    if (!elytra.is(Items.ELYTRA) || !ElytraItem.isFlyEnabled(elytra))
      throw new IllegalArgumentException("[ELYTRA_REQUIRED] 胸甲槽需要耐久可用的鞘翅");
    double distance = horizontal(p.position());
    double drop = p.getY() - landing.getY();
    if (distance < 10 || distance > 64 || drop < 4 || drop > 30
        || !p.isFallFlying() && drop < 8)
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 需要水平10..64格、低4..30格的可观察着陆区；未展开至少低8格");
    for (int x = -1; x <= 1; x++)
      for (int z = -1; z <= 1; z++) {
        var at = landing.offset(x, 0, z);
        if (!world.hasChunkAt(at) || !world.getWorldBorder().isWithinBounds(at)
            || !Navigator.stand(at) || !world.getFluidState(at).isEmpty())
          throw new IllegalArgumentException("[LANDING_UNVERIFIED] 需要至少3×3的已加载安全落地面");
      }
    boost = args.has("boost") && args.get("boost").getAsBoolean();
  }

  @Override void tick() {
    var mc = Minecraft.getInstance();
    var p = mc.player;
    if (mc.screen != null || p.isPassenger() || p.isInWater() || p.isInLava()) {
      result("FLIGHT_INTERRUPTED", "滑翔中断，检查角色当前状态");
      return;
    }
    if (p.onGround()) {
      boolean landed = opened && !p.isFallFlying() && p.getHealth() > 0
          && horizontal(p.position()) <= 2 && Math.abs(p.getY() - landing.getY()) < 1;
      result(landed ? "NONE" : "LANDING_UNCONFIRMED",
          landed ? "已观察到目标范围内稳定着地" : "已离开滑翔，未在目标范围内确认着地");
      return;
    }
    if (++ticks > 240) {
      result("TIME_BUDGET", "滑翔预算结束，请人工接续飞行");
      return;
    }
    if (!p.isFallFlying()) {
      if (opened || ticks >= 16) {
        result("GLIDE_NOT_OBSERVED", "已尝试普通跳跃操作，未观察到可持续滑翔");
        return;
      }
      PlayerControls.motionState(false, ticks % 4 >= 2);
      return;
    }
    if (!opened) {
      opened = true;
      PlayerControls.release();
    }
    Vec3 delta = destination.subtract(p.position());
    var moving = p.getDeltaMovement();
    if (!clearAhead(moving)) {
      result("FLIGHT_PATH_BLOCKED", "前方未加载或有碰撞，请人工接续滑翔");
      return;
    }
    float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
    float pitch = (float) Math.toDegrees(Math.atan2(-delta.y, Math.max(2, horizontal(p.position()))));
    p.setYRot(p.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - p.getYRot()), -18, 18));
    p.setXRot(Mth.clamp(pitch * .65f, 0, 19));
    Actions.status = "滑翔 · 距着陆点 " + Math.round(horizontal(p.position())) + " 格";
    if (boost && !boostRequested && horizontal(p.position()) > 28
        && p.getY() - landing.getY() > 8 && Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 10) {
      boostRequested = true;
      String safeRocket = "";
      for (int i = 0; i < 36; i++) {
        var stack = p.getInventory().getItem(i);
        if (!stack.is(Items.FIREWORK_ROCKET)) continue;
        var fireworks = stack.getTagElement("Fireworks");
        if (fireworks != null
            && !fireworks.getList("Explosions", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty()) continue;
        safeRocket = WorldSense.fingerprint(stack);
        break;
      }
      if (safeRocket.isEmpty()) {
        boostReceipt = "背包没有已观察到的无爆炸烟花，继续滑翔";
        return;
      }
      var rocket = new JsonObject();
      rocket.addProperty("item", safeRocket);
      rocket.addProperty("mode", "tap");
      child("use_hand", rocket, reply -> boostReceipt = Rules.clean(reply, 250));
    }
  }

  private boolean clearAhead(Vec3 velocity) {
    var p = Minecraft.getInstance().player;
    for (int i = 1; i <= 3; i++) {
      var box = p.getBoundingBox().move(velocity.scale(i));
      var min = BlockPos.containing(box.minX, box.minY, box.minZ);
      var max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
      if (!world.hasChunkAt(min) || !world.hasChunkAt(max)
          || !world.getWorldBorder().isWithinBounds(box)) return false;
      // Close to the designated field, contact with the floor is the expected landing.
      if (!world.noCollision(p, box)
          && !(horizontal(p.position()) <= 2 && p.getY() <= landing.getY() + 1)) return false;
    }
    return true;
  }

  private double horizontal(Vec3 point) {
    return Math.hypot(destination.x - point.x, destination.z - point.z);
  }

  private void result(String code, String detail) {
    var p = Minecraft.getInstance().player;
    var out = new JsonObject();
    out.addProperty("business_success", code.equals("NONE"));
    out.addProperty("status", code.equals("NONE") ? "verified" : "partial");
    out.addProperty("reason_code", code);
    out.addProperty("detail", detail);
    out.addProperty("glide_observed", opened);
    out.addProperty("on_ground", p.onGround());
    out.addProperty("distance_to_target", horizontal(p.position()));
    if (boostRequested) out.addProperty("boost_receipt", boostReceipt);
    finish(out.toString());
  }
}
