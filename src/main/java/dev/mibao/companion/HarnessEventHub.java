package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe Harness event ring buffer + non-blocking async long-poll waiter hub. Zero
 * {@code net.minecraft.*} imports so concurrency and real HTTP wait behavior can be unit tested
 * offline.
 */
public final class HarnessEventHub implements AutoCloseable {
  public static final int MAX_RING = 256;
  public static final int MAX_WAITERS = 4;
  public static final long MIN_TIMEOUT_MS = 1000L;
  public static final long MAX_TIMEOUT_MS = 20_000L;

  private static final class Waiter {
    final String scope;
    final long after;
    final Set<String> kinds;
    final CompletableFuture<JsonObject> future = new CompletableFuture<>();
    final AtomicBoolean done = new AtomicBoolean();
    ScheduledFuture<?> timeoutTask;

    Waiter(String scope, long after, Set<String> kinds) {
      this.scope = scope;
      this.after = after;
      this.kinds = kinds;
    }
  }

  private final Deque<JsonObject> ring = new ArrayDeque<>();
  private final List<Waiter> waiters = new ArrayList<>();
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            var t = new Thread(r, "Companion-Harness-EventWait");
            t.setDaemon(true);
            return t;
          });
  private long sequence;
  private long generation = 1;
  private String currentScope = "";
  private volatile boolean closed;

  public synchronized long nextSequence() {
    return sequence;
  }

  public synchronized long generation() {
    return generation;
  }

  public synchronized void updateScope(String newScope) {
    String safe = newScope == null ? "" : newScope;
    if (Objects.equals(currentScope, safe)) return;
    currentScope = safe;
    generation++;
    List<Waiter> stale = new ArrayList<>();
    for (Waiter w : waiters) {
      if (w.done.compareAndSet(false, true)) stale.add(w);
    }
    waiters.clear();
    JsonObject resync = buildPageLocked(safe, sequence, Set.of(), false, true);
    for (Waiter w : stale) {
      if (w.timeoutTask != null) w.timeoutTask.cancel(false);
      w.future.complete(resync.deepCopy());
    }
  }

  public JsonObject publish(String scope, String kind, String text, JsonObject extra) {
    String safeScope = scope == null ? "" : scope;
    JsonObject stored;
    List<Waiter> matched = List.of();
    List<JsonObject> pages = List.of();
    synchronized (this) {
      if (!Objects.equals(currentScope, safeScope)) {
        currentScope = safeScope;
        generation++;
      }
      stored = new JsonObject();
      stored.addProperty("scope", safeScope);
      stored.addProperty("sequence", ++sequence);
      stored.addProperty("generation", generation);
      long now = System.currentTimeMillis();
      stored.addProperty("at_ms", now);
      stored.addProperty("at", Instant.ofEpochMilli(now).toString());
      stored.addProperty("kind", kind);
      stored.addProperty("text", text == null ? "" : text);
      if (extra != null) {
        for (var entry : extra.entrySet()) {
          if (!stored.has(entry.getKey())) {
            stored.add(entry.getKey(), entry.getValue().deepCopy());
          }
        }
      }
      ring.addLast(stored);
      while (ring.size() > MAX_RING) ring.removeFirst();

      if (!waiters.isEmpty()) {
        matched = new ArrayList<>(waiters.size());
        pages = new ArrayList<>(waiters.size());
        Iterator<Waiter> it = waiters.iterator();
        while (it.hasNext()) {
          Waiter w = it.next();
          if (Objects.equals(w.scope, safeScope)
              && sequence > Math.max(0, w.after)
              && (w.kinds.isEmpty() || w.kinds.contains(kind))) {
            if (w.done.compareAndSet(false, true)) {
              matched.add(w);
              pages.add(buildPageLocked(w.scope, w.after, w.kinds, false, false));
            }
            it.remove();
          } else if (!Objects.equals(w.scope, safeScope)) {
            if (w.done.compareAndSet(false, true)) {
              matched.add(w);
              pages.add(buildPageLocked(safeScope, sequence, w.kinds, false, true));
            }
            it.remove();
          }
        }
      }
    }
    for (int i = 0; i < matched.size(); i++) {
      Waiter w = matched.get(i);
      if (w.timeoutTask != null) w.timeoutTask.cancel(false);
      w.future.complete(pages.get(i));
    }
    return stored.deepCopy();
  }

  public synchronized JsonObject read(String scope, long after) {
    JsonObject page = HarnessEventPage.read(ring, scope == null ? "" : scope, after, sequence);
    page.addProperty("generation", generation);
    return page;
  }

  public CompletableFuture<JsonObject> waitFor(
      String scope, long after, Set<String> kinds, long requestedTimeoutMs) {
    if (closed) {
      return CompletableFuture.completedFuture(
          buildEmptyClosedPage(scope == null ? "" : scope));
    }
    String safeScope = scope == null ? "" : scope;
    Set<String> safeKinds = kinds == null ? Set.of() : Set.copyOf(kinds);
    long timeoutMs = Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, requestedTimeoutMs));

    synchronized (this) {
      boolean truncated = !ring.isEmpty() && after < ring.peekFirst().get("sequence").getAsLong() - 1;
      JsonObject immediate = buildPageLocked(safeScope, after, safeKinds, false, false);
      if (!immediate.getAsJsonArray("events").isEmpty() || truncated) {
        return CompletableFuture.completedFuture(immediate);
      }
      if (waiters.size() >= MAX_WAITERS) {
        return CompletableFuture.failedFuture(
            new IllegalArgumentException("[WAIT_LIMIT] 长轮询并发数已达上限，请改用 events 轮询"));
      }
      Waiter waiter = new Waiter(safeScope, after, safeKinds);
      waiters.add(waiter);
      waiter.timeoutTask =
          timer.schedule(
              () -> {
                JsonObject emptyPage = null;
                synchronized (HarnessEventHub.this) {
                  waiters.remove(waiter);
                  if (waiter.done.compareAndSet(false, true)) {
                    emptyPage =
                        buildPageLocked(waiter.scope, waiter.after, waiter.kinds, true, false);
                  }
                }
                if (emptyPage != null) waiter.future.complete(emptyPage);
              },
              timeoutMs,
              TimeUnit.MILLISECONDS);
      return waiter.future;
    }
  }

  private JsonObject buildPageLocked(
      String scope, long after, Set<String> kinds, boolean timedOut, boolean generationChanged) {
    var out = new JsonObject();
    var list = new JsonArray();
    long floor = Math.max(0, after);
    for (var e : ring) {
      long seq = e.get("sequence").getAsLong();
      if (seq > floor
          && Objects.equals(scope, e.get("scope").getAsString())
          && (kinds.isEmpty() || kinds.contains(e.get("kind").getAsString()))) {
        list.add(e.deepCopy());
      }
    }
    out.add("events", list);
    out.addProperty("next", sequence);
    out.addProperty("scope", scope);
    out.addProperty("generation", generation);
    out.addProperty("read_only", true);
    out.addProperty(
        "truncated", !ring.isEmpty() && after < ring.peekFirst().get("sequence").getAsLong() - 1);
    if (timedOut) out.addProperty("timed_out", true);
    if (generationChanged) out.addProperty("generation_changed", true);
    return out;
  }

  private synchronized JsonObject buildEmptyClosedPage(String scope) {
    var out = new JsonObject();
    out.add("events", new JsonArray());
    out.addProperty("next", sequence);
    out.addProperty("scope", scope);
    out.addProperty("generation", generation);
    out.addProperty("read_only", true);
    out.addProperty("closed", true);
    return out;
  }

  @Override
  public void close() {
    closed = true;
    List<Waiter> pending;
    synchronized (this) {
      pending = new ArrayList<>(waiters);
      waiters.clear();
    }
    for (Waiter w : pending) {
      if (w.timeoutTask != null) w.timeoutTask.cancel(false);
      if (w.done.compareAndSet(false, true)) {
        w.future.complete(buildEmptyClosedPage(w.scope));
      }
    }
    timer.shutdownNow();
  }
}
