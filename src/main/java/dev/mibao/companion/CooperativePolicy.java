package dev.mibao.companion;

public final class CooperativePolicy {
  public static boolean yield(
      boolean enabled,
      boolean waiting,
      boolean keysHeld,
      boolean ownedMenu,
      boolean screenOpen,
      boolean manualResume,
      long now,
      long until) {
    return enabled
        && waiting
        && (keysHeld || ownedMenu && screenOpen || manualResume || now < until);
  }

  private CooperativePolicy() {}
}
