package dev.mibao.companion;

/** Accounts for this batch without counting unrelated pre-existing input as returned material. */
final class SmeltBalance {
  record Evidence(int remaining, int unaccounted) {}

  static Evidence check(int requested, int before, int now, int outputDelta, int outputPerInput) {
    int remaining = Math.min(requested, Math.max(0, now - Math.max(0, before - requested)));
    int processed = Math.max(0, outputDelta) / Math.max(1, outputPerInput);
    return new Evidence(remaining, Math.min(requested, Math.max(0, before - now - processed)));
  }
}
