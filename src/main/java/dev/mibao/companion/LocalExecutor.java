package dev.mibao.companion;

import com.google.gson.*;
import net.minecraft.client.Minecraft;

/** One client tick owns one action lane. Model planning cannot overlap a local write action. */
public final class LocalExecutor {
  private static ExecutionPolicy.Lane lane = ExecutionPolicy.Lane.REST;

  private static void interruptInput(String message) {
    FollowControl.suspend(message);
    DutyScheduler.suspend(message);
    BaritoneNavigation.cancelActive(message);
    if (Actions.job instanceof MiningRouteJob route) route.suspendInput(message);
    if (Actions.job instanceof BaritoneMiningJob mining) mining.interruptedBy(message);
    if (Actions.job instanceof UseHandJob use) use.suspend();
    if (Actions.job instanceof PlatformJob building) building.interruptedBy(message);
    else if (Actions.job instanceof MiningJob
        || Actions.job instanceof PlaceJob
        || Actions.job instanceof AutoActionJob
        || Actions.job instanceof TargetedInteractionJob
        || Actions.job instanceof BlueprintBuildJob
        || Actions.job instanceof RollbackJob) {
      Actions.job.finish(message);
    } else if (Actions.job instanceof GameInputJob) Actions.job.finish(message);
  }

  public static void tick() {
    var mc = Minecraft.getInstance();
    boolean managing = Panel.managing(mc.screen);
    if (Cooperation.yielding()) {
      interruptInput("输入已释放：人工协同介入，请重新观察。");
      lane = ExecutionPolicy.Lane.PAUSED;
      Perception.tick();
      return;
    }
    if (Agent.paused || managing) {
      interruptInput("输入已释放：暂停或进入伙伴面板，不自动续按。");
      lane = ExecutionPolicy.Lane.PAUSED;
      PlayerControls.release();
      Perception.tick();
      return;
    }
    if (!Agent.enabled) {
      interruptInput("输入已释放：AI未接管。");
      lane = ExecutionPolicy.Lane.REST;
      PlayerControls.release();
      Perception.tick();
      return;
    }
    if (!Agent.controlWorldValid()) {
      interruptInput("输入已释放：当前世界控制已失效。");
      lane = ExecutionPolicy.Lane.REST;
      Perception.tick();
      return;
    }
    if (MiningExpedition.expire()) {
      lane = ExecutionPolicy.Lane.IDLE;
      Perception.tick();
      return;
    }
    if (Agent.session != null && Agent.session.expired()) {
      Agent.expireSession();
      lane = ExecutionPolicy.Lane.IDLE;
      Perception.tick();
      return;
    }
    if (HealthRecovery.tick()) {
      interruptInput("输入已释放：进入恢复流程，请重新观察。");
      lane = ExecutionPolicy.Lane.RECOVERY;
      Perception.tick();
      return;
    }
    if (SoundReflex.tick()) {
      interruptInput("输入已释放：触发声音避险撤离，请重新观察。");
      lane = ExecutionPolicy.Lane.RECOVERY;
      Perception.tick();
      return;
    }
    boolean defending = CombatEvidence.defend();
    if (defending) interruptInput("输入已释放：自卫介入，请重新观察。");
    lane =
        ExecutionPolicy.choose(
            Agent.enabled,
            Agent.paused,
            managing,
            !Agent.pendingTool.isBlank(),
            defending,
            mc.screen != null,
            Actions.job != null,
            Agent.production != null
                || Agent.resource != null
                || Agent.transport != null
                || Agent.restock != null
                || Agent.session != null
                || Agent.busy);
    if (!defending) Actions.tick();
    Perception.tick();
    if (!defending) Agent.tick();
    if (!defending) {
      if (DutyScheduler.isArmed()) {
        if (mc.screen != null || HarnessController.busy() || !Agent.task.isBlank()) {
          DutyScheduler.suspend("等待当前任务或界面结束");
          if (FollowControl.active()) FollowControl.suspend("等待当前任务或界面结束");
        } else {
          DutyScheduler.tick();
        }
      } else if (FollowControl.active()) {
        if (mc.screen != null || HarnessController.busy() || !Agent.task.isBlank())
          FollowControl.suspend("等待当前任务或界面结束");
        else FollowControl.tick();
      } else if (mc.screen == null && !HarnessController.busy() && Agent.task.isBlank()) {
        DutyScheduler.tickStandby();
      }
    }
  }

  public static JsonObject observe() {
    var o = new JsonObject();
    o.addProperty("lane", lane.name());
    o.addProperty("status", ActionSummary.describe(Actions.status));
    o.addProperty("rule", "权限逐动作检查；本地任务只执行正常玩家操作，菜单事务等待实际同步，模型不重放未确认点击");
    String problem =
        RestockTask.latest != null && RestockTask.latest.outcome.equals("blocked")
            ? RestockTask.latest.problem
            : Actions.status;
    if (Rules.actionFailed(problem)) o.add("recovery_advice", FailureAdvice.json(problem));
    if (Agent.session != null) o.add("work_session", Agent.session.observe());
    o.add("recovery", HealthRecovery.observe());
    o.add("sound_reflex", SoundReflex.observe());
    o.add("follow", FollowControl.status());
    o.add("duty", DutyScheduler.status());
    o.add("pickup", CollectionJob.diagnostic());
    return o;
  }
}
