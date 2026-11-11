package dev.mibao.companion;

/** Quantity ledger for one bounded restock; callbacks supply verified observations only. */
public final class RestockPlan {
  public enum Step {
    DESTINATION,
    SOURCE,
    FETCH,
    DEPOSIT,
    RECHECK,
    COMPLETE,
    BLOCKED,
    CANCELLED
  }

  public final int target;
  private Step step = Step.DESTINATION;
  private int initial = -1, current = -1, requested, carrying, delivered, trips;
  private String reason = "";
  private boolean uncertain;

  public RestockPlan(int target) {
    if (target < 1 || target > 4096) throw new IllegalArgumentException("补给目标须为 1–4096");
    this.target = target;
  }

  public Step step() {
    return step;
  }

  public int current() {
    return current;
  }

  public int delivered() {
    return delivered;
  }

  public int carrying() {
    return carrying;
  }

  public int requested() {
    return requested;
  }

  public int trips() {
    return trips;
  }

  public int missing() {
    return current < 0 ? 0 : Math.max(0, target - current);
  }

  public String reason() {
    return reason;
  }

  public boolean uncertain() {
    return uncertain;
  }

  public boolean terminal() {
    return step == Step.COMPLETE || step == Step.BLOCKED || step == Step.CANCELLED;
  }

  public void destination(int count) {
    if (step != Step.DESTINATION && step != Step.RECHECK)
      throw new IllegalStateException("Unexpected destination observation");
    if (count < 0) throw new IllegalArgumentException("Negative inventory");
    current = count;
    if (initial < 0) initial = count;
    else if (count != initial + delivered) {
      block("目的库存出现计划之外的变化；停止并重新核对现场");
      return;
    }
    if (carrying > 0) {
      block("目的容器没有足够兼容空间；本任务尚有 " + carrying + " 个物品留在背包");
      return;
    }
    step = count >= target ? Step.COMPLETE : Step.SOURCE;
  }

  public void source(int available) {
    require(Step.SOURCE);
    if (available < 0) throw new IllegalArgumentException("Negative stock");
    if (available == 0) return;
    if (trips >= 64) {
      block("已达到本次补给的 64 趟上限，请核对空间或拆分任务");
      return;
    }
    requested = Math.min(Math.min(missing(), available), 64);
    step = Step.FETCH;
  }

  public void fetched(int verified) {
    require(Step.FETCH);
    if (verified <= 0 || verified > requested) {
      block("取货没有取得有效数量证明");
      return;
    }
    carrying = verified;
    trips++;
    step = Step.DEPOSIT;
  }

  public void deposited(int verified) {
    require(Step.DEPOSIT);
    if (verified < 0 || verified > carrying) {
      block("到货数量证明与本次携带数量不符");
      return;
    }
    delivered += verified;
    carrying -= verified;
    step = Step.RECHECK;
  }

  public void block(String reason) {
    if (terminal()) return;
    this.reason = reason;
    step = Step.BLOCKED;
  }

  public void uncertain(String reason) {
    if (terminal()) return;
    uncertain = true;
    block(reason);
  }

  public void cancel() {
    if (terminal()) return;
    uncertain = true;
    reason = "任务已停止；已发操作可能部分完成，重新执行前需观察现场";
    step = Step.CANCELLED;
  }

  private void require(Step expected) {
    if (step != expected) throw new IllegalStateException("Expected " + expected + ", got " + step);
  }
}
