package dev.mibao.companion;

/** Stable companion defaults shared by the follow executor and its tests. */
final class FollowPolicy {
  static final int PORTAL_RANGE = 40;
  static final int PORTAL_FRESH_TICKS = 120;
  static final int PORTAL_WALK_TICKS = 600;

  static boolean freshPortal(int ageTicks) {
    return ageTicks >= 0 && ageTicks <= PORTAL_FRESH_TICKS;
  }

  static boolean chase(double distance, boolean alreadyChasing) {
    return alreadyChasing ? distance > 4 : distance >= 7;
  }

  static boolean chase(double distance, boolean alreadyChasing, String rangeMode) {
    if ("CLOSE".equalsIgnoreCase(rangeMode)) return alreadyChasing ? distance > 3 : distance >= 5;
    if ("WIDE".equalsIgnoreCase(rangeMode)) return alreadyChasing ? distance > 8 : distance >= 12;
    return chase(distance, alreadyChasing);
  }

  static boolean allowPortal(String profile, String kind, boolean allowEnd) {
    return kind.equals("nether") && profile.equals("nether")
        || kind.equals("end") && profile.equals("end") && allowEnd;
  }

  private FollowPolicy() {}
}
