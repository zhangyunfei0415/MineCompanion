package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * Bounded, scope-isolated ring buffer for recent in-game [CHAT] messages so Harness/LLM agents can
 * read multiplayer & system chat directly from {@code observe}/{@code events} without tailing
 * {@code latest.log}. Zero {@code net.minecraft.*} imports.
 */
public final class ChatBuffer {
  public static final int MAX_ENTRIES = 32;

  public record Entry(
      long seq, long atMs, String kind, String sender, String senderUuid, String text) {}

  private static final Deque<Entry> ENTRIES = new ArrayDeque<>();
  private static String currentScope = "";
  private static long seq;

  private ChatBuffer() {}

  public static synchronized void record(
      String scope,
      String kind,
      String sender,
      String senderUuid,
      String text,
      long nowMs) {
    String cleanScope = scope == null ? "" : scope;
    if (!cleanScope.equals(currentScope)) {
      ENTRIES.clear();
      currentScope = cleanScope;
    }
    if (text == null) return;
    String cleaned = Rules.clean(text, 240);
    if (cleaned.isBlank()) return;
    String lower = cleaned.toLowerCase(Locale.ROOT);
    if (lower.startsWith("/login ")
        || lower.startsWith("/register ")
        || lower.startsWith("/l ")) {
      return;
    }
    String safeSender = sender == null ? "" : Rules.clean(sender, 48);
    String safeUuid = senderUuid == null ? "" : Rules.clean(senderUuid, 64);
    String safeKind = kind == null || kind.isBlank() ? "chat" : Rules.clean(kind, 24);
    ENTRIES.addLast(new Entry(++seq, nowMs, safeKind, safeSender, safeUuid, cleaned));
    while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.removeFirst();
  }

  public static synchronized List<Entry> snapshot(String scope, int limit) {
    String cleanScope = scope == null ? "" : scope;
    if (!cleanScope.equals(currentScope) || limit <= 0 || ENTRIES.isEmpty()) return List.of();
    int cap = Math.min(limit, ENTRIES.size());
    List<Entry> all = new ArrayList<>(ENTRIES);
    return List.copyOf(all.subList(all.size() - cap, all.size()));
  }

  public static synchronized JsonArray recentJson(String scope, int limit) {
    JsonArray arr = new JsonArray();
    for (Entry e : snapshot(scope, limit)) {
      JsonObject row = new JsonObject();
      row.addProperty("seq", e.seq());
      row.addProperty("at_ms", e.atMs());
      row.addProperty("kind", e.kind());
      if (!e.sender().isBlank()) row.addProperty("sender", e.sender());
      if (!e.senderUuid().isBlank()) row.addProperty("sender_uuid", e.senderUuid());
      row.addProperty("text", e.text());
      arr.add(row);
    }
    return arr;
  }

  public static synchronized void clear() {
    ENTRIES.clear();
    currentScope = "";
  }
}
