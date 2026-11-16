package dev.mibao.companion;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;

/** Player-selected recovery sends the normal respawn request once and invalidates old work. */
final class DeathRecovery {
  private static long epoch;
  private static String scope = "", target = "", mode = "";
  private static int wait;
  private static boolean pending, sent;
  private static String phase = "alive";
  private static Object world;
  private static long diedAt, respawnedAt;

  static JsonObject observe() {
    var o = new JsonObject();
    o.addProperty("phase", phase);
    o.addProperty("policy", Store.config.deathRecovery);
    o.addProperty("respawn_policy", automatic(Store.config.deathRecovery) ? "auto" : "manual");
    o.addProperty("awaiting_respawn", pending && !phase.equals("resume_pending"));
    o.addProperty("requires_human", pending && !automatic(mode) || phase.equals("resume_pending"));
    o.addProperty("respawn_request_sent", sent);
    o.addProperty("died_at", diedAt);
    o.addProperty("respawned_at", respawnedAt);
    o.addProperty(
        "resume_requirement",
        !Agent.enabled
            ? "game_takeover_authorization"
            : phase.equals("resume_pending")
                ? "human_in_game_or_authorized_resume_control"
                : pending ? automatic(mode) ? "auto_after_respawn" : "human_respawn" : "none");
    return o;
  }

  static boolean automatic(String policy) {
    return DeathPolicy.automatic(policy);
  }

  static void tick() {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) return;
    if (pending && (!Agent.enabled || Agent.epoch != epoch || !scope.equals(Store.scope()))) {
      pending = false;
      phase = "cancelled";
      return;
    }
    if (!pending) {
      if (!Agent.enabled || !mc.player.isDeadOrDying()) {
        if (!Agent.paused && !mc.player.isDeadOrDying()) phase = "alive";
        return;
      }
      // A player stop during this death must not restart recovery on a later frame.
      if (world == mc.level && java.util.Set.of("cancelled", "resume_pending").contains(phase))
        return;
      var record = new JsonObject();
      diedAt = System.currentTimeMillis();
      record.addProperty("dimension", mc.level.dimension().location().toString());
      record.addProperty("x", mc.player.getX());
      record.addProperty("y", mc.player.getY());
      record.addProperty("z", mc.player.getZ());
      record.addProperty("at_ms", System.currentTimeMillis());
      record.addProperty("target", Agent.task);
      record.addProperty("inventory", "死亡前随身数据，掉落实体与回收状态待重新观察");
      Store.data.checkpoints.put("death:last", record);
      Store.save();
      mode = Store.config.deathRecovery;
      target = Agent.task;
      Agent.suspendForDeath();
      epoch = Agent.epoch;
      scope = Store.scope();
      pending = true;
      world = mc.level;
      phase = "died";
      sent = false;
      wait = 0;
    }
    if (!mc.player.isDeadOrDying()) {
      pending = false;
      phase = "respawned";
      respawnedAt = System.currentTimeMillis();
      if (DeathPolicy.canResume(mode, Agent.enabled, Agent.epoch == epoch, true))
        Agent.resumeAfterDeath(mode.equals("REASSESS") ? target : "");
      else {
        phase = "resume_pending";
        Agent.state = "已人工重生；接管与连接保留，请继续或调用已授权的resume_control";
      }
      return;
    }
    phase = "awaiting_respawn";
    if (!automatic(mode)) return;
    if (++wait > 400) {
      pending = false;
      phase = "resume_pending";
      Agent.state = "自动重生等待超时；接管与连接保留，请检查死亡界面";
      return;
    }
    if (!sent && wait >= 20 && mc.screen instanceof DeathScreen) {
      mc.player.respawn();
      sent = true;
    }
  }

  private DeathRecovery() {}
}
