package dev.mibao.companion;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;

/**
 * Main-thread control arbiter. External inference replaces API inference, not permissions or
 * receipts.
 */
public final class HarnessController {
  private static final HarnessSession session = new HarnessSession();
  private static final Deque<JsonObject> events = new ArrayDeque<>();
  private static ObservationFrames frames = new ObservationFrames();
  private static long sequence, nextFrame;
  private static String connectionScope = "";
  private static HarnessRecorder recorder;
  private static Path recordingFile;
  private static final ExecutorService IO =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(64),
          r -> {
            var t = new Thread(r, "Companion-Harness-Recorder");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());
  private static long unsafeFrame;

  public static void frame(Object screen) {
    if (LocalDialogue.editing
        || screen != null
            && !QuestBookBridge.available()
            && !(screen
                instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen))
      unsafeFrame = System.currentTimeMillis();
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  private static String scope() {
    return Store.scope()
        + "|"
        + (mc().level == null ? "disconnected" : mc().level.dimension().location());
  }

  public static boolean busy() {
    return foregroundBusy();
  }

  public static boolean foregroundBusy() {
    return Agent.routine != null
        || (Actions.job != null && !DutyScheduler.ownsCurrentJob())
        || Agent.production != null
        || Agent.transport != null
        || Agent.restock != null
        || Agent.resource != null
        || Agent.session != null
        || Agent.busy
        || !Agent.pendingTool.isBlank();
  }

  public static void tick() {
    session.invalidateEpoch(Agent.epoch);
    if (!connectionScope.isEmpty()) {
      if (!scope().equals(connectionScope)
          && connectionScope.startsWith(Store.scope() + "|")
          && Store.config.driver.equals("HARNESS")
          && Store.config.mcp
          && Agent.enabled) {
        Agent.controlWorldValid();
        connectionScope = scope();
        session.rebindDimension(connectionScope, Agent.epoch);
        event("dimension_changed", "同一服务器切换维度；租约和接管保留，旧动作作废，先重新观察目标");
      }
      if (!scope().equals(connectionScope)
          || !Store.config.driver.equals("HARNESS")
          || !Store.config.mcp) {
        if (Agent.enabled) Agent.stop("本地Harness驱动或世界改变，已停止控制");
        else interrupted("Harness连接失效");
      } else if (session.expired(System.currentTimeMillis())) {
        if (Store.config.harnessFinishLocal
            && (Agent.routine != null
                || Actions.job != null
                || Agent.production != null
                || Agent.resource != null
                || Agent.transport != null
                || Agent.restock != null
                || Agent.session != null)
            && Agent.pendingTool.isBlank()) {
          session.revokeLease();
          connectionScope = "";
          event("controller_detached", "外部连接过期；按玩家策略完成已交代的有限本地任务，可重连查询，不重放动作");
        } else Agent.harnessDisconnected("Harness连接已过期，当前动作已停止；AI接管保留，等待助手重新连接");
      }
    }
    if (recorder != null && System.currentTimeMillis() >= nextFrame) {
      nextFrame = System.currentTimeMillis() + (busy() ? 500 : 5000);
      var e = frames.encode(Actions.snapshot(), System.currentTimeMillis(), false);
      e.addProperty("at_ms", System.currentTimeMillis());
      e.addProperty("at", java.time.Instant.now().toString());
      write(e);
    }
  }

  public static void interrupted(String why) {
    session.interrupt(why);
    connectionScope = "";
    event("control_stopped", why);
    stopRecord();
  }

  public static void cooperativeEvent(String why) {
    event("human_intervention", why);
  }

  public static void userMessage(String text) {
    event("local_user", Rules.clean(text, 2000));
  }

  public static void message(String text) {
    if (!text.startsWith("{") && !text.startsWith("[")) event("assistant", Rules.clean(text, 1200));
  }

  public static void chatEvent(String kind, String sender, String text) {
    String cleanText = Rules.clean(text, 300);
    if (cleanText.isBlank()) return;
    String line =
        sender != null && !sender.isBlank()
            ? "[" + kind + "] <" + Rules.clean(sender, 48) + "> " + cleanText
            : "[" + kind + "] " + cleanText;
    var last = events.peekLast();
    if (last != null
        && "chat".equals(last.get("kind").getAsString())
        && line.equals(last.get("text").getAsString())
        && System.currentTimeMillis() - last.get("at_ms").getAsLong() < 500) {
      return;
    }
    event("chat", line);
  }

  public static void task(String text) {
    event("task", Rules.clean(text, 4000));
    LocalDialogue.add("系统", "指令已交给本地Harness，等待控制器处理");
  }

  private static void event(String kind, String text) {
    var e = new JsonObject();
    e.addProperty("scope", scope());
    e.addProperty("sequence", ++sequence);
    e.addProperty("at_ms", System.currentTimeMillis());
    e.addProperty("at", java.time.Instant.now().toString());
    e.addProperty("kind", kind);
    e.addProperty("text", text);
    events.addLast(e);
    while (events.size() > 256) events.removeFirst();
    write(e);
  }

  private static void write(JsonObject e) {
    var active = recorder;
    if (active == null) return;
    var copy = e.deepCopy();
    try {
      IO.execute(
          () -> {
            try {
              active.add(copy);
            } catch (Exception failure) {
              mc().execute(
                      () -> {
                        if (recorder == active) {
                          recorder = null;
                          Companion.notifyUser(
                              "Harness记录已停止：" + Rules.clean(failure.getMessage(), 100));
                        }
                      });
            }
          });
    } catch (RejectedExecutionException full) {
      recorder = null;
      Companion.notifyUser("Harness记录队列已满，已停止记录；控制动作不重试");
    }
  }

  public static String recordStatus() {
    return recorder == null ? "未记录" : recordingFile.getFileName().toString();
  }

  public static void stopRecord() {
    if (recorder != null) {
      event("recording_stopped", "本地记录结束");
      recorder = null;
    }
  }

  public static void startRecord() throws Exception {
    if (recorder != null) return;
    Path folder = Store.ROOT.resolve("harness-recordings");
    recordingFile =
        folder.resolve(
            "session-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".jsonl");
    frames = new ObservationFrames();
    recorder = new HarnessRecorder(recordingFile);
    event("recording_started", "schema2：完整观察与差分，10秒完整检查点；只记录本地指令、工具结果与结构化观察，不含API设置；截图需另行授权");
    nextFrame = 0;
  }

  /** Lightweight lease probe for HUD/render paths; never builds an observation. */
  public static boolean leaseActive() {
    return session.active(System.currentTimeMillis());
  }

  public static JsonObject status() {
    var o = Actions.observe();
    o.addProperty("contract_version", 1);
    o.add("death_recovery", DeathRecovery.observe());
    o.addProperty("death_phase", DeathRecovery.observe().get("phase").getAsString());
    o.addProperty("respawn_policy", DeathRecovery.observe().get("respawn_policy").getAsString());
    o.addProperty("enabled", Agent.enabled);
    o.addProperty("epoch_reason", Agent.lastEpochReason);
    o.add("cooperation", Cooperation.view());
    o.addProperty("paused", Agent.paused);
    for (var e : PauseControl.observe().entrySet()) o.add(e.getKey(), e.getValue());
    o.add("decision", DecisionService.status());
    o.addProperty("busy", foregroundBusy());
    o.addProperty("background_duty_active", DutyScheduler.ownsCurrentJob());
    o.addProperty("any_job_active", Actions.job != null);
    o.addProperty("pending_tool", Agent.pendingTool);
    o.addProperty("driver", Store.config.driver);
    o.addProperty("lease_active", session.active(System.currentTimeMillis()));
    o.addProperty("lease_remaining_ms", session.remaining(System.currentTimeMillis()));
    o.addProperty(
        "lease_ttl_seconds",
        session.active(System.currentTimeMillis())
            ? session.ttlSeconds()
            : HarnessLeasePolicy.normalize(Store.config.harnessLeaseSeconds));
    o.addProperty(
        "lease_mode",
        o.get("lease_ttl_seconds").getAsLong() == 0 ? "manual_stop" : "renewable_idle_timeout");
    o.addProperty("finish_local_after_disconnect", Store.config.harnessFinishLocal);
    o.addProperty("recording", recordStatus());
    o.add("runtime_stats", RuntimeStats.snapshot());
    o.add("planning_policy", RuntimeStrategy.view());
    o.add("community", CommunityTasks.view());
    o.add("logistics", LogisticsRuntime.summary());
    o.addProperty("physical_key_gate", WatchMode.keyHookSeen);
    o.addProperty("physical_mouse_gate", WatchMode.mouseHookSeen);
    return o;
  }

  public static JsonObject call(String name, JsonObject a) throws Exception {
    if (name.equals("status")) return status();
    if (name.equals("game_tools")) {
      var o = new JsonObject();
      o.add("tools", Agent.schemas());
      return o;
    }
    if (name.equals("capabilities")) {
      var o =
          HarnessCapabilities.describe(
              Agent.schemas().size(),
              session.active(System.currentTimeMillis())
                  ? (int) session.ttlSeconds()
                  : HarnessLeasePolicy.normalize(Store.config.harnessLeaseSeconds));
      o.add("planning_policy", RuntimeStrategy.view());
      return o;
    }
    if (name.equals("events")) return readEvents(a);
    if (name.equals("receipt"))
      return publicReceipt(session.receipt(Agent.str(a, "request_id", ""), scope()));
    if (name.equals("stop")) {
      Agent.stop("本地Harness急停");
      return ok("已停止");
    }
    if (name.equals("task")) {
      if (!Agent.enabled) throw new IllegalArgumentException("先在游戏中开启AI接管");
      if (Agent.paused || Cooperation.yielding())
        throw new IllegalArgumentException("[BUSY] 当前已暂停或交给玩家，请先恢复控制");
      Agent.submit(Agent.str(a, "text", ""), true);
      return ok("任务已接收，按当前API/HARNESS驱动处理");
    }
    if (name.equals("connect")) {
      if (!Agent.enabled) throw new IllegalArgumentException("[TAKEOVER_DISABLED] 先在游戏内明确开启AI接管");
      if (!Store.config.mcp) throw new IllegalArgumentException("[INTERFACE_DISABLED] 游戏内本机接口未开启");
      if (!Store.config.driver.equals("HARNESS"))
        throw new IllegalArgumentException("[DRIVER_MISMATCH] 在游戏中选择Harness驱动");
      if (mc().player == null) throw new IllegalArgumentException("[WORLD_REQUIRED] 尚未进入世界");
      session.durationSeconds(Store.config.harnessLeaseSeconds);
      String lease = session.connect(scope(), System.currentTimeMillis());
      connectionScope = scope();
      var o = ok("本地Harness已连接");
      o.addProperty("lease", lease);
      o.addProperty("ttl_seconds", session.ttlSeconds());
      o.addProperty("scope", scope());
      o.addProperty("epoch", Agent.epoch);
      event(
          "controller_connected",
          "控制租约：" + HarnessLeasePolicy.label((int) session.ttlSeconds()) + "；急停/释放/退出世界立即撤销");
      return o;
    }
    if (!Agent.enabled) throw new IllegalArgumentException("[TAKEOVER_DISABLED] 游戏内AI接管已关闭");
    if (!Store.config.mcp) throw new IllegalArgumentException("[INTERFACE_DISABLED] 本机接口已关闭");
    if (!Store.config.driver.equals("HARNESS"))
      throw new IllegalArgumentException("[DRIVER_MISMATCH] 当前不是Harness驱动");
    session.check(Agent.str(a, "lease", ""), scope(), System.currentTimeMillis());
    switch (name) {
      case "heartbeat" -> {
        return ok("租约已按当前期限续期");
      }
      case "release" -> {
        Agent.stop("本地Harness主动释放控制");
        return ok("已释放");
      }
      case "reply" -> {
        Agent.localConversation = true;
        Messages.reply(Agent.str(a, "text", ""));
        if (!busy() && !Agent.paused) {
          Agent.state = "本地Harness已回复，等待任务";
          Hud.FEED.acknowledge();
        }
        return ok("本地回复已显示，未发送公屏");
      }
      case "record" -> {
        if (Agent.str(a, "mode", "start").equals("stop")) stopRecord();
        else startRecord();
        return ok(recordStatus());
      }
      case "screenshot" -> {
        return screenshot();
      }
      case "action" -> {
        String tool = Agent.str(a, "tool", ""), id = Agent.str(a, "request_id", "");
        var args = Agent.obj(a, "args");
        String signature = TaskIdentity.signature(tool, args);
        var existing = session.existing(id, signature);
        if (existing != null) return publicReceipt(existing);
        if (!a.has("epoch")
            || a.get("epoch").getAsLong() != Agent.epoch
            || !Agent.str(a, "scope", "").equals(scope()))
          throw new IllegalArgumentException("[STALE_EPOCH] 旧epoch/scope不可控制，请重新status观察");
        if (!Rules.TOOLS.contains(tool)
            || Set.of("finish", "run_skill", "save_skill", "read_web", "search_web").contains(tool))
          throw new IllegalArgumentException("外部action仅允许已有游戏工具；不直接执行模型、网页或技能定义");
        if ((Agent.paused || Cooperation.yielding() || foregroundBusy())
            && !Set.of(
                    "capability_probe",
                    "machine_adapters",
                    "work_session_status",
                    "build_status",
                    "logistics_status",
                    "task_timeline")
                .contains(tool)
            && !Set.of(
                    "task_status",
                    "task_result",
                    "task_cancel",
                    "follow_targets",
                    "follow_status",
                    "follow_stop",
                    "duty_status",
                    "duty_set",
                    "observe_owner_focus",
                    "resume_control",
                    "pause_control",
                    "permission_status",
                    "decision_status",
                    "config_reload",
                    "observe_compact",
                    "integration_status",
                    "inspect_client",
                    "inspect_landing",
                    "column_probe",
                    "light_survey",
                    "find_blocks",
                    "vehicle_state",
                    "mining_status",
                    "plan_mining",
                    "preview_mining_route",
                    "explore_query",
                    "command_status")
                .contains(tool))
          return publicReceipt(session.blocked(id, signature, Agent.epoch, tool,
              "失败：[BUSY] 当前暂停/忙碌/待审批；先查状态或回执，不叠加写动作"));
        if (!Agent.paused && !Cooperation.yielding() && DutyScheduler.ownsCurrentJob()
            && !foregroundBusy()
            && (Rules.WRITE.contains(tool)
                || Set.of("go_to", "duty_set", "follow_start", "explore_branch", "explore_roam", "explore_return")
                    .contains(tool))) {
          DutyScheduler.yieldCurrentJob("明确指令抢占：" + tool);
        }
        var r = session.begin(id, signature, Agent.epoch, tool);
        Agent.localConversation = true;
        Hud.FEED.acknowledge();
        if (RestockTask.latest != null && RestockTask.latest.ended > 0) RestockTask.latest = null;
        event("tool_started", tool + " request_id=" + id);
        Agent.execute(
            tool,
            args,
            result -> {
              if (r.get("state").getAsString().equals("running")) {
                r.addProperty("state", Rules.actionFailed(result) ? "blocked" : "returned");
                r.addProperty("result", result);
                r.add("outcome", ActionOutcome.describe(result));
                event("tool_returned", tool + "：" + Rules.clean(result, 16000));
              }
            });
        return publicReceipt(r);
      }
      default -> throw new IllegalArgumentException("未知Harness工具");
    }
  }

  private static JsonObject readEvents(JsonObject a) {
    return HarnessEventPage.read(
        events, scope(), a.has("after") ? a.get("after").getAsLong() : 0, sequence);
  }

  public static void configureLease(int seconds) {
    Store.config.harnessLeaseSeconds = HarnessLeasePolicy.normalize(seconds);
    session.reconfigure(Store.config.harnessLeaseSeconds, System.currentTimeMillis());
    Store.save();
  }

  private static JsonObject publicReceipt(JsonObject r) {
    var o = r.deepCopy();
    o.remove("signature");
    if (o.has("state") && o.get("state").getAsString().equals("blocked") && o.has("result"))
      o.add("advice", FailureAdvice.json(o.get("result").getAsString()));
    return ReasonCodes.enrich(o);
  }

  private static JsonObject ok(String text) {
    var o = new JsonObject();
    o.addProperty("message", text);
    return o;
  }

  private static int screenshots;
  private static long lastScreenshot;

  private static JsonObject screenshot() throws Exception {
    if (!Store.config.harnessImages) throw new IllegalArgumentException("截图默认关闭，请在Harness页开启截图授权");
    if (System.currentTimeMillis() - unsafeFrame < 1000)
      throw new IllegalArgumentException("刚离开设置或文本输入，等待安全画面稳定1秒后再截取");
    if (mc().screen != null
            && !QuestBookBridge.safeImage()
            && !(mc().screen
                instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)
        || LocalDialogue.editing) throw new IllegalArgumentException("仅截世界/物品菜单，不截设置/聊天/输入中的文本");
    if (System.currentTimeMillis() - lastScreenshot < 2000 || screenshots >= 100)
      throw new IllegalArgumentException("截图冷却2秒，每次游戏启动最多100张");
    Path folder = Store.ROOT.resolve("harness-recordings/images");
    Files.createDirectories(folder);
    Path file = folder.resolve("frame-" + System.currentTimeMillis() + ".png");
    var image = net.minecraft.client.Screenshot.takeScreenshot(mc().getMainRenderTarget());
    try {
      net.minecraft.Util.ioPool()
          .execute(
              () -> {
                try {
                  image.writeToFile(file);
                  mc().execute(
                      () -> {
                        RuntimeStats.add("harness_screenshots_saved");
                        event("screenshot_saved", "截图已写入本地：" + file);
                      });
                } catch (Exception failure) {
                  mc().execute(
                      () ->
                          event(
                              "screenshot_failed",
                              "截图写入失败：" + Rules.clean(String.valueOf(failure.getMessage()), 120)));
                } finally {
                  image.close();
                }
              });
    } catch (RuntimeException rejected) {
      image.close();
      throw rejected;
    }
    lastScreenshot = System.currentTimeMillis();
    screenshots++;
    var o = ok("截图已排队写入本地（未上传），完成后追加 screenshot_saved 事件");
    o.addProperty("path", file.toString());
    return o;
  }
}
