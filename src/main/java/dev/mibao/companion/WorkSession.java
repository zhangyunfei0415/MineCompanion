package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * A normal client-player work session; prerequisites wait, unknown results stop, nothing replays.
 */
public final class WorkSession {
  private final SessionPlan plan;
  private final SessionProgress progress;
  private final Consumer<String> done;
  private final long epoch = Agent.epoch;
  private final String scope = Store.scope(),
      dimension = mc().level.dimension().location().toString();
  private final long deadline;
  private final TaskCard card = new TaskCard();
  private SupplyWait supply;
  private int ticks;
  private boolean finished;
  private Map<String, Integer> waitingInventory;
  private FarmBatch<net.minecraft.core.BlockPos> farmBatch;

  public FarmBatch<net.minecraft.core.BlockPos> farmBatch() {
    return farmBatch;
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public WorkSession(JsonObject input, Consumer<String> done) {
    plan = new SessionPlan(input);
    progress = new SessionProgress(plan.steps.size());
    this.done = done;
    deadline = System.currentTimeMillis() + plan.seconds * 1000L;
    card.kind = "session";
    card.title = plan.name;
    card.target = plan.steps.size();
    card.current = 0;
    card.progress = "已完成 0 / " + card.target + " 步";
    if (input.has("farm_batch")) {
      var b = input.getAsJsonObject("farm_batch");
      farmBatch =
          new FarmBatch<>(
              b.get("count").getAsInt(),
              b.get("plant").getAsBoolean(),
              b.get("grow").getAsBoolean(),
              b.get("harvest").getAsBoolean());
    }
    RestockTask.latest = card;
    checkpoint("prepared", false);
  }

  public boolean finished() {
    return finished;
  }

  public boolean expired() {
    return !finished && System.currentTimeMillis() >= deadline;
  }

  public void cancel() {
    if (finished) return;
    finished = true;
    card.uncertain = progress.pending();
    card.outcome = "cancelled";
    card.phase = "连续流程已停止";
    card.ended = System.currentTimeMillis();
    RestockTask.latest = card;
    checkpoint("cancelled", card.uncertain);
  }

  public void fail(String reason) {
    end(
        "部分完成：连续流程停止；已完成 " + progress.completed() + " / " + plan.steps.size() + " 步。" + reason,
        progress.pending());
  }

  public void tick() {
    if (finished) return;
    if (epoch != Agent.epoch
        || !scope.equals(Store.scope())
        || !dimension.equals(mc().level.dimension().location().toString())) {
      cancel();
      return;
    }
    if (System.currentTimeMillis() >= deadline) {
      fail("达到会话总预算；已执行步骤不回滚，未确认步骤不重放。");
      return;
    }
    if (progress.pending()) return;
    if (progress.done()) {
      if (farmBatch != null && !farmBatch.complete()) {
        fail("同批作物核验未完成；" + farmBatch.summary());
        return;
      }
      end(
          "成功：连续流程 "
              + plan.name
              + " 完成 "
              + progress.completed()
              + " / "
              + plan.steps.size()
              + " 步；各步骤结果由原执行器核验。"
              + (farmBatch == null ? "" : farmBatch.summary()),
          false);
      return;
    }
    if (++ticks % 10 != 1) return;
    var step = plan.steps.get(progress.completed());
    var missing = new LinkedHashMap<String, Integer>();
    step.requires()
        .forEach(
            (id, n) -> {
              int deficit = n - plainCount(id);
              if (deficit > 0) missing.put(id, deficit);
            });
    if (supply == null) supply = new SupplyWait(step.waitSeconds());
    var state = supply.check(System.currentTimeMillis(), missing.isEmpty());
    if (state != SupplyWait.State.READY) {
      Navigator.release();
      RestockTask.latest = card;
      card.phase = "等待临时补给";
      card.next =
          "缺少 "
              + missing
              + "；剩余约 "
              + ((supply.remaining(System.currentTimeMillis()) + 999) / 1000)
              + " 秒";
      Agent.state = card.phase + " · " + card.next;
      if (waitingInventory == null) {
        waitingInventory = Map.copyOf(PlayerEvidence.actual("*"));
        checkpoint("waiting_supply", false);
      }
      if (state == SupplyWait.State.EXPIRED) fail("当前步骤尚未执行，等待物资超时：" + missing);
      return;
    }
    if (waitingInventory != null) {
      WorkLedger.inventoryChange(waitingInventory, Map.copyOf(PlayerEvidence.actual("*")));
      waitingInventory = null;
    }
    supply = null;
    String ticket = progress.begin(UUID.randomUUID().toString());
    card.phase = ActionSummary.step(step.tool());
    card.next = "第 " + (progress.completed() + 1) + " 步，按现有权限执行";
    checkpoint("child_started", true);
    Agent.execute(
        step.tool(),
        step.args(),
        result -> {
          if (finished || epoch != Agent.epoch) return;
          boolean success = StepEvidence.complete(step.tool(), result);
          if (!progress.finish(ticket, success)) return;
          card.current = progress.completed();
          card.progress = "已完成 " + card.current + " / " + card.target + " 步";
          RestockTask.latest = card;
          WorkLedger.add(
              "session_step",
              plan.name + " · " + step.tool() + "：" + ActionSummary.describe(result));
          if (!success) {
            end("部分完成：连续流程在第 " + (progress.completed() + 1) + " 步停止。" + result + " 后续步骤未执行。", true);
            return;
          }
          checkpoint("child_verified", false);
        });
  }

  private int plainCount(String id) {
    int count = 0;
    for (int i = 0; i < 36; i++) {
      var s = mc().player.getInventory().getItem(i);
      if (!s.isEmpty()
          && !s.hasTag()
          && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(id))
        count += s.getCount();
    }
    return count;
  }

  private void checkpoint(String phase, boolean uncertain) {
    if (!scope.equals(Store.scope())) return;
    var d = new JsonObject();
    d.addProperty("name", plan.name);
    d.addProperty("completed_steps", progress.completed());
    d.addProperty("total_steps", plan.steps.size());
    d.addProperty("next_tool", progress.done() ? "" : plan.steps.get(progress.completed()).tool());
    RecoveryLedger.put("work_session", phase, uncertain, d, dimension);
  }

  private void end(String text, boolean uncertain) {
    if (finished) return;
    finished = true;
    card.uncertain = uncertain;
    card.outcome = text.startsWith("成功") ? "complete" : "blocked";
    card.problem = card.outcome.equals("complete") ? "" : text;
    card.phase = ActionSummary.describe(text);
    card.ended = System.currentTimeMillis();
    RestockTask.latest = card;
    checkpoint(card.outcome.equals("complete") ? "complete" : "stopped", uncertain);
    if (Agent.session == this) Agent.session = null;
    done.accept(text);
  }

  public JsonObject observe() {
    var o = new JsonObject();
    o.addProperty("name", plan.name);
    o.addProperty("completed", progress.completed());
    o.addProperty("total", plan.steps.size());
    o.addProperty("phase", card.phase);
    o.addProperty("next", card.next);
    o.addProperty("pending", progress.pending());
    o.addProperty("auto_replay", false);
    return o;
  }

  public String human() {
    return "连续流程："
        + plan.name
        + "\n已完成："
        + progress.completed()
        + " / "
        + plan.steps.size()
        + " 步\n当前阶段："
        + card.phase
        + "\n下一步："
        + card.next
        + "\n子动作："
        + ActionSummary.describe(Actions.status)
        + "\n收到临时物资后会重新检查；未知结果不自动重做。";
  }
}
