package dev.mibao.companion;

/** Preserve vanilla absolute floors while respecting modded maximum health. */
public final class SurvivalPolicy {
  public static boolean blocked(double health, double threshold, boolean testing) {
    return !testing && health < threshold;
  }

  public static double critical(double maximum) {
    return Math.max(5, maximum * .25);
  }

  public static double work(double maximum) {
    return Math.max(10, maximum * .4);
  }

  public static double combat(double maximum) {
    return Math.max(12, maximum * .5);
  }
}
