package dev.mibao.companion;

final class DeathPolicy {
  static boolean automatic(String policy) {
    return "RESPAWN_IDLE".equals(policy) || "REASSESS".equals(policy);
  }

  static boolean canResume(String policy, boolean authorized, boolean sameEpoch, boolean alive) {
    return automatic(policy) && authorized && sameEpoch && alive;
  }
}
