package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 0ms client-side high-priority reflex lane triggered by lethal primed explosives or near lava
 * while mining. Runs inside LocalExecutor right after HealthRecovery and before CombatEvidence.
 */
public final class SoundReflex {
  private static boolean active, holdInPlaceSafe;
  private static int ticksLeft;
  private static double threatX, threatY, threatZ;
  private static float retreatYaw;
  private static long lastDangerMs, lastLavaStopMs;
  private static String lastDetail = "无";

  private SoundReflex() {}

  public static void reset() {
    active = false;
    holdInPlaceSafe = false;
    ticksLeft = 0;
    lastDetail = "无";
    PlayerControls.release();
  }

  public static boolean active() {
    return active;
  }

  private static boolean canReflexNow() {
    var mc = Minecraft.getInstance();
    return Agent.enabled
        && !Agent.paused
        && Store.config.soundReflex
        && mc.player != null
        && mc.level != null
        && !mc.player.isDeadOrDying()
        && !HealthRecovery.active()
        && !Panel.managing(mc.screen);
  }

  public static void triggerDanger(String soundId, double sx, double sy, double sz, double dist) {
    long now = System.currentTimeMillis();
    if (now - lastDangerMs < 800 || !canReflexNow()) return;
    lastDangerMs = now;
    threatX = sx;
    threatY = sy;
    threatZ = sz;

    String reason =
        "部分完成：[SOUND_REFLEX] 检测到致命声源 "
            + soundId
            + "（距 "
            + String.format(Locale.ROOT, "%.1f", dist)
            + "m），已中断作业并本地冲刺撤离，请重新观察四周安全。";
    interruptJobs(reason);
    retreatYaw = pickSafeRetreatYaw(sx, sz);
    holdInPlaceSafe = !isForwardSafe(Minecraft.getInstance().player.position(), retreatYaw);
    active = true;
    ticksLeft = 34;
    lastDetail = (holdInPlaceSafe ? "高空原地避险 " : "正在撤离 ") + soundId;
    Actions.status = "声音避险 · " + lastDetail;
    Companion.notifyUser("高危警报：" + soundId + (holdInPlaceSafe ? "（四周高空落差，已原地潜行防坠）！" : "，已触发本地快速撤离！"));
  }

  public static void triggerMiningLavaStop(
      String soundId, double sx, double sy, double sz, double dist) {
    long now = System.currentTimeMillis();
    if (now - lastLavaStopMs < 2000 || !canReflexNow()) return;
    boolean mining =
        Actions.job instanceof MiningJob
            || Actions.job instanceof MiningRouteJob
            || Actions.job instanceof BaritoneMiningJob
            || (Actions.job instanceof AutoActionJob auto && auto.isMineMode());
    if (!mining) return;
    lastLavaStopMs = now;
    String reason =
        "部分完成：[SOUND_REFLEX_LAVA] 挖掘期间近处出现熔岩冒泡音（"
            + String.format(Locale.ROOT, "%.1f", dist)
            + "m），已紧急停镐，请用 inspect_block 确认前方安全。";
    interruptJobs(reason);
    lastDetail = "已停镐避让熔岩（" + String.format(Locale.ROOT, "%.1f", dist) + "m）";
    Companion.notifyUser("声音警报：近处检测到 " + soundId + "，已暂停挖掘！");
  }

  private static void interruptJobs(String reason) {
    var mc = Minecraft.getInstance();
    if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    FollowControl.suspend(reason);
    DutyScheduler.suspend(reason);
    BaritoneNavigation.cancelActive(reason);
    Navigator.release();
    MiningExpedition.cancelActive();
    Agent.clearContinuationTasksForSafety();
    if (Actions.job != null) {
      Actions.job.finish(reason);
    }
  }

  public static boolean tick() {
    if (!active) return false;
    var mc = Minecraft.getInstance();
    if (!canReflexNow() || mc.screen != null || --ticksLeft <= 0) {
      end("声音避险撤离结束；请重新观察现场安全。");
      return false;
    }
    double dx = mc.player.getX() - threatX;
    double dy = mc.player.getY() - threatY;
    double dz = mc.player.getZ() - threatZ;
    if (dx * dx + dy * dy + dz * dz >= 81.0) {
      end("已撤离至 9 格安全距离外。");
      return false;
    }
    if (!isForwardSafe(mc.player.position(), retreatYaw)) {
      retreatYaw = pickSafeRetreatYaw(threatX, threatZ);
      holdInPlaceSafe = !isForwardSafe(mc.player.position(), retreatYaw);
    } else {
      holdInPlaceSafe = false;
    }
    if (holdInPlaceSafe) {
      // Never sprint off a 1-wide high-altitude scaffold/cliff when no safe retreat direction exists.
      PlayerControls.motionState(false, false, false, true);
      Actions.status = "声音避险 · 高空防坠原地潜行";
      return true;
    }
    mc.player.setYRot(retreatYaw);
    mc.player.setYHeadRot(retreatYaw);
    boolean jump = mc.player.horizontalCollision && mc.player.onGround();
    PlayerControls.motionState(true, jump, true, false);
    Actions.status = "声音避险 · " + lastDetail;
    return true;
  }

  private static void end(String status) {
    active = false;
    holdInPlaceSafe = false;
    ticksLeft = 0;
    PlayerControls.release();
    lastDetail = status;
    Actions.status = status;
  }

  private static float pickSafeRetreatYaw(double sx, double sz) {
    var player = Minecraft.getInstance().player;
    if (player == null) return 0f;
    double awayX = player.getX() - sx;
    double awayZ = player.getZ() - sz;
    float baseYaw =
        Math.hypot(awayX, awayZ) < 0.1
            ? player.getYRot()
            : (float) (Math.toDegrees(Math.atan2(awayZ, awayX)) - 90.0);
    float[] offsets = {0f, 35f, -35f, 70f, -70f, 110f, -110f, 145f, -145f, 180f};
    for (float off : offsets) {
      float cand = baseYaw + off;
      if (isForwardSafe(player.position(), cand)) return cand;
    }
    return baseYaw;
  }

  private static boolean isForwardSafe(Vec3 pos, float yawDeg) {
    var level = Minecraft.getInstance().level;
    if (level == null) return false;
    double rad = Math.toRadians(yawDeg);
    double fx = -Math.sin(rad);
    double fz = Math.cos(rad);
    for (int step = 1; step <= 3; step++) {
      BlockPos foot = BlockPos.containing(pos.x + fx * step, pos.y + 0.1, pos.z + fz * step);
      if (!level.hasChunkAt(foot)) return false;
      if (!level.getFluidState(foot).isEmpty() || !level.getFluidState(foot.above()).isEmpty())
        return false;
      boolean hasFloor = false;
      for (int drop = 1; drop <= 2; drop++) {
        BlockPos below = foot.below(drop);
        if (!level.hasChunkAt(below)) break;
        if (!level.getFluidState(below).isEmpty()) return false;
        if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
          hasFloor = true;
          break;
        }
      }
      if (!hasFloor) return false;
    }
    return true;
  }

  public static JsonObject observe() {
    var o = new JsonObject();
    o.addProperty("active", active);
    o.addProperty("enabled", Store.config.soundReflex);
    o.addProperty("detail", lastDetail);
    return o;
  }
}
