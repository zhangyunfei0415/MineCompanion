package dev.mibao.companion;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/** Optional asynchronous selector over existing continuous tasks, sharing their action lane. */
public final class DecisionService {
  private static final HttpClient HTTP = DecisionTransport.create();
  private static boolean pending;
  private static long calls, failures, lastMillis, inputTokens;
  private static JsonObject latest = new JsonObject();
  private static JsonObject lastFailure = new JsonObject();
  private static long generation;

  static void invalidate() {
    generation++;
  }

  static JsonObject status() {
    var o = new JsonObject();
    o.addProperty("contract_version", 1);
    o.add("supported_candidate_tools", Store.GSON.toJsonTree(TaskActions.ALLOWED));
    o.add("last_failure", lastFailure.deepCopy());
    o.addProperty("mode", Store.config.decisionMode);
    o.addProperty(
        "provider",
        "LOCAL".equals(Store.config.decisionMode)
            ? Store.config.decisionLocalProtocol
            : Store.config.decisionCloudProvider);
    o.addProperty("auto_submit", Store.config.decisionAutoSubmit);
    o.addProperty("pending", pending);
    o.addProperty("calls", calls);
    o.addProperty("failures", failures);
    o.addProperty("input_tokens_reported", inputTokens);
    o.addProperty("last_latency_ms", lastMillis);
    var projected = latest.deepCopy();
    if (projected.has("task")) {
      var task = projected.getAsJsonObject("task");
      if (Agent.str(task, "scope", "").equals(Store.scope())) {
        try {
          projected.add("task", TaskGateway.receipt(task.get("task_id").getAsString()));
        } catch (IllegalArgumentException e) {
          task.addProperty("active", false);
          task.addProperty("status", "unavailable");
        }
      } else {
        task.addProperty("active", false);
        task.addProperty("status", "other_world");
      }
    }
    o.add("latest", projected);
    return o;
  }

  static void choose(JsonObject input, Consumer<String> done) {
    if (pending) throw new IllegalArgumentException("[DECISION_BUSY] 已有判断请求");
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null || mc.getConnection() == null)
      throw new IllegalArgumentException("[WORLD_REQUIRED] 先进入游戏世界");
    if (!Agent.enabled || Agent.paused || Cooperation.yielding())
      throw new IllegalArgumentException("[DECISION_PAUSED] 当前未接管或已暂停");
    var profile = DecisionWire.profile(Store.config);
    var choices = input.getAsJsonArray("candidates");
    if (choices == null || choices.size() < 2 || choices.size() > 12)
      throw new IllegalArgumentException(
          "[DECISION_CANDIDATES] 提供2..12个已有动作候选；合法工具见decision_status.supported_candidate_tools");
    var candidates = choices.deepCopy();
    Map<String, JsonObject> bound = new LinkedHashMap<>();
    for (var value : candidates) {
      var c = value.getAsJsonObject();
      String tool = SkillCatalog.required(c, "tool");
      if (!TaskGateway.supports(tool))
        throw new IllegalArgumentException(TaskActions.unsupported(tool));
      bound.put(SkillCatalog.required(c, "id"), c);
    }
    boolean submit = input.has("submit") && input.get("submit").getAsBoolean();
    if (submit && !Store.config.decisionAutoSubmit)
      throw new IllegalArgumentException("[DECISION_SUBMIT_DISABLED] 请在判断层设置开启自动提交");
    var snapshot = new JsonObject();
    snapshot.addProperty("goal", Agent.str(input, "goal", Agent.task));
    snapshot.addProperty("current_task", Agent.task);
    var position = new JsonObject();
    position.addProperty("x", mc.player.getBlockX());
    position.addProperty("y", mc.player.getBlockY());
    position.addProperty("z", mc.player.getBlockZ());
    snapshot.add("position", position);
    snapshot.addProperty("scope", Store.scope());
    snapshot.addProperty("dimension", mc.level.dimension().location().toString());
    snapshot.addProperty("epoch", Agent.epoch);
    snapshot.addProperty("observed_at", System.currentTimeMillis());
    snapshot.addProperty("health", mc.player.getHealth());
    snapshot.addProperty("food", mc.player.getFoodData().getFoodLevel());
    snapshot.add("inventory", Store.GSON.toJsonTree(PlayerEvidence.actual("*")));
    var stable = new JsonObject();
    stable.add("position", position);
    stable.add("inventory", snapshot.get("inventory"));
    stable.addProperty("task", Agent.task);
    String context = TaskIdentity.signature("decision", stable);
    var previousFailures = new JsonArray();
    var failedSignatures = new HashSet<String>();
    for (var record : Store.data.checkpoints.values()) {
      if (!Agent.str(record, "decision_context", "").equals(context) || !record.has("result"))
        continue;
      if (Rules.actionFailed(record.get("result").getAsString())) {
        failedSignatures.add(Agent.str(record, "signature", ""));
        if (previousFailures.size() < 6)
          previousFailures.add(Rules.clean(record.get("result").getAsString(), 500));
      }
    }
    snapshot.add("recent_failures_same_inventory_and_position", previousFailures);
    var permissions = new JsonObject();
    permissions.addProperty("mode", Store.config.permission);
    permissions.addProperty("full_game_control", Store.config.fullGameControl);
    permissions.addProperty("navigation_break", NavigationPermissions.canBreak());
    permissions.addProperty("navigation_place", NavigationPermissions.canPlace());
    snapshot.add("permissions", permissions);
    snapshot.add("candidates", candidates);
    if (input.has("context")) snapshot.add("context_unverified", input.get("context"));
    var body = DecisionWire.request(profile, snapshot, candidates);
    if (body.toString().length() > 24000)
      throw new IllegalArgumentException("[DECISION_CONTEXT_LIMIT] 状态与候选总量超过24000字符，请缩小当前阶段");
    var request =
        HttpRequest.newBuilder(URI.create(profile.endpoint()))
            .timeout(Duration.ofSeconds(25))
            .header("Content-Type", "application/json");
    if (!profile.key().isBlank())
      request.header(
          profile.protocol().equals("GOOGLE") ? "x-goog-api-key" : "Authorization",
          profile.protocol().equals("GOOGLE") ? profile.key() : "Bearer " + profile.key());
    var world = mc.level;
    long epoch = Agent.epoch, started = System.nanoTime(), revision = generation;
    String scope = Store.scope(), mode = Store.config.decisionMode, goal = Agent.task;
    pending = true;
    calls++;
    try {
      HTTP.sendAsync(
              request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
              HttpResponse.BodyHandlers.ofString())
          .whenComplete(
              (response, error) ->
                  mc.execute(
                      () -> {
                        pending = false;
                        lastMillis = (System.nanoTime() - started) / 1_000_000;
                        try {
                          if (error != null || response.statusCode() != 200) {
                            lastFailure =
                                DecisionFailure.describe(
                                    profile,
                                    response == null ? 0 : response.statusCode(),
                                    response == null ? "" : response.body(),
                                    error);
                            throw new IllegalArgumentException(
                                "["
                                    + lastFailure.get("reason_code").getAsString()
                                    + "] "
                                    + lastFailure.get("detail_excerpt").getAsString());
                          }
                          if (response.body().length() > 131072)
                            throw new IllegalArgumentException("[DECISION_RESPONSE_LIMIT] 响应过大");
                          var result =
                              DecisionWire.answer(
                                  profile,
                                  JsonParser.parseString(response.body()).getAsJsonObject(),
                                  bound.keySet());
                          if (result.has("usage")) {
                            var usage = result.getAsJsonObject("usage");
                            if (usage.has("total_input_tokens"))
                              inputTokens += usage.get("total_input_tokens").getAsLong();
                            else if (usage.has("prompt_tokens"))
                              inputTokens += usage.get("prompt_tokens").getAsLong();
                          }
                          if (world != mc.level
                              || epoch != Agent.epoch
                              || revision != generation
                              || !goal.equals(Agent.task)
                              || !scope.equals(Store.scope())
                              || !mode.equals(Store.config.decisionMode)
                              || !profile.equals(DecisionWire.profile(Store.config))
                              || !Agent.enabled
                              || Agent.paused
                              || Cooperation.yielding())
                            throw new IllegalArgumentException(
                                "[DECISION_STALE] 状态或控制已变化，请重新观察；未提交任务");
                          result.addProperty("latency_ms", lastMillis);
                          result.addProperty("submitted", false);
                          if (submit) {
                            var selected = bound.get(result.get("choice").getAsString());
                            if (failedSignatures.contains(
                                TaskIdentity.signature(
                                    selected.get("tool").getAsString(),
                                    Agent.obj(selected, "args"))))
                              throw new IllegalArgumentException(
                                  "[DECISION_REPEAT_BLOCKED] 相同现场与库存下该动作已失败，请观察目标变化或更换候选");
                            var task = new JsonObject();
                            task.addProperty("task_id", "decision-" + UUID.randomUUID());
                            task.addProperty("tool", selected.get("tool").getAsString());
                            task.add("args", Agent.obj(selected, "args"));
                            task.addProperty("decision_context", context);
                            // GUI/cursor recovery and eating have their own preconditions at
                            // dispatch.
                            // The mining/workshop preflight would incorrectly reject these
                            // candidates.
                            result.add("task", TaskGateway.submit(task));
                            result.addProperty("submitted", true);
                          }
                          latest = result.deepCopy();
                          done.accept(result.toString());
                        } catch (Exception failure) {
                          failures++;
                          String message =
                              failure instanceof IllegalArgumentException
                                      && failure.getMessage() != null
                                      && failure.getMessage().startsWith("[DECISION_")
                                  ? failure.getMessage()
                                  : "[DECISION_FAILED] 判断解析或任务前置条件失败；请重新观察任务状态";
                          latest = new JsonObject();
                          latest.addProperty("status", "rejected");
                          latest.addProperty("error", message);
                          if (response != null && response.statusCode() == 200)
                            lastFailure = DecisionFailure.describe(profile, 200, message, null);
                          done.accept(latest.toString());
                        }
                      }));
    } catch (RuntimeException e) {
      pending = false;
      throw e;
    }
  }
}
