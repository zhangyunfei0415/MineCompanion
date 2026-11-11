package dev.mibao.companion;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

/** Bounded player melee, with stable target lock and observed damage/death receipts. */
public final class HuntJob extends Actions.Job {
  private final Navigator nav = new Navigator();
  private InventoryMove prepare;
  private final WorkPlan plan;
  private final BlockPos center;
  private final String type;
  private final int radius;
  private final TargetRecovery<UUID> recovery = new TargetRecovery<>(2, 8);
  private net.minecraft.world.entity.LivingEntity target;
  private UUID identity;
  private BlockPos destination, targetLastPos;
  private int confirmed, waiting, replans, empty, swings, seekTick;
  private long attempted, lastAttack, lastHurt;
  private boolean equipped;
  public static String detail = "未战斗";
  public static UUID focus;
  private int unconfirmedLost;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public HuntJob(String type, int count, int radius, Consumer<String> done) {
    super(done);
    this.type = type;
    this.radius = radius;
    plan = new WorkPlan(new WorkPlan.Step(WorkPlan.ScanType.ENTITY, type, count, radius));
    if (!CombatPolicy.accepted(type, Store.config.huntResources, Store.config.attackPlayers))
      throw new IllegalArgumentException(
          "[UNSUPPORTED_TARGET]"
              + " 支持hostile或明确ID的普通敌怪/原版资源动物；资源动物与玩家按独立开关，命名/宠物/Boss仍受筛选；水生目标不承诺入水追击");
    if (mc().gameMode.getPlayerMode() != GameType.SURVIVAL)
      throw new IllegalArgumentException("清怪仅支持生存模式");
    center = mc().player.blockPosition();
  }

  private void result(String why) {
    finish(
        (plan.isDone() ? "成功：" : "部分完成：")
            + "已确认 "
            + confirmed
            + " / "
            + plan.steps.get(0).count()
            + " 个目标死亡；另有 "
            + unconfirmedLost
            + " 次目标丢失未计数。"
            + why
            + " 服务器死亡观察不代表击杀归属或掉落已拾取。");
  }

  @Override
  void tick() {
    if (++ticks > 2400) {
      result("达到2分钟预算。");
      return;
    }
    if (prepare != null) {
      detail = Actions.status = "战斗准备 · 从背包取近战武器";
      int r = prepare.tick();
      if (r < 0) {
        result(prepare.error);
        return;
      }
      if (r > 0) {
        mc().player.getInventory().selected = prepare.readySlot;
        prepare = null;
        equipped = true;
      }
      return;
    }
    if (mc().screen != null) {
      result("其他界面已打开。");
      return;
    }
    if (SurvivalPolicy.blocked(
            mc().player.getHealth(),
            SurvivalPolicy.combat(mc().player.getMaxHealth()),
            Store.config.testIgnoreLowHealth)
        || mc().player.getFoodData().getFoodLevel() < 6) {
      result("生命或饱食不足，请协助撤离。");
      return;
    }
    if (!ClientEvidence.attached()) {
      result("服务器证据接收器不可用。");
      return;
    }
    if (mc().player.blockPosition().distSqr(center) > (radius + 3.0) * (radius + 3.0)) {
      result("偏离指定区域。");
      return;
    }
    if (plan.isDone()) {
      result("目标数量已满足。");
      return;
    }
    if (!equipped || !MeleeOps.weapon(mc().player.getMainHandItem())) {
      int slot = MeleeOps.bestWeapon(false);
      if (slot < 0) {
        result("需要耐久足够、有近战攻击属性的武器。");
        return;
      }
      if (slot >= 9) {
        String fp = WorldSense.fingerprint(mc().player.getInventory().getItem(slot));
        prepare = InventoryMove.ensure(s -> !s.isEmpty() && WorldSense.fingerprint(s).equals(fp));
        return;
      }
      mc().player.getInventory().selected = slot;
      equipped = true;
    }
    var danger =
        mc().level
            .getEntitiesOfClass(
                Monster.class,
                mc().player.getBoundingBox().inflate(4),
                m ->
                    m.isAlive()
                        && !CombatPolicy.HUNTABLE.contains(
                            BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString())
                        && mc().player.hasLineOfSight(m));
    if (!danger.isEmpty()) {
      result(
          "近处有未适配威胁 " + BuiltInRegistries.ENTITY_TYPE.getKey(danger.get(0).getType()) + "，停止通用近战。");
      return;
    }
    if (target == null) {
      detail = Actions.status = "索敌 · " + type + " · 已确认 " + confirmed;
      if (++empty % 5 != 1) return;
      var found =
          mc().level
              .getEntitiesOfClass(
                  net.minecraft.world.entity.LivingEntity.class,
                  new AABB(center).inflate(radius),
                  m ->
                      recovery.available(m.getUUID(), ticks)
                          && m.blockPosition().distSqr(center) <= (double) radius * radius
                          && MeleeOps.eligible(m, type)
                          && mc().player.hasLineOfSight(m));
      found.sort(Comparator.comparingDouble(MeleeOps::targetScore));
      if (found.isEmpty()) {
        if (recovery.waiting(ticks)) return;
        if (empty >= 100) result("没有更多已加载可见目标。" + recovery.lastReason());
        return;
      }
      target = found.get(0);
      identity = target.getUUID();
      focus = identity;
      waiting = replans = swings = 0;
      attempted = lastAttack = lastHurt = 0;
      destination = null;
      empty = 0;
      return;
    }
    if (CombatPolicy.deathConfirmed(attempted, CombatEvidence.death(identity))) {
      if (plan.verify(identity.toString())) confirmed++;
      nav.stop();
      target = null;
      focus = null;
      return;
    }
    if (++waiting > 600) {
      result("单目标30秒预算耗尽。");
      return;
    }
    if (target.isRemoved() || !target.isAlive()) {
      nav.stop();
      if (++seekTick > 30) {
        unconfirmedLost++;
        skip();
      }
      return;
    }
    seekTick = 0;
    if (!target.getUUID().equals(identity)
        || !MeleeOps.eligible(target, type)
        || target.blockPosition().distSqr(center) > (double) radius * radius) {
      skip();
      return;
    }
    long hurt = CombatEvidence.hurt(identity);
    if (hurt > lastHurt) lastHurt = hurt;
    if (swings >= 4 && lastHurt < attempted && System.nanoTime() - attempted > 3_000_000_000L) {
      recovery.reject(identity, ticks, 60, "该目标连续出手未观察受伤，暂避并重新索敌。");
      target = null;
      focus = null;
      nav.stop();
      if (recovery.full()) result("伤害/目标恢复预算耗尽。");
      return;
    }
    if (!MeleeOps.canStrike(target)) {
      detail =
          Actions.status =
              "接近目标 · " + target.getName().getString() + " · 寻找可见攻击站位 · " + MeleeOps.reason(target);
      if (destination == null
          || waiting % 10 == 0
              && targetLastPos != null
              && targetLastPos.distSqr(target.blockPosition()) > .5) {
        if (++replans > 100) {
          result("移动目标重规划达到上限。");
          return;
        }
        targetLastPos = target.blockPosition();
        destination = MeleeOps.stance(target);
        if (destination == null || !nav.startExact(destination)) {
          skip();
          return;
        }
      }
      int r = nav.tick();
      if (r < 0) {
        skip();
        return;
      }
      if (r > 0) destination = null;
      return;
    }
    nav.stop();
    destination = null;
    Actions.look(MeleeOps.aim(target));
    detail =
        Actions.status =
            "近战 · "
                + target.getName().getString()
                + " · 出手 "
                + swings
                + " · "
                + (lastHurt >= attempted && attempted > 0 ? "已观察受伤" : "等待伤害证据");
    if (mc().player.getAttackStrengthScale(0) < .95f || target.hurtTime > 0) return;
    if (++swings > 24) {
      result("24次出手没有完成目标。");
      return;
    }
    lastAttack = System.nanoTime();
    if (attempted == 0) attempted = lastAttack;
    MeleeOps.strike(target);
  }

  private void skip() {
    if (target != null) recovery.reject(target.getUUID(), ticks, 60, "目标离开区域或没有可达攻击站位；每目标最多尝试2次。");
    target = null;
    focus = null;
    nav.stop();
    if (recovery.full()) result("8个目标恢复预算耗尽。" + recovery.lastReason());
  }

  @Override
  void stop() {
    if (prepare != null) prepare.stop();
    nav.stop();
    detail = "战斗已停止";
    focus = null;
    super.stop();
  }

  @Override
  String interrupted() {
    return "战斗已停止；已确认死亡 " + confirmed + "，未确认目标不计数。";
  }
}
