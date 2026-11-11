package dev.mibao.companion;

/** Missing resources may arrive later; checking never consumes them or extends the deadline. */
public final class SupplyWait {
  public enum State {
    READY,
    WAIT,
    EXPIRED
  }

  private final long budget;
  private long until = -1;

  public SupplyWait(int seconds) {
    if (seconds < 0 || seconds > 120) throw new IllegalArgumentException("等待须为0..120秒");
    budget = seconds * 1000L;
  }

  public State check(long now, boolean available) {
    if (available) return State.READY;
    if (until < 0) until = now + budget;
    return now >= until ? State.EXPIRED : State.WAIT;
  }

  public long remaining(long now) {
    return until < 0 ? budget : Math.max(0, until - now);
  }
}
