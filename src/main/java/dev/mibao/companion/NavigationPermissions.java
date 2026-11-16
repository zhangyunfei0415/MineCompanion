package dev.mibao.companion;

/** Shared effective terrain permissions for mining, pathfinding and the panel. */
public final class NavigationPermissions {
  private NavigationPermissions() {}

  static boolean canBreak(Store.Config c) {
    return !c.navigationDetourOnly
        && (c.fullGameControl || "FULL".equals(c.permission) || c.navigationBreak && c.allowMine);
  }

  static boolean canPlace(Store.Config c) {
    return !c.navigationDetourOnly
        && (c.fullGameControl || "FULL".equals(c.permission) || c.navigationPlace && c.allowPlace);
  }

  static boolean canBreak() {
    return canBreak(Store.config);
  }

  static boolean canPlace() {
    return canPlace(Store.config);
  }
}
