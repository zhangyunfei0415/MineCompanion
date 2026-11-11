package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/** Single controller, bounded receipts, renewable timed or explicitly manual lease. */
public final class HarnessSession {
  public static final long TTL = 60_000;
  private long ttl = TTL;

  public void durationSeconds(int seconds) {
    ttl = HarnessLeasePolicy.normalize(seconds) * 1000L;
  }

  public void reconfigure(int seconds, long now) {
    boolean live = active(now);
    durationSeconds(seconds);
    if (live) expires = ttl == 0 ? Long.MAX_VALUE : now + ttl;
  }

  public long ttlSeconds() {
    return ttl / 1000;
  }

  private String lease = "", scope = "";
  private long expires;
  private final Map<String, JsonObject> receipts = new LinkedHashMap<>();
  private final Set<String> seen = new HashSet<>();

  public String connect(String scope, long now) {
    if (!lease.isEmpty() && now < expires)
      throw new IllegalArgumentException("已有Harness持有控制租约，请先释放或等待到期");
    this.scope = scope;
    lease = UUID.randomUUID().toString();
    expires = ttl == 0 ? Long.MAX_VALUE : now + ttl;
    return lease;
  }

  public boolean active(long now) {
    return !lease.isEmpty() && now < expires;
  }

  public boolean expired(long now) {
    return !lease.isEmpty() && now >= expires;
  }

  public long remaining(long now) {
    return active(now) ? ttl == 0 ? -1 : expires - now : 0;
  }

  /** Detach the controller while an explicitly submitted bounded local job finishes. */
  public void revokeLease() {
    lease = "";
    expires = 0;
  }

  public void check(String lease, String scope, long now) {
    if (!this.scope.equals(scope))
      throw new IllegalArgumentException("[WORLD_CHANGED] Harness世界/维度已变，请重新观察，不重放旧动作");
    if (!active(now))
      throw new IllegalArgumentException("[LEASE_EXPIRED] Harness租约已到期或释放，请查status");
    if (!this.lease.equals(lease)) throw new IllegalArgumentException("[LEASE_INVALID] 当前控制器租约不匹配");
    expires = ttl == 0 ? Long.MAX_VALUE : now + ttl;
  }

  public JsonObject existing(String id, String signature) {
    var r = receipts.get(id);
    if (r == null && seen.contains(id))
      throw new IllegalArgumentException("此request_id的回执已归档/属于其他世界，不可重放；先重新观察");
    if (r != null && !r.get("signature").getAsString().equals(signature))
      throw new IllegalArgumentException("相同request_id不能用于不同动作");
    return r;
  }

  public JsonObject begin(String id, String signature, long epoch, String tool) {
    if (id.isBlank() || id.length() > 100 || seen.contains(id))
      throw new IllegalArgumentException("request_id须为1..100字符且不能重复");
    if (seen.size() >= 8192) throw new IllegalArgumentException("单次游戏已达8192动作上限，请先导出记录");
    if (receipts.size() >= 256) {
      String old =
          receipts.entrySet().stream()
              .filter(e -> !e.getValue().get("state").getAsString().equals("running"))
              .map(Map.Entry::getKey)
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("过多动作仍在运行"));
      receipts.remove(old);
    }
    var r = new JsonObject();
    r.addProperty("request_id", id);
    r.addProperty("signature", signature);
    r.addProperty("scope", scope);
    r.addProperty("epoch", epoch);
    r.addProperty("tool", tool);
    r.addProperty("state", "running");
    receipts.put(id, r);
    seen.add(id);
    return r;
  }

  public JsonObject receipt(String id) {
    var r = receipts.get(id);
    if (r == null) throw new IllegalArgumentException("没有此动作回执");
    return r;
  }

  public JsonObject receipt(String id, String currentScope) {
    var r = receipt(id).deepCopy();
    r.addProperty("historical_context", !r.get("scope").getAsString().equals(currentScope));
    r.addProperty("replay_allowed", false);
    return r;
  }

  public void interrupt(String reason) {
    for (var r : receipts.values())
      if (r.get("state").getAsString().equals("running")) {
        r.addProperty("state", "interrupted");
        r.addProperty("result", reason);
      }
    lease = "";
    expires = 0;
  }

  public void invalidateEpoch(long epoch) {
    for (var r : receipts.values())
      if (r.get("state").getAsString().equals("running") && r.get("epoch").getAsLong() != epoch) {
        r.addProperty("state", "interrupted");
        r.addProperty("result", "任务代次已变，旧动作不得重放");
      }
  }

  /** Same-server dimension transition only; controller validates that boundary before calling. */
  public void rebindDimension(String nextScope, long epoch) {
    invalidateEpoch(epoch);
    scope = nextScope;
  }
}
