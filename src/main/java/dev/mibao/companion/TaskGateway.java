package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;

/**
 * Small asynchronous facade over existing tasks, not a second executor. Stored receipts never
 * replay.
 */
final class TaskGateway {
  private static final String RUN = UUID.randomUUID().toString();
  private static String activeId = "";
  private static final Set<String> TASKS =
      Set.of(
          "fish",
          "illuminate_area",
          "work_session",
          "craft_item",
          "smelt_item",
          "restock",
          "transport",
          "farm_cycle",
          "build_platform",
          "build_bridge",
          "run_library_skill",
          "command_skill_run");

  static JsonObject submit(JsonObject input) {
    String id = Agent.str(input, "task_id", UUID.randomUUID().toString());
    if (!id.matches("[A-Za-z0-9_.-]{1,80}"))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] task_id为1..80字母数字/._-");
    String tool = SkillCatalog.required(input, "tool");
    if (!TASKS.contains(tool))
      throw new IllegalArgumentException("[TASK_UNSUPPORTED] 使用现有连续任务入口：" + TASKS);
    JsonObject args = Agent.obj(input, "args").deepCopy();
    String signature = TaskIdentity.signature(tool, args);
    String key = "task:" + id;
    if (Store.data.checkpoints.containsKey(key)) {
      var old = Store.data.checkpoints.get(key);
      if (!Agent.str(old, "signature", "").equals(signature))
        throw new IllegalArgumentException("[TASK_ID_CONFLICT] 同ID的目标/参数不同，拒绝覆盖");
      return receipt(id);
    }
    if (HarnessController.busy()) throw new IllegalArgumentException("[BUSY] 已有任务，不叠加执行");
    long count =
        Store.data.checkpoints.keySet().stream().filter(k -> k.startsWith("task:")).count();
    if (count >= 1024)
      throw new IllegalArgumentException(
          "[TASK_LEDGER_FULL] 本世界已有1024个任务ID；保留去重记录，不静默遗忘后重放，请备份整理账本");
    var record = new JsonObject();
    record.addProperty("task_id", id);
    record.addProperty("tool", tool);
    record.addProperty("signature", signature);
    record.addProperty("run", RUN);
    record.addProperty("epoch", Agent.epoch);
    record.addProperty(
        "dimension", Minecraft.getInstance().level.dimension().location().toString());
    record.addProperty("scope", Store.scope());
    record.addProperty("status", "submitted");
    record.addProperty("started_at", System.currentTimeMillis());
    Store.data.checkpoints.put(key, record);
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
        archived.addProperty("result_excerpt_archived", true);
      }
    }
    Store.save();
    activeId = id;
    Agent.execute(
        tool,
        args,
        result -> {
          if (Store.data.checkpoints.get(key) != record
              || !Agent.str(record, "status", "").equals("submitted")) return;
          record.addProperty("status", "returned");
          record.addProperty("ended_at", System.currentTimeMillis());
          record.add("outcome", ActionOutcome.describe(result));
          record.addProperty("result", TaskTimeline.clean(result));
          Store.save();
          if (activeId.equals(id)) activeId = "";
        });
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
    Agent.manualPause();
    var r = Store.data.checkpoints.get("task:" + id);
    r.addProperty("status", "cancelled");
    Store.save();
    return "任务停止并保留接管/连接；明确继续前不执行。已发送动作请查现场，不重放。";
  }
}
