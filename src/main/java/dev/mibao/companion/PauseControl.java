package dev.mibao.companion;

import com.google.gson.JsonObject;

/** Recovery is separate from ordinary game actions and never grants takeover. */
final class PauseControl {
  static boolean harnessPaused;
  static String source = "none";

  static JsonObject observe() {
    var o = new JsonObject();
    boolean dead =
        net.minecraft.client.Minecraft.getInstance().player != null
            && net.minecraft.client.Minecraft.getInstance().player.isDeadOrDying();
    boolean canResume =
        Agent.enabled
            && Agent.paused
            && !dead
            && (harnessPaused || Store.config.harnessResumeHumanPause);
    o.addProperty(
        "paused_by",
        !Agent.paused
            ? HealthRecovery.active() ? "guard" : "none"
            : dead ? "death_recovery" : harnessPaused ? "harness" : source);
    o.addProperty("can_resume", canResume);
    o.addProperty("harness_resume_human_pause", Store.config.harnessResumeHumanPause);
    o.addProperty(
        "pause_reason_code",
        dead
            ? "PLAYER_DEAD"
            : Agent.paused
                ? harnessPaused
                    ? "HARNESS_PAUSED"
                    : "human".equals(source) ? "HUMAN_PAUSED" : "SYSTEM_PAUSED"
                : HealthRecovery.active() ? "HEALTH_GUARD" : "NONE");
    o.addProperty(
        "resume_requirement",
        !Agent.enabled
            ? "game_takeover_authorization"
            : dead
                ? "wait_for_respawn_policy"
                : !Agent.paused
                    ? HealthRecovery.active() ? "auto_after_guard" : "none"
                    : canResume ? "resume_control" : "game_continue_or_allow_harness_resume");
    o.addProperty(
        "resume_hint",
        !Agent.enabled
            ? "游戏内重新开启AI接管；Harness不能撤销急停"
            : dead
                ? "等待所选死亡恢复策略，旧任务先重新观察"
                : HealthRecovery.active() && !Agent.paused
                    ? "等待生命恢复，条件满足后自动解除恢复守卫；旧动作不重放"
                    : !Agent.paused
                        ? "当前无需恢复"
                        : canResume
                            ? "调用resume_control，恢复后重新观察"
                            : "F8点击继续；或预先在权限与行为允许Harness恢复人工暂停");
    return o;
  }

  static String resume() {
    if (!observe().get("can_resume").getAsBoolean())
      return "[RESUME_REQUIRES_PLAYER] " + observe().get("resume_hint").getAsString();
    Agent.pause(false);
    harnessPaused = false;
    return "暂停已恢复；先观察当前阶段和原任务回执，不重放未确认动作";
  }
}
