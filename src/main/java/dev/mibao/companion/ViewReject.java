package dev.mibao.companion;

/**
 * Pure formatter for rejected view/observation numbers. Keeping {@code expected_view} and {@code
 * actual_view} inside the receipt text makes stale-view failures diagnosable without changing the
 * receipt JSON contract. No game runtime required.
 */
public final class ViewReject {
  private ViewReject() {}

  /** One stale-view rejection with both the submitted and the current view number. */
  public static String stale(String hint, long expected, long actual) {
    return "[STALE_VIEW] " + hint + "；expected_view=" + expected + " actual_view=" + actual;
  }
}
