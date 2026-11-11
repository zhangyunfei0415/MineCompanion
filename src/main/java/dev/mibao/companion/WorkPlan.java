package dev.mibao.companion;

import java.util.*;

/**
 * Adapted from Maid Intelligence engine/plan/TaskPlan.java (MIT), Copyright (c) 2026 RhineIris,
 * commit a2fea9aa6f7079afa37ee608eddb32d1a99a35c3. License: licenses/maidintelligence-MIT.txt.
 * Client adaptation adds immutable, bounded steps and unique result receipts; issuing an action
 * never advances a step.
 */
public final class WorkPlan {
  public enum ScanType {
    BLOCK,
    ENTITY
  }

  public record Step(ScanType scanType, String target, int count, int radius) {
    public Step {
      Objects.requireNonNull(scanType);
      if (target == null || target.isBlank() || target.length() > 256)
        throw new IllegalArgumentException("目标不能为空");
      if (count < 1 || count > 256 || radius < 2 || radius > 24)
        throw new IllegalArgumentException("任务数量或半径越界");
      if (scanType == ScanType.ENTITY && (count > 8 || radius > 16))
        throw new IllegalArgumentException("清怪限制8只、半径16格");
    }
  }

  public final List<Step> steps;
  private int currentStep, completed;
  private final Set<String> receipts = new HashSet<>();

  public WorkPlan(Step... steps) {
    if (steps.length < 1 || steps.length > 32) throw new IllegalArgumentException("任务须含1..32步");
    this.steps = List.of(steps);
  }

  public Step current() {
    return isDone() ? null : steps.get(currentStep);
  }

  public boolean isDone() {
    return currentStep >= steps.size();
  }

  public int progress() {
    return currentStep;
  }

  public int completed() {
    return completed;
  }

  public boolean verify(String receipt) {
    if (isDone() || receipt == null || receipt.isBlank() || !receipts.add(receipt)) return false;
    if (++completed >= current().count()) {
      currentStep++;
      if (!isDone()) {
        completed = 0;
        receipts.clear();
      }
    }
    return true;
  }
}
