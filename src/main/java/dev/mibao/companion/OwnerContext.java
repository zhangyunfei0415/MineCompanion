package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/**
 * Read-only owner focus & shared-attention context: reports owner location, held items, health,
 * and 6-block line-of-sight target (block or entity) without interfering with owner controls.
 */
final class OwnerContext {
  private OwnerContext() {}

  static Player findOwner(Minecraft mc) {
    return findPlayer(mc, "");
  }

  static Player findPlayer(Minecraft mc, String explicitName) {
    if (mc == null || mc.level == null || mc.player == null) return null;
    if (explicitName != null && !explicitName.isBlank()) {
      for (var p : mc.level.players()) {
        if (p != mc.player
            && p.isAlive()
            && !p.isSpectator()
            && p.getGameProfile().getName().equalsIgnoreCase(explicitName.trim())) return p;
      }
      return null;
    }
    UUID followId = FollowControl.currentTargetUuid();
    if (followId != null) {
      Player byUuid = mc.level.getPlayerByUUID(followId);
      if (byUuid != null && byUuid != mc.player && byUuid.isAlive() && !byUuid.isSpectator())
        return byUuid;
    }
    String ownerName = Store.config.owner == null ? "" : Store.config.owner.trim();
    if (!ownerName.isBlank()) {
      for (var p : mc.level.players()) {
        if (p != mc.player
            && p.isAlive()
            && !p.isSpectator()
            && p.getGameProfile().getName().equalsIgnoreCase(ownerName)) return p;
      }
    }
    return null;
  }

  static JsonObject observe() {
    var mc = Minecraft.getInstance();
    var out = new JsonObject();
    if (mc.level == null || mc.player == null) {
      out.addProperty("online", false);
      return out;
    }
    Player owner = findOwner(mc);
    if (owner == null) {
      out.addProperty("online", false);
      out.addProperty("reason", "主人/跟随目标未在当前客户端加载范围内");
      return out;
    }
    out.addProperty("online", true);
    out.addProperty("name", owner.getGameProfile().getName());
    out.addProperty("uuid", owner.getUUID().toString());
    out.addProperty("distance", Math.round(mc.player.distanceTo(owner) * 100.0) / 100.0);
    var pos = new JsonObject();
    pos.addProperty("x", owner.blockPosition().getX());
    pos.addProperty("y", owner.blockPosition().getY());
    pos.addProperty("z", owner.blockPosition().getZ());
    out.add("position", pos);
    out.addProperty("health", owner.getHealth());
    out.addProperty("food", owner.getFoodData().getFoodLevel());
    out.addProperty("sleeping", owner.isSleeping());
    out.addProperty("crouching", owner.isCrouching());
    out.add("main_hand", Actions.item(owner.getMainHandItem()));
    out.add("off_hand", Actions.item(owner.getOffhandItem()));

    Vec3 eye = owner.getEyePosition();
    Vec3 look = owner.getLookAngle();
    Vec3 end = eye.add(look.scale(6.0));
    var blockHit =
        mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, owner));
    if (blockHit.getType() == HitResult.Type.BLOCK) {
      var bp = blockHit.getBlockPos();
      var bObj = new JsonObject();
      bObj.addProperty("x", bp.getX());
      bObj.addProperty("y", bp.getY());
      bObj.addProperty("z", bp.getZ());
      bObj.addProperty("face", blockHit.getDirection().getName());
      bObj.addProperty(
          "block", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(bp).getBlock()).toString());
      out.add("looking_at_block", bObj);
    }
    var entityHit =
        ProjectileUtil.getEntityHitResult(
            owner,
            eye,
            end,
            owner.getBoundingBox().expandTowards(look.scale(6.0)).inflate(1.0),
            e -> e != owner && !e.isSpectator() && e.isPickable(),
            36.0);
    if (entityHit != null && entityHit.getEntity() != null) {
      var target = entityHit.getEntity();
      var eObj = new JsonObject();
      eObj.addProperty("entity_id", target.getId());
      eObj.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
      eObj.addProperty("name", target.getName().getString());
      eObj.addProperty("x", target.blockPosition().getX());
      eObj.addProperty("y", target.blockPosition().getY());
      eObj.addProperty("z", target.blockPosition().getZ());
      out.add("looking_at_entity", eObj);
    }
    return out;
  }

  static JsonObject compact() {
    var full = observe();
    var out = new JsonObject();
    out.addProperty("online", full.has("online") && full.get("online").getAsBoolean());
    if (!out.get("online").getAsBoolean()) return out;
    out.addProperty("name", full.get("name").getAsString());
    out.addProperty("distance", full.get("distance").getAsDouble());
    out.add("main_hand", full.get("main_hand"));
    if (full.has("looking_at_block")) out.add("looking_at_block", full.get("looking_at_block"));
    if (full.has("looking_at_entity")) out.add("looking_at_entity", full.get("looking_at_entity"));
    return out;
  }
}
