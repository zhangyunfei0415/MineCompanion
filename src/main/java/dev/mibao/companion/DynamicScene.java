package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Bounded shared geometry cache. Loaded client data is distinct from visibility and server AI
 * intent.
 */
public final class DynamicScene {
  private static JsonObject cached;
  private static long at;
  private static Object level;
  private static Vec3 origin;

  public static void reset() {
    cached = null;
    at = 0;
    level = null;
    origin = null;
  }

  public static JsonObject inspect() {
    var mc = Minecraft.getInstance();
    if (mc.level == null || mc.player == null) return new JsonObject();
    long now = System.currentTimeMillis();
    if (cached != null
        && level == mc.level
        && now - at < 100
        && origin.distanceToSqr(mc.player.position()) < 1) return copy(now);
    level = mc.level;
    origin = mc.player.position();
    at = now;
    cached = new JsonObject();
    cached.addProperty("source", "loaded_client_entities; not server AI targets");
    cached.addProperty("observed_at_ms", now);
    var all =
        mc.level.getEntities(
            mc.player,
            mc.player.getBoundingBox().inflate(16),
            e ->
                e.isAlive()
                    && (e instanceof LivingEntity
                        || e instanceof net.minecraft.world.entity.vehicle.Boat
                        || e instanceof net.minecraft.world.entity.vehicle.AbstractMinecart
                        || e instanceof net.minecraft.world.entity.decoration.HangingEntity
                        || e instanceof ItemEntity
                        || e instanceof net.minecraft.world.entity.projectile.Projectile));
    all.sort(Comparator.comparingDouble(mc.player::distanceToSqr));
    JsonArray entries = new JsonArray();
    long budget = System.nanoTime();
    for (int i = 0; i < Math.min(48, all.size()); i++) {
      if (i >= 4 && System.nanoTime() - budget > 2_000_000) break;
      var e = all.get(i);
      var o = new JsonObject();
      o.addProperty("entity_id", e.getId());
      o.addProperty("uuid", e.getUUID().toString());
      o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
      o.addProperty("player_vehicle", mc.player.getVehicle() == e);
      o.addProperty("passengers", e.getPassengers().size());
      o.addProperty("visible", mc.player.hasLineOfSight(e));
      o.add("position", vec(e.position()));
      o.add("velocity", vec(e.getDeltaMovement()));
      var b = e.getBoundingBox();
      o.add(
          "aabb",
          Store.GSON.toJsonTree(new double[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ}));
      o.addProperty("eye_to_box", MeleeOps.distance(mc.player.getEyePosition(), b));
      if (e instanceof LivingEntity living) {
        o.addProperty("health", living.getHealth());
        o.addProperty(
            "recent_damage_source",
            CombatPolicy.recent(System.nanoTime(), CombatEvidence.attacker(e.getUUID())));
        o.addProperty("melee_status", MeleeOps.reason(living));
        o.addProperty(
            "selected",
            Objects.equals(CombatEvidence.focus, e.getUUID())
                || Objects.equals(HuntJob.focus, e.getUUID()));
      }
      if (e instanceof ItemEntity item) {
        o.add("stack", Actions.item(item.getItem()));
        int capacity = 0;
        for (int n = 0; n < 36; n++) {
          var s = mc.player.getInventory().getItem(n);
          if (s.isEmpty()) capacity += item.getItem().getMaxStackSize();
          else if (net.minecraft.world.item.ItemStack.isSameItemSameTags(s, item.getItem()))
            capacity += Math.max(0, s.getMaxStackSize() - s.getCount());
        }
        o.addProperty("inventory_capacity_for_stack", capacity);
        o.addProperty("capacity_sufficient", capacity >= item.getItem().getCount());
      }
      entries.add(o);
    }
    cached.add("entities", entries);
    cached.addProperty("sample_count", entries.size());
    cached.addProperty("truncated", all.size() > entries.size());
    cached.addProperty("cpu_ms", (System.nanoTime() - budget) / 1_000_000.0);
    return copy(now);
  }

  private static JsonArray vec(Vec3 v) {
    var a = new JsonArray();
    a.add(v.x);
    a.add(v.y);
    a.add(v.z);
    return a;
  }

  private static JsonObject copy(long now) {
    var o = cached.deepCopy();
    o.addProperty("age_ms", now - at);
    return o;
  }
}
