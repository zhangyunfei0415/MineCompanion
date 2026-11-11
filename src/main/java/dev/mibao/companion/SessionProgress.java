package dev.mibao.companion;

/** Receipt-driven advancement: duplicated/late callbacks cannot complete another step. */
public final class SessionProgress {
  private final int total;
  private int completed;
  private String pending = "";
  private boolean blocked;

  public SessionProgress(int total) {
    if (total < 1 || total > 16) throw new IllegalArgumentException();
    this.total = total;
  }

  public int completed() {
    return completed;
  }

  public boolean done() {
    return completed == total;
  }

  public String begin(String ticket) {
    if (done() || blocked || !pending.isEmpty() || ticket == null || ticket.isBlank())
      throw new IllegalStateException("流程已有动作或已结束");
    pending = ticket;
    return ticket;
  }

  public boolean finish(String ticket, boolean success) {
    if (blocked || pending.isEmpty() || !pending.equals(ticket)) return false;
    pending = "";
    if (success) completed++;
    else blocked = true;
    return true;
  }

  public boolean pending() {
    return !pending.isEmpty();
  }
}
