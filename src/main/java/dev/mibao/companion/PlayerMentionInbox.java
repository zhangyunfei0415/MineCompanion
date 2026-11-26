package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * Thread-safe bounded in-memory inbox for multiplayer {@code player_mention} dialogues, keeping
 * chat interactions decoupled from {@link CommunityBoard} until an authorized member's request is
 * explicitly accepted as work. Zero {@code net.minecraft.*} imports.
 */
public final class PlayerMentionInbox {
  public enum State {
    PENDING,
    ACKNOWLEDGED,
    RESOLVED,
    EXPIRED
  }

  public static final int MAX_ENTRIES = 32;
  public static final long DEFAULT_TTL_MS = 120_000L;
  public static final long GUEST_COOLDOWN_MS = 10_000L;

  public static final class Mention {
    public final String id;
    public final String scope;
    public final long worldGeneration;
    public final long createdMs;
    public final long expiresAtMs;
    public final String senderUuid;
    public final String senderName;
    public final MentionPolicy.Role role;
    public final String matchedAlias;
    public final String text;
    public State state;
    public String taskId = "";
    public boolean ackSent;
    public int guestReplyCount;
    public String resolution = "";
    public long resolvedAtMs;

    Mention(
        String id,
        String scope,
        long worldGeneration,
        long createdMs,
        long expiresAtMs,
        String senderUuid,
        String senderName,
        MentionPolicy.Role role,
        String matchedAlias,
        String text) {
      this.id = id;
      this.scope = scope;
      this.worldGeneration = worldGeneration;
      this.createdMs = createdMs;
      this.expiresAtMs = expiresAtMs;
      this.senderUuid = senderUuid;
      this.senderName = senderName;
      this.role = role;
      this.matchedAlias = matchedAlias;
      this.text = text;
      this.state = State.PENDING;
    }

    public JsonObject toJson() {
      var o = new JsonObject();
      o.addProperty("schema", 1);
      o.addProperty("mention_id", id);
      o.addProperty("scope", scope);
      o.addProperty("world_generation", worldGeneration);
      o.addProperty("at_ms", createdMs);
      o.addProperty("expires_at_ms", expiresAtMs);
      var sender = new JsonObject();
      sender.addProperty("uuid", senderUuid);
      sender.addProperty("name", senderName);
      sender.addProperty("role", role.name());
      o.add("sender", sender);
      o.addProperty("matched_alias", matchedAlias);
      o.addProperty("text", text);
      o.addProperty("untrusted_player_text", true);
      var caps = new JsonArray();
      for (String c : MentionPolicy.capabilities(role)) caps.add(c);
      o.add("capabilities", caps);
      o.addProperty("state", state.name());
      if (!taskId.isBlank()) o.addProperty("task_id", taskId);
      if (!resolution.isBlank()) o.addProperty("resolution", resolution);
      return o;
    }
  }

  private final List<Mention> entries = new ArrayList<>();
  private final Map<String, Long> guestLastAt = new HashMap<>();

  public synchronized Mention enqueue(
      String scope,
      long worldGeneration,
      String senderUuid,
      String senderName,
      MentionPolicy.Role role,
      String alias,
      String text,
      long nowMs) {
    pruneLocked(scope, nowMs);
    String safeScope = scope == null ? "" : scope;
    String safeUuid = senderUuid == null ? "" : senderUuid.strip();
    String safeName = senderName == null ? "" : Rules.clean(senderName, 32);
    String cleanText = Rules.clean(text == null ? "" : text, 400).trim();
    if (safeUuid.isBlank() || safeName.isBlank() || cleanText.isBlank()) return null;

    if (role == MentionPolicy.Role.GUEST) {
      String rateKey = safeScope + "|" + safeUuid;
      Long prev = guestLastAt.get(rateKey);
      if (prev != null && nowMs - prev < GUEST_COOLDOWN_MS) return null;
      guestLastAt.put(rateKey, nowMs);
    }

    while (entries.size() >= MAX_ENTRIES) {
      int removeIdx = -1;
      for (int i = 0; i < entries.size(); i++) {
        State st = entries.get(i).state;
        if (st == State.RESOLVED || st == State.EXPIRED) {
          removeIdx = i;
          break;
        }
      }
      entries.remove(removeIdx >= 0 ? removeIdx : 0);
    }

    Mention m =
        new Mention(
            "m-" + UUID.randomUUID().toString().substring(0, 12),
            safeScope,
            worldGeneration,
            nowMs,
            nowMs + DEFAULT_TTL_MS,
            safeUuid,
            safeName,
            role,
            alias == null ? "" : alias,
            cleanText);
    entries.add(m);
    return m;
  }

  public synchronized Mention get(String mentionId, String scope, long nowMs) {
    pruneLocked(scope, nowMs);
    if (mentionId == null || mentionId.isBlank()) return null;
    for (Mention m : entries) {
      if (m.id.equals(mentionId) && Objects.equals(m.scope, scope) && m.state != State.EXPIRED) {
        return m;
      }
    }
    return null;
  }

  public synchronized Mention acknowledge(String mentionId, String scope, long nowMs) {
    Mention m = get(mentionId, scope, nowMs);
    if (m == null || m.state == State.RESOLVED) return null;
    if (m.state == State.PENDING) m.state = State.ACKNOWLEDGED;
    return m;
  }

  public synchronized Mention markReplySent(
      String mentionId,
      String scope,
      MentionPolicy.ReplyPhase phase,
      boolean resolve,
      long nowMs) {
    Mention m = get(mentionId, scope, nowMs);
    if (m == null) {
      throw new IllegalArgumentException("[MENTION_NOT_FOUND] 未找到有效或未过期的玩家提及会话");
    }
    if (m.role == MentionPolicy.Role.GUEST) {
      if (phase != MentionPolicy.ReplyPhase.CHAT) {
        throw new IllegalArgumentException("[PERMISSION_DENIED] 访客会话仅允许安全 CHAT 回复");
      }
      if (m.guestReplyCount >= 1 || m.state == State.RESOLVED) {
        throw new IllegalArgumentException("[GUEST_REPLY_LIMIT] 单次访客提及最多允许 1 条回复并已结案");
      }
      m.guestReplyCount++;
      m.state = State.RESOLVED;
      m.resolution = "guest_chat_replied";
      m.resolvedAtMs = nowMs;
      return m;
    }
    if (m.state == State.PENDING) m.state = State.ACKNOWLEDGED;
    if (phase == MentionPolicy.ReplyPhase.ACK) m.ackSent = true;
    if (resolve || phase == MentionPolicy.ReplyPhase.FINAL) {
      m.state = State.RESOLVED;
      m.resolution = phase == MentionPolicy.ReplyPhase.CHAT ? "chat_replied" : "final_replied";
      m.resolvedAtMs = nowMs;
    }
    return m;
  }

  public synchronized String resolveAsWork(
      String mentionId, String scope, String taskId, long nowMs) {
    Mention m = get(mentionId, scope, nowMs);
    if (m == null) {
      throw new IllegalArgumentException("[MENTION_NOT_FOUND] 未找到有效或未过期的玩家提及会话");
    }
    if (!MentionPolicy.canWork(m.role)) {
      throw new IllegalArgumentException("[PERMISSION_DENIED] 访客仅允许安全聊天，禁止创建或执行任务");
    }
    if (m.state == State.RESOLVED && !m.taskId.isBlank()) {
      return m.taskId;
    }
    if (taskId == null || taskId.isBlank()) {
      throw new IllegalArgumentException("[INVALID_ARGUMENT] task_id 不能为空");
    }
    m.taskId = taskId;
    m.state = State.RESOLVED;
    m.resolution = "queued_for_work";
    m.resolvedAtMs = nowMs;
    return m.taskId;
  }

  public synchronized JsonArray listActive(String scope, long nowMs) {
    pruneLocked(scope, nowMs);
    var arr = new JsonArray();
    for (Mention m : entries) {
      if (!Objects.equals(m.scope, scope)) continue;
      if (m.state == State.PENDING
          || m.state == State.ACKNOWLEDGED
          || (m.state == State.RESOLVED && nowMs - m.resolvedAtMs <= 30_000L)) {
        arr.add(m.toJson());
      }
    }
    return arr;
  }

  public synchronized void expireAll(String reason) {
    for (Mention m : entries) {
      if (m.state == State.PENDING || m.state == State.ACKNOWLEDGED) {
        m.state = State.EXPIRED;
        m.resolution = reason == null ? "expired" : reason;
      }
    }
  }

  public synchronized void clearScope(String scope) {
    entries.removeIf(m -> Objects.equals(m.scope, scope));
  }

  private void pruneLocked(String scope, long nowMs) {
    for (Mention m : entries) {
      if ((m.state == State.PENDING || m.state == State.ACKNOWLEDGED) && nowMs > m.expiresAtMs) {
        m.state = State.EXPIRED;
        m.resolution = "ttl_expired";
      } else if (scope != null
          && !scope.isBlank()
          && !Objects.equals(m.scope, scope)
          && (m.state == State.PENDING || m.state == State.ACKNOWLEDGED)) {
        m.state = State.EXPIRED;
        m.resolution = "scope_changed";
      }
    }
    entries.removeIf(
        m ->
            (m.state == State.EXPIRED && nowMs - m.expiresAtMs > 60_000L)
                || (m.state == State.RESOLVED && m.resolvedAtMs > 0 && nowMs - m.resolvedAtMs > 120_000L));
    guestLastAt.entrySet().removeIf(e -> nowMs - e.getValue() > 60_000L);
  }
}
