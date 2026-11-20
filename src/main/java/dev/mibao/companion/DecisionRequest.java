package dev.mibao.companion;

/** Main-thread ownership of the one pending judgment request. */
final class DecisionRequest {
  private long sequence;
  private long pending;

  long begin() {
    pending = ++sequence;
    return pending;
  }

  boolean pending() {
    return pending != 0;
  }

  void invalidate() {
    pending = 0;
  }

  boolean finish(long request) {
    if (pending != request) return false;
    pending = 0;
    return true;
  }
}
