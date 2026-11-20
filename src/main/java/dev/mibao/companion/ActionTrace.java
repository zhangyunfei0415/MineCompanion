package dev.mibao.companion;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;

/** Bounded diagnostics. Only allowlisted action fields; never credentials, prompts or GUI text. */
public final class ActionTrace {
  private record PendingFlush(Path folder, Path target, Path tmp, String json) {}

  private static final Pattern SECRET_EXCERPT =
      Pattern.compile("(?is).*?(api.?key|authorization|bearer|sk-[a-z0-9]).*");
  private static final ExecutorService IO =
      Executors.newSingleThreadExecutor(
          r -> {
            var t = new Thread(r, "Companion-Trace-IO");
            t.setDaemon(true);
            return t;
          });
  private static final AtomicReference<PendingFlush> pending = new AtomicReference<>();
  private static final AtomicBoolean scheduled = new AtomicBoolean();
  private static final Deque<JsonObject> records = new ArrayDeque<>();
  private static long sequence;
  private static String recordScope = "";

  public static Consumer<String> wrap(String tool, JsonObject args, Consumer<String> callback) {
    if (!recordScope.equals(Store.scope())) {
      records.clear();
      recordScope = Store.scope();
    }
    JsonObject r = new JsonObject();
    r.addProperty("action_id", ++sequence);
    r.addProperty("epoch", Agent.epoch);
    r.addProperty("tool", tool);
    r.addProperty("started", java.time.Instant.now().toString());
    JsonObject safe = new JsonObject();
    for (String k :
        List.of(
            "name",
            "action",
            "modifiers",
            "ticks",
            "mode",
            "hand",
            "item",
            "block",
            "count",
            "x",
            "y",
            "z",
            "slot",
            "seconds"))
      if (args.has(k) && args.get(k).isJsonPrimitive()) safe.add(k, args.get(k).deepCopy());
    r.add("args", safe);
    var mc = Minecraft.getInstance();
    r.addProperty(
        "foreground_physical",
        org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(
                mc.getWindow().getWindow(), org.lwjgl.glfw.GLFW.GLFW_FOCUSED)
            == 1);
    r.addProperty("screen", mc.screen == null ? "world" : mc.screen.getClass().getName());
    r.addProperty("phase", "running");
    records.addLast(r);
    while (records.size() > 128) records.removeFirst();
    flush();
    TaskTimeline.add("action", ActionSummary.step(tool), safe.toString());
    return result -> {
      if (tool.equals("decision_select")
          && (!r.get("phase").getAsString().equals("running")
              || r.get("epoch").getAsLong() != Agent.epoch)) return;
      CommunityTasks.result(tool, result);
      RuntimeStrategy.result(tool, result);
      r.addProperty("finished", java.time.Instant.now().toString());
      r.addProperty("phase", "returned");
      r.add("outcome", ActionOutcome.describe(result));
      RuntimeStats.add("action_results");
      r.addProperty(
          "origin",
          result.contains("[PERMISSION")
                  || result.contains("[WRONG_")
                  || result.contains("[PROTECTED")
                  || result.contains("[KEY_")
              ? "local_validation"
              : result.contains("服务器确认") || result.contains("服务器同步核对") || result.contains("完整服务器快照")
                  ? "server_sync_evidence"
                  : "tool_report_or_request_only");
      String excerpt =
          result.startsWith("{") || result.startsWith("[")
              ? "structured result"
              : Rules.clean(result, 500);
      if (SECRET_EXCERPT.matcher(excerpt).matches()) excerpt = "redacted";
      r.addProperty("result", excerpt);
      TaskTimeline.add(
          "result",
          ActionSummary.step(tool) + " · " + excerpt,
          "动作#" + r.get("action_id") + "；" + r.get("origin").getAsString());
      flush();
      callback.accept(result);
    };
  }

  public static void interrupted(String reason) {
    for (var r : records)
      if (r.get("phase").getAsString().equals("running")) {
        r.addProperty("phase", "interrupted");
        r.addProperty("stop_reason", Rules.clean(reason, 100));
      }
    flush();
  }

  private static void flush() {
    if (records.isEmpty()) return;
    Path folder = Store.ROOT.resolve("action-traces");
    String scope =
        UUID.nameUUIDFromBytes(recordScope.getBytes(StandardCharsets.UTF_8)).toString();
    pending.set(
        new PendingFlush(
            folder,
            folder.resolve(scope + "-last.json"),
            folder.resolve(scope + "-last.tmp"),
            Store.GSON.toJson(records)));
    if (scheduled.compareAndSet(false, true)) IO.execute(ActionTrace::drainFlushes);
  }

  private static void drainFlushes() {
    while (true) {
      try {
        PendingFlush job;
        while ((job = pending.getAndSet(null)) != null) {
          try {
            Files.createDirectories(job.folder());
            Files.writeString(job.tmp(), job.json(), StandardCharsets.UTF_8);
            Files.move(job.tmp(), job.target(), StandardCopyOption.REPLACE_EXISTING);
          } catch (Exception ignored) {
          }
        }
      } finally {
        scheduled.set(false);
      }
      // Guard against a concurrent flush arriving right before scheduled.set(false).
      if (pending.get() == null || !scheduled.compareAndSet(false, true)) return;
    }
  }
}
