package dev.mibao.companion;

/** Low trajectory for the ordinary fully drawn bow, with a conservative movement lead. */
public final class BowAim {
  private BowAim() {}

  public static double elevation(double horizontal, double vertical, double speed) {
    double g = .05,
        v2 = speed * speed,
        d = v2 * v2 - g * (g * horizontal * horizontal + 2 * vertical * v2);
    if (horizontal < .01 || d < 0) return Math.atan2(vertical, Math.max(.01, horizontal));
    return Math.atan((v2 - Math.sqrt(d)) / (g * horizontal));
  }

  public static int chargeTicks() {
    return 22;
  }
}
