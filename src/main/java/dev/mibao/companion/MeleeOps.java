package dev.mibao.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/**
 * Original client adaptation: prepare, target, approach a visible stance, cooldown, then attack.
 */
public final class MeleeOps {
  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public static boolean weapon(ItemStack s) {
    return !s.isEmpty()
        && !(s.getItem() instanceof ProjectileWeaponItem)
        && s.getAttributeModifiers(EquipmentSlot.MAINHAND).containsKey(Attributes.ATTACK_DAMAGE)
        && (!s.isDamageableItem() || s.getMaxDamage() - s.getDamageValue() > 2);
  }

  public static int bestWeapon(boolean hotbarOnly) {
    int best = -1;
    double score = -1;
    for (int i = 0; i < (hotbarOnly ? 9 : 36); i++) {
      var s = mc().player.getInventory().getItem(i);
      if (!weapon(s)) continue;
      double damage = 1, speed = 4;
      for (var a : s.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE))
        damage += a.getAmount();
      for (var a : s.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_SPEED))
        speed += a.getAmount();
      double value = damage * Math.max(.1, speed) + (s.getItem() instanceof SwordItem ? 2 : 0);
      if (value > score) {
        score = value;
        best = i;
      }
    }
    return best;
  }

  public static boolean eligible(LivingEntity e, String type) {
    String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    boolean player = e instanceof net.minecraft.world.entity.player.Player;
    return e != mc().player
        && e.isAlive()
        && !e.isRemoved()
        && e.isPickable()
        && (!e.hasCustomName() || player || Store.config.attackNamed)
        && !(e instanceof TamableAnimal)
        && ((player && Store.config.attackPlayers && type.equals("minecraft:player"))
            || !player
                && (CombatPolicy.HUNTABLE.contains(id)
                        && (type.equals("hostile") || type.equals(id))
                    || Store.config.huntResources
                        && CombatPolicy.RESOURCES.contains(id)
                        && type.equals(id)));
  }

  public static double reach() {
    return Math.max(0, Math.min(3, mc().player.getEntityReach()) - .15);
  }

  public static Vec3 aim(LivingEntity e) {
    var b = e.getBoundingBox();
    var eye = mc().player.getEyePosition();
    Vec3 fallback = b.getCenter();
    for (double fraction : new double[] {.65, .4, .85}) {
      var p =
          new Vec3(
              (b.minX + b.maxX) / 2, b.minY + (b.maxY - b.minY) * fraction, (b.minZ + b.maxZ) / 2);
      if (mc().level
              .clip(
                  new ClipContext(
                      eye, p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc().player))
              .getType()
          == HitResult.Type.MISS) return p;
    }
    return fallback;
  }

  public static double targetScore(LivingEntity e) {
    return TargetScore.score(
        distance(mc().player.getEyePosition(), e.getBoundingBox()),
        CombatPolicy.recent(System.nanoTime(), CombatEvidence.attacker(e.getUUID())),
        mc().player.hasLineOfSight(e),
        e.getHealth());
  }

  public static String reason(LivingEntity e) {
    if (!e.isAlive()) return "dead";
    if (distance(mc().player.getEyePosition(), e.getBoundingBox()) > reach())
      return "outside_reach";
    if (!visible(mc().player.getEyePosition(), e)) return "block_occluded";
    if (!canStrike(e)) return "entity_occluded_or_ray_miss";
    if (mc().player.getAttackStrengthScale(0) < .95f) return "attack_cooldown";
    if (e.hurtTime > 0) return "target_hurt_window";
    return "ready";
  }

  public static double distance(Vec3 eye, AABB b) {
    double x = Math.max(b.minX, Math.min(b.maxX, eye.x)),
        y = Math.max(b.minY, Math.min(b.maxY, eye.y)),
        z = Math.max(b.minZ, Math.min(b.maxZ, eye.z));
    return eye.distanceTo(new Vec3(x, y, z));
  }

  private static boolean visible(Vec3 from, LivingEntity e) {
    return mc().level
            .clip(
                new ClipContext(
                    from, aim(e), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc().player))
            .getType()
        == HitResult.Type.MISS;
  }

  public static boolean canStrike(LivingEntity e) {
    var p = mc().player;
    var eye = p.getEyePosition();
    if (distance(eye, e.getBoundingBox()) > reach() || !visible(eye, e) || !p.hasLineOfSight(e))
      return false;
    Vec3 aim = aim(e);
    var hit =
        net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
            p,
            eye,
            aim,
            p.getBoundingBox().expandTowards(aim.subtract(eye)).inflate(1),
            other -> !other.isSpectator() && other.isPickable(),
            reach() * reach());
    return hit != null && hit.getEntity() == e;
  }

  public static BlockPos stance(LivingEntity e) {
    BlockPos best = null;
    double distance = Double.MAX_VALUE;
    var center = e.blockPosition();
    for (int dx = -3; dx <= 3; dx++)
      for (int dz = -3; dz <= 3; dz++)
        for (int dy = -1; dy <= 1; dy++) {
          var p = center.offset(dx, dy, dz);
          if (!Navigator.stand(p)) continue;
          Vec3 eye = Navigator.foot(p).add(0, mc().player.getEyeHeight(), 0);
          if (distance(eye, e.getBoundingBox()) > reach() - .1 || !visible(eye, e)) continue;
          double d = p.distSqr(mc().player.blockPosition());
          if (d < distance) {
            distance = d;
            best = p;
          }
        }
    return best;
  }

  public static void strike(LivingEntity target) {
    Actions.look(aim(target));
    mc().gameMode.attack(mc().player, target);
    mc().player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
  }
}
