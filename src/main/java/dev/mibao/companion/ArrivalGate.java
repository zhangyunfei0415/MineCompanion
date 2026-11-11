package dev.mibao.companion;

/** A transient airborne crossing is not a completed navigation stance. */
public final class ArrivalGate {
  private int stable;

  public boolean step(boolean inRange, boolean grounded, double verticalSpeed) {
    if (!inRange || !grounded || Math.abs(verticalSpeed) > .15) {
      stable = 0;
      return false;
    }
    return ++stable >= 3;
  }

  public static boolean near(
      double x, double y, double z, double tx, double ty, double tz, boolean exact) {
    double dx = x - tx, dz = z - tz;
    return dx * dx + dz * dz <= (exact ? .16 : 4) && Math.abs(y - ty) <= (exact ? .22 : .65);
  }

  public static boolean transition(double fromY, double toY) {
    return toY - fromY <= 1.05 && fromY - toY <= 1.25;
  }

  /** Keep a margin for the 0.4-block waypoint tolerance instead of aiming at a circle edge. */
  public static boolean candidate(
      double x, double y, double z, double tx, double ty, double tz, boolean exact) {
    if (exact) return near(x, y, z, tx, ty, tz, true);
    double dx = x - tx, dz = z - tz;
    return dx * dx + dz * dz <= 1.5 * 1.5 && Math.abs(y - ty) <= .65;
  }

  public static boolean advanceWaypoint(
      double distanceSquared, boolean grounded, boolean last, boolean actualGoal) {
    return grounded && distanceSquared < .16 && (!last || actualGoal);
  }
}
