package dev.mibao.companion;

import java.util.*;

/** Per-job target memory: let transient failures cool down, but never retry forever. */
public final class TargetRecovery<T> {
  private record Failure(int attempts, int after, String reason) {}

  private final Map<T, Failure> failures = new LinkedHashMap<>();
  private final int maxAttempts, maxTargets;

  public TargetRecovery(int attempts, int targets) {
    if (attempts < 1 || targets < 1) throw new IllegalArgumentException();
    maxAttempts = attempts;
    maxTargets = targets;
  }

  public boolean available(T target, int tick) {
    var f = failures.get(target);
    return f == null || f.attempts < maxAttempts && tick >= f.after;
  }

  public void reject(T target, int tick, int cooldown, String reason) {
    if (target == null) return;
    var old = failures.get(target);
    if (old == null && failures.size() >= maxTargets) return;
    failures.put(
        target,
        new Failure(old == null ? 1 : old.attempts + 1, tick + Math.max(1, cooldown), reason));
  }

  public boolean waiting(int tick) {
    return failures.values().stream().anyMatch(f -> f.attempts < maxAttempts && tick < f.after);
  }

  public boolean full() {
    return failures.size() >= maxTargets;
  }

  public int size() {
    return failures.size();
  }

  public String lastReason() {
    return failures.isEmpty()
        ? ""
        : new ArrayList<>(failures.values()).get(failures.size() - 1).reason;
  }
}
