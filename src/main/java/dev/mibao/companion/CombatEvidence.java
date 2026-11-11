package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;

/** Main-thread server damage/death receipts, scoped to the currently loaded dimension. */
public final class CombatEvidence {
  private static final Map<UUID, Long> attackers = new LinkedHashMap<>(),
      deaths = new LinkedHashMap<>(),
      hurts = new LinkedHashMap<>();
  private static String dimension = "";
  private static long defenseStarted;
  private static InventoryMove prepare;
  private static final Navigator defenseNav = new Navigator();
  private static net.minecraft.core.BlockPos defenseDestination;
  private static int oldSlot = -1, defenseTicks;
  private static boolean shielding;
  private static long restUntil;
  public static UUID focus;

  public static void reset() {
    attackers.clear();
    deaths.clear();
    hurts.clear();
    dimension = "";
    defenseStarted = 0;
    focus = null;
    prepare = null;
    oldSlot = -1;
    shielding = false;
    defenseDestination = null;
    restUntil = 0;
    defenseTicks = 0;
  }

  private static void scope() {
    var level = Minecraft.getInstance().level;
    String d = level == null ? "" : level.dimension().location().toString();
    if (!dimension.equals(d)) {
      reset();
      dimension = d;
    }
  }

  private static void put(Map<UUID, Long> map, UUID id) {
    if (map.size() >= 128) map.remove(map.keySet().iterator().next());
    map.put(id, System.nanoTime());
  }

  public static void damage(ClientboundDamageEventPacket packet) {
    scope();
    var mc = Minecraft.getInstance();
    var injured = mc.level.getEntity(packet.entityId());
    if (injured != null) put(hurts, injured.getUUID());
    if (packet.entityId() != mc.player.getId()) return;
    Entity source = packet.getSource(mc.level).getEntity();
    if (source instanceof Monster) put(attackers, source.getUUID());
  }

  public static void event(ClientboundEntityEventPacket packet) {
    scope();
    if (packet.getEventId() != 3) return;
    var entity = packet.getEntity(Minecraft.getInstance().level);
    if (entity != null) put(deaths, entity.getUUID());
  }

  public static long hurt(UUID id) {
    scope();
    return hurts.getOrDefault(id, 0L);
  }

  public static long death(UUID id) {
    scope();
    return deaths.getOrDefault(id, 0L);
  }

  public static long attacker(UUID id) {
    scope();
    return attackers.getOrDefault(id, 0L);
  }

  public static void cancelDefense() {
    if (defenseStarted == 0 && prepare == null && oldSlot < 0 && !shielding) {
      focus = null;
      return;
    }
    if (prepare != null) prepare.stop();
    prepare = null;
    defenseNav.stop();
    defenseDestination = null;
    if (shielding) {
      var mc = Minecraft.getInstance();
      if (mc.player != null && mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
      PlayerControls.release();
    }
    shielding = false;
    if (oldSlot >= 0 && Minecraft.getInstance().player != null)
      Minecraft.getInstance().player.getInventory().selected = oldSlot;
    oldSlot = -1;
    defenseStarted = 0;
    focus = null;
    if (Actions.status.startsWith("自卫"))
      Actions.status = "自卫已结束；" + (Actions.job == null ? "等待任务" : "重新核对原作业");
  }

  /** Only actual damage sources trigger self-defense. Client mob AI targets are not synced. */
  public static boolean defend() {
    scope();
    var mc = Minecraft.getInstance();
    long now = System.nanoTime();
    if (!Store.config.selfDefense
        || !Agent.enabled
        || Agent.paused
        || (Actions.job instanceof HuntJob || Actions.job instanceof RangedHuntJob)
        || now < restUntil) {
      cancelDefense();
      return false;
    }
    if (Actions.job instanceof UseHandJob || Actions.job instanceof EatJob) {
      cancelDefense();
      return false;
    }
    if (prepare != null) {
      int r = prepare.tick();
      if (r == 0) return true;
      if (r > 0) mc.player.getInventory().selected = prepare.readySlot;
      else {
        WorkLedger.add("combat", prepare.error);
        restUntil = now + 2_000_000_000L;
      }
      prepare = null;
      return true;
    }
    if (mc.screen != null) {
      cancelDefense();
      return false;
    }
    attackers.entrySet().removeIf(e -> !CombatPolicy.recent(now, e.getValue()));
    var threats =
        mc.level.getEntitiesOfClass(
            Monster.class,
            mc.player.getBoundingBox().inflate(6),
            m ->
                m.isAlive()
                    && (attackers.containsKey(m.getUUID())
                        || Store.config.proactiveDefense
                            && MeleeOps.eligible(m, "hostile")
                            && mc.player.distanceToSqr(m) < 16)
                    && mc.player.hasLineOfSight(m));
    if (threats.isEmpty()) {
      cancelDefense();
      return false;
    }
    if (defenseStarted == 0) {
      defenseStarted = now;
      Navigator.release();
    }
    if (SurvivalPolicy.blocked(
        mc.player.getHealth(),
        SurvivalPolicy.work(mc.player.getMaxHealth()),
        Store.config.testIgnoreLowHealth)) {
      cancelDefense();
      return false;
    }
    if (now - defenseStarted > Rules.bounded(Store.config.defenseSeconds, 5, 60) * 1_000_000_000L) {
      WorkLedger.add("combat", "自卫窗口结束，重新评估而不关闭AI接管");
      cancelDefense();
      restUntil = now + 1_000_000_000L;
      return true;
    }
    // Avoid provoking explosive/teleporting/boss mobs with a generic melee routine.
    threats.sort(java.util.Comparator.comparingDouble(MeleeOps::targetScore));
    var mob = threats.get(0);
    focus = mob.getUUID();
    String id =
        net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
            .getKey(mob.getType())
            .toString();
    if (!CombatPolicy.HUNTABLE.contains(id)) {
      cancelDefense();
      restUntil = now + 2_000_000_000L;
      WorkLedger.add("combat", "未适配威胁 " + id + "，等待高层安排撤离/对应技能");
      return true;
    }
    if (oldSlot < 0) oldSlot = mc.player.getInventory().selected;
    int weapon = MeleeOps.bestWeapon(false);
    if (weapon < 0) {
      cancelDefense();
      restUntil = now + 2_000_000_000L;
      WorkLedger.add("combat", "缺少近战武器，需上层准备或撤离");
      return true;
    }
    if (weapon >= 9) {
      String fp = WorldSense.fingerprint(mc.player.getInventory().getItem(weapon));
      prepare = InventoryMove.ensure(s -> !s.isEmpty() && WorldSense.fingerprint(s).equals(fp));
      return true;
    }
    mc.player.getInventory().selected = weapon;
    if (!MeleeOps.canStrike(mob)) {
      releaseShield();
      if (defenseDestination == null || ++defenseTicks % 10 == 0) {
        defenseDestination = MeleeOps.stance(mob);
        if (defenseDestination != null) defenseNav.startExact(defenseDestination);
      }
      if (defenseDestination != null && defenseNav.tick() != 0) defenseDestination = null;
      Actions.status = "自卫接近 · " + MeleeOps.reason(mob);
      return true;
    }
    if (defenseDestination != null) {
      defenseNav.stop();
      defenseDestination = null;
    }
    Actions.look(MeleeOps.aim(mob));
    if (mc.player.getAttackStrengthScale(0) >= .95f && mob.hurtTime == 0) {
      releaseShield();
      MeleeOps.strike(mob);
    } else if (mc.player.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem
        && !mc.player.getCooldowns().isOnCooldown(mc.player.getOffhandItem().getItem())) {
      if (!mc.player.isUsingItem())
        mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.OFF_HAND);
      if (mc.player.isUsingItem()) {
        PlayerControls.holdFood();
        shielding = true;
      }
    }
    Actions.status = "自卫 · " + MeleeOps.reason(mob) + "；战后重新核对原作业";
    return true;
  }

  private static void releaseShield() {
    if (shielding) {
      var mc = Minecraft.getInstance();
      if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
      PlayerControls.release();
      shielding = false;
    }
  }
}
