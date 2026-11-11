package dev.mibao.companion;

/** Lower scores win; keep an existing valid target rather than sorting every tick. */
public final class TargetScore {
  private TargetScore() {}

  public static double score(
      double boxDistance, boolean damagedPlayer, boolean visible, double health) {
    return Math.max(0, boxDistance) * 10
        + (visible ? 0 : 1000)
        - (damagedPlayer ? 80 : 0)
        + Math.max(0, health) * .02;
  }
}
