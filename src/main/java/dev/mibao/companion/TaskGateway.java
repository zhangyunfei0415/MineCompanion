package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/**
 * Small asynchronous facade over existing tasks, not a second executor. Stored receipts never
 * replay.
 */
final class TaskGateway {
  private static final String RUN = UUID.randomUUID().toString();
  private static String activeId = "";

  static boolean supports(String tool) {
    return TaskActions.ALLOWED.contains(tool);
  }

  static JsonObject submit(JsonObject input) {
    String id = Agent.str(input, "task_id", UUID.randomUUID().toString());
    if (!id.matches("[A-Za-z0-9_.-]{1,80}"))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] task_id为1..80字母数字/._-");
    String tool = SkillCatalog.required(input, "tool");
    if (!supports(tool)) throw new IllegalArgumentException(TaskActions.unsupported(tool));
    JsonObject args = Agent.obj(input, "args").deepCopy();
    String signature = TaskIdentity.signature(tool, args);
    String key = "task:" + id;
    if (Store.data.checkpoints.containsKey(key)) {
      var old = Store.data.checkpoints.get(key);
      if (!Agent.str(old, "signature", "").equals(signature))
        throw new IllegalArgumentException("[TASK_ID_CONFLICT] 同ID的目标/参数不同，拒绝覆盖");
      return receipt(id);
    }
    if (DutyScheduler.ownsCurrentJob() && !HarnessController.foregroundBusy()) {
      DutyScheduler.yieldCurrentJob("task_submit 抢占：" + tool);
    }
    if (HarnessController.foregroundBusy()) throw new IllegalArgumentException("[BUSY] 已有任务，不叠加执行");
    long count =
        Store.data.checkpoints.keySet().stream().filter(k -> k.startsWith("task:")).count();
    if (count >= 1024)
      throw new IllegalArgumentException(
          "[TASK_LEDGER_FULL] 本世界已有1024个任务ID；保留去重记录，不静默遗忘后重放，请备份整理账本");
    var context = ActionContext.capture();
    var record = new JsonObject();
    record.addProperty("task_id", id);
    record.addProperty("tool", tool);
    record.addProperty("signature", signature);
    if (input.has("decision_context"))
      record.add("decision_context", input.get("decision_context"));
    record.addProperty("run", RUN);
    record.addProperty("epoch", context.epoch());
    record.addProperty("dimension", context.dimension());
    record.addProperty("scope", context.scope());
    record.addProperty("status", "submitted");
    record.addProperty("current_phase", "已提交：" + tool);
    record.addProperty("started_at", System.currentTimeMillis());
    Store.data.checkpoints.put(key, record);
    if (!Store.saveWorld()) {
      Store.data.checkpoints.remove(key);
      throw new IllegalArgumentException("[TASK_LEDGER_UNWRITABLE] 无法保存任务ID，未派发动作");
    }
    long detailed = Store.data.checkpoints.values().stream().filter(v -> v.has("result")).count();
    if (detailed >= 64) {
      String oldest =
          Store.data.checkpoints.entrySet().stream()
              .filter(
                  e ->
                      e.getKey().startsWith("task:")
                          && !e.getKey().equals(key)
                          && e.getValue().has("result"))
              .min(
                  Comparator.comparingLong(
                      e ->
                          e.getValue().has("started_at")
                              ? e.getValue().get("started_at").getAsLong()
                              : 0))
              .map(Map.Entry::getKey)
              .orElse(null);
      if (oldest != null) {
        var archived = Store.data.checkpoints.get(oldest);
        archived.remove("result");
        archived.remove("mining_route");
        archived.remove("harvest");
        archived.remove("gear");
        archived.addProperty("result_excerpt_archived", true);
      }
    }
    activeId = id;
    Actions.status = "任务已提交：" + tool;
    try {
      Agent.execute(
          tool,
          args,
          result -> {
            if (!context.current()
                || Store.data.checkpoints.get(key) != record
                || !Agent.str(record, "status", "").equals("submitted")) return;
            TaskReceipt.returned(record, result);
            if (activeId.equals(id)) activeId = "";
            Store.saveWorld();
          });
    } catch (RuntimeException e) {
      if (activeId.equals(id))
        interruptActive("interrupted_unknown_result", "任务派发中断；请核对现场");
      throw e;
    }
    return receipt(id);
  }

  static JsonObject receipt(String id) {
    var r = Store.data.checkpoints.get("task:" + id);
    if (r == null) throw new IllegalArgumentException("[TASK_NOT_FOUND] 未找到本世界任务ID");
    var o = r.deepCopy();
    if (Agent.str(r, "status", "").equals("submitted")
        && (!id.equals(activeId)
            || !Agent.str(r, "run", "").equals(RUN)
            || r.get("epoch").getAsLong() != Agent.epoch))
      o.addProperty("status", "interrupted_unknown_result");
    boolean active = id.equals(activeId) && Agent.str(o, "status", "").equals("submitted");
    if (active && Agent.session != null) o.add("session", Agent.session.observe());
    if (active)
      o.addProperty("current_phase", Agent.session != null ? Agent.state : Actions.status);
    o.addProperty("active", active);
    o.addProperty("replay_safe", false);
    return o;
  }

  static String cancel(String id) {
    var current = receipt(id);
    if (!Agent.str(current, "status", "").equals("submitted")
        || !current.get("active").getAsBoolean()) return "任务已结束或旧执行结果未知；没有停止其他新任务";
    interruptActive("cancelled", "任务取消；请核对现场");
    Agent.cancelSubmittedTask("已取消任务 " + id + "；请核对现场");
    return Agent.paused
        ? "成功：任务已取消，原有暂停仍生效。已发送动作请核对现场。"
        : "成功：任务已取消，执行占用已释放。已发送动作请核对现场。";
  }

  static void interruptActive(String status, String reason) {
    if (activeId.isEmpty()) return;
    var r = Store.data.checkpoints.get("task:" + activeId);
    activeId = "";
    if (r == null || !r.get("status").getAsString().equals("submitted")
        || r.get("epoch").getAsLong() != Agent.epoch
        || !RUN.equals(r.get("run").getAsString())) return;
    r.addProperty("status", status);
    r.addProperty("ended_at", System.currentTimeMillis());
    r.addProperty("current_phase", reason);
    r.addProperty("requires_reobservation", true);
    try {
      if (Agent.production != null) r.add("recovery", Agent.production.recoveryEvidence());
      else if (Actions.job instanceof SmeltJob job) r.add("recovery", job.recoveryEvidence());
      else if (Actions.job instanceof MiningRouteJob job) r.add("mining_route", job.interruptionEvidence());
    } catch (RuntimeException e) {
      Companion.log("任务已结束，现场证据读取失败：" + e.getClass().getSimpleName());
    }
    Store.saveWorld();
  }
}
