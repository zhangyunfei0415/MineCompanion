package dev.mibao.companion;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.*;

/**
 * Ordinary bow only: finite shots, real draw/release, projectile/ammunition and death observations.
 */
public final class RangedHuntJob extends Actions.Job {
  private final String type;
  private final int wanted, radius, oldSlot;
  private final BlockPos center;
  private final Navigator nav = new Navigator();
  private final Set<UUID> complete = new HashSet<>(),
      skipped = new HashSet<>(),
      projectiles = new HashSet<>();
  private InventoryMove prepare;
  private InventorySession verify;
  private LivingEntity target;
  private BlockPos stance;
  private int phase, delay, confirmed, shots, arrowBefore, wait;
  private long attempt;
  private boolean drawing;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public RangedHuntJob(String type, int count, int radius, Consumer<String> done) {
    super(done);
    this.type = type;
    wanted = count;
    this.radius = radius;
    center = mc().player.blockPosition();
    oldSlot = mc().player.getInventory().selected;
    TaskReadiness.requireWorld(true);
    if (!CombatPolicy.accepted(type, Store.config.huntResources, Store.config.attackPlayers))
      throw new IllegalArgumentException("[UNSUPPORTED_TARGET] 未授权或未适配的远程目标");
  }

  private int arrows() {
    int n = 0;
    for (int i = 0; i < 41; i++) {
      var s = mc().player.getInventory().getItem(i);
      if (s.is(Items.ARROW) && !s.hasTag()) n += s.getCount();
    }
    return n;
  }

  void tick() {
    if (++ticks > 2400) {
      result("达到2分钟预算");
      return;
    }
    if (prepare != null) {
      int r = prepare.tick();
      if (r < 0) {
        result(prepare.error);
        return;
      }
      if (r > 0) {
        mc().player.getInventory().selected = prepare.readySlot;
        prepare = null;
      }
      return;
    }
    if (verify != null) {
      int r = verify.tick();
      if (r < 0) {
        result(verify.error);
        return;
      }
      if (r == 0) return;
      verify.close();
      verify = null;
      boolean projectile =
          mc()
              .level
              .getEntitiesOfClass(
                  AbstractArrow.class,
                  mc().player.getBoundingBox().inflate(20),
                  e -> !projectiles.contains(e.getUUID()))
              .stream()
              .anyMatch(e -> e.getOwner() == mc().player);
      if (arrows() >= arrowBefore
          && !projectile
          && CombatEvidence.hurt(target.getUUID()) < attempt
          && CombatEvidence.death(target.getUUID()) < attempt) {
        result("射击效果未确认，停止且不重放该次释放");
        return;
      }
      phase = 3;
      delay = 0;
      return;
    }
    if (mc().screen != null) {
      result("菜单改变");
      return;
    }
    if (SurvivalPolicy.blocked(
        mc().player.getHealth(),
        SurvivalPolicy.combat(mc().player.getMaxHealth()),
        Store.config.testIgnoreLowHealth)) {
      result("生命偏低，交给恢复通道");
      return;
    }
    if (confirmed >= wanted) {
      result("目标满足");
      return;
    }
    if (shots >= 24 || skipped.size() >= 12) {
      result("射击或目标恢复预算耗尽");
      return;
    }
    if (mc().player.blockPosition().distSqr(center) > (radius + 4.0) * (radius + 4.0)) {
      result("偏离指定区域");
      return;
    }
    if (!mc().player.getMainHandItem().is(Items.BOW)) {
      int found = -1;
      for (int i = 0; i < 36; i++) {
        var s = mc().player.getInventory().getItem(i);
        if (s.is(Items.BOW) && s.getMaxDamage() - s.getDamageValue() > 2) {
          found = i;
          break;
        }
      }
      if (found < 0) {
        result("需要有耐久的原版弓");
        return;
      }
      if (found < 9) mc().player.getInventory().selected = found;
      else {
        String fp = WorldSense.fingerprint(mc().player.getInventory().getItem(found));
        prepare = InventoryMove.ensure(s -> !s.isEmpty() && WorldSense.fingerprint(s).equals(fp));
        return;
      }
    }
    if (target != null
        && attempt > 0
        && CombatPolicy.deathConfirmed(attempt, CombatEvidence.death(target.getUUID()))) {
      if (complete.add(target.getUUID())) confirmed++;
      release();
      target = null;
      phase = 0;
      stance = null;
      return;
    }
    if (target != null
        && (!MeleeOps.eligible(target, type)
            || target.blockPosition().distSqr(center) > radius * radius)) {
      release();
      if (++wait < 25) return;
      skipped.add(target.getUUID());
      target = null;
      phase = 0;
      stance = null;
      wait = 0;
    }
    if (target == null) {
      var list =
          mc().level
              .getEntitiesOfClass(
                  LivingEntity.class,
                  new AABB(center).inflate(radius),
                  e ->
                      MeleeOps.eligible(e, type)
                          && !complete.contains(e.getUUID())
                          && !skipped.contains(e.getUUID())
                          && mc().player.hasLineOfSight(e));
      list.sort(Comparator.comparingDouble(MeleeOps::targetScore));
      if (list.isEmpty()) {
        result("没有更多当前已加载可见目标");
        return;
      }
      target = list.get(0);
      HuntJob.focus = target.getUUID();
      wait = 0;
      attempt = 0;
    }
    Actions.status =
        HuntJob.detail =
            "弓箭 · " + target.getName().getString() + " · 发射 " + shots + " · 确认死亡 " + confirmed;
    if (phase == 3) {
      if (++delay < 20) return;
      phase = 0;
    }
    if (phase == 0) {
      double distance = mc().player.distanceTo(target);
      if (distance < 5 || distance > 14 || !mc().player.hasLineOfSight(target)) {
        if (stance == null || ++delay % 12 == 0) {
          stance = bowStance();
          if (stance == null) {
            skipped.add(target.getUUID());
            target = null;
            return;
          }
          if (!nav.startExact(stance)) {
            skipped.add(target.getUUID());
            target = null;
            stance = null;
            return;
          }
        }
        int r = nav.tick();
        if (r != 0) stance = null;
        return;
      }
      nav.stop();
      stance = null;
      var ammo = mc().player.getProjectile(mc().player.getMainHandItem());
      if (!ammo.is(Items.ARROW) || ammo.hasTag()) {
        result("基础弓模式只适配普通箭；未擅自使用药箭或未知弹药");
        return;
      }
      arrowBefore = arrows();
      projectiles.clear();
      for (var e :
          mc().level
              .getEntitiesOfClass(AbstractArrow.class, mc().player.getBoundingBox().inflate(20)))
        projectiles.add(e.getUUID());
      aim();
      mc().gameMode.useItem(mc().player, InteractionHand.MAIN_HAND);
      if (!mc().player.isUsingItem()) {
        result("弓未进入拉弓状态");
        return;
      }
      PlayerControls.holdFood();
      drawing = true;
      phase = 1;
      delay = 0;
      return;
    }
    if (phase == 1) {
      if (!mc().player.isUsingItem()) {
        result("拉弓被中断，未再次激活");
        return;
      }
      aim();
      if (++delay < BowAim.chargeTicks()) return;
      if (!mc().player.hasLineOfSight(target)) {
        result("释放前目标被遮挡，停止；请重新观察");
        return;
      }
      attempt = System.nanoTime();
      mc().gameMode.releaseUsingItem(mc().player);
      drawing = false;
      PlayerControls.release();
      shots++;
      phase = 2;
      delay = 0;
      return;
    }
    if (phase == 2 && ++delay >= 6) verify = new InventorySession(false);
  }

  private void aim() {
    var p = mc().player;
    var eye = p.getEyePosition();
    var b = target.getBoundingBox();
    double distance = eye.distanceTo(b.getCenter());
    var lead = target.getDeltaMovement().scale(Math.min(4, distance / 3));
    var point = b.getCenter().add(lead);
    var d = point.subtract(eye);
    double horizontal = Math.sqrt(d.x * d.x + d.z * d.z),
        angle = BowAim.elevation(horizontal, d.y, 3);
    p.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
    p.setXRot((float) -Math.toDegrees(angle));
  }

  private BlockPos bowStance() {
    BlockPos best = null;
    double score = Double.MAX_VALUE;
    for (int x = -10; x <= 10; x += 2)
      for (int z = -10; z <= 10; z += 2)
        for (int y = -1; y <= 1; y++) {
          var at = target.blockPosition().offset(x, y, z);
          if (!Navigator.stand(at)) continue;
          var eye = Navigator.foot(at).add(0, mc().player.getEyeHeight(), 0);
          double d = eye.distanceTo(target.getBoundingBox().getCenter());
          if (d < 6 || d > 12 || at.distSqr(center) > (radius + 2.0) * (radius + 2.0)) continue;
          var ray =
              mc().level
                  .clip(
                      new net.minecraft.world.level.ClipContext(
                          eye,
                          target.getBoundingBox().getCenter(),
                          net.minecraft.world.level.ClipContext.Block.COLLIDER,
                          net.minecraft.world.level.ClipContext.Fluid.NONE,
                          mc().player));
          if (ray.getType() != HitResult.Type.MISS) continue;
          double value = at.distSqr(mc().player.blockPosition());
          if (value < score) {
            score = value;
            best = at;
          }
        }
    return best;
  }

  private void release() {
    if (drawing) {
      mc().gameMode.releaseUsingItem(mc().player);
      PlayerControls.release();
      drawing = false;
    }
    nav.stop();
  }

  private void result(String why) {
    finish(
        (confirmed >= wanted ? "成功：" : "部分完成：")
            + "弓模式已确认 "
            + confirmed
            + " / "
            + wanted
            + " 个目标死亡；已释放 "
            + shots
            + " 箭。"
            + why
            + "；发射/死亡观察不证明击杀归属。");
  }

  void stop() {
    release();
    if (prepare != null) prepare.stop();
    if (verify != null) verify.close();
    if (mc().player != null) mc().player.getInventory().selected = oldSlot;
    HuntJob.focus = null;
    HuntJob.detail = "战斗已停止";
    super.stop();
  }

  String interrupted() {
    return "远程作业已停止；释放使用按键可能射出已蓄力箭，需重新观察；不会自动补射。";
  }
}
