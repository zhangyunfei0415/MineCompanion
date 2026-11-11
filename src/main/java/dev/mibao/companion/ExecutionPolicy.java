package dev.mibao.companion;

/** Priority lanes do not grant permissions or bypass transaction receipts. */
public final class ExecutionPolicy {
  public enum Lane {
    REST,
    PAUSED,
    RECOVERY,
    APPROVAL,
    DEFENSE,
    MENU_TRANSACTION,
    LOCAL_ACTION,
    PLANNING,
    IDLE
  }

  public static Lane choose(
      boolean enabled,
      boolean paused,
      boolean managing,
      boolean approval,
      boolean defense,
      boolean menu,
      boolean job,
      boolean planning) {
    if (!enabled) return Lane.REST;
    if (paused || managing) return Lane.PAUSED;
    if (menu) return approval ? Lane.APPROVAL : job ? Lane.MENU_TRANSACTION : Lane.PLANNING;
    if (defense) return Lane.DEFENSE;
    if (approval) return Lane.APPROVAL;
    if (job) return Lane.LOCAL_ACTION;
    return planning ? Lane.PLANNING : Lane.IDLE;
  }
}
