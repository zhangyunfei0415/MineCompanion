package dev.mibao.companion;

/**
 * Pure ladder, open/operable-trapdoor, climb-steering, and vertical progress rules.
 * Zero {@code net.minecraft.*} dependencies for offline JUnit verification.
 */
public final class ClimbRules {
  public enum ClimbMode {
    APPROACH_CENTER,
    CLIMB_UP,
    CLIMB_DOWN,
    RECOVER_REPLAN
  }

  public record ClimbCommand(
      ClimbMode mode,
      boolean forward,
      boolean jump,
      boolean sneak,
      float targetYaw,
      float targetPitch) {}

  private ClimbRules() {}

  public static boolean isTrapdoorClimbableNow(
      boolean isTrapdoor, boolean open, boolean belowMakesClimbable) {
    return isTrapdoor && open && belowMakesClimbable;
  }

  public static boolean isTrapdoorClimbableForPlanning(
      boolean isTrapdoor,
      boolean woodenTrapdoor,
      boolean open,
      boolean belowMakesClimbable,
      boolean autoOpenDoors) {
    return isTrapdoor && belowMakesClimbable && (open || (woodenTrapdoor && autoOpenDoors));
  }

  public static boolean canDescendVerticallyInto(boolean targetIsClosedTrapdoor) {
    return !targetIsClosedTrapdoor;
  }

  public static boolean canClimbOntoAboveCell(
      boolean fromClosedTrapdoor, boolean aboveIsClimbable) {
    return !fromClosedTrapdoor || aboveIsClimbable;
  }

  public static boolean shouldHoldJumpOnClimbGateWait(
      boolean onClimbable, boolean onGround, boolean goingUp) {
    return onClimbable && !onGround && goingUp;
  }

  public static boolean shouldSneakToStabilizeClimb(boolean onClimbable, boolean onGround) {
    return shouldSneakToStabilizeClimb(onClimbable, onGround, false);
  }

  public static boolean shouldSneakToStabilizeClimb(
      boolean onClimbable, boolean onGround, boolean isScaffolding) {
    return onClimbable && !onGround && !isScaffolding;
  }

  public static boolean isScaffoldingCollisionPassable(boolean isScaffolding, boolean allowClimb) {
    return isScaffolding && allowClimb;
  }

  public static boolean canAcceptClimbableArrival(
      boolean onClimbable, boolean onGround, boolean targetIsClimbable) {
    return !onClimbable || onGround || targetIsClimbable;
  }

  public static boolean shouldCenterLateral(double latDist, boolean wasCentering) {
    return wasCentering ? latDist > 0.08 : latDist > 0.13;
  }

  public static boolean shouldCenterDescent(double horizDist, boolean wasCentering) {
    return wasCentering ? horizDist > 0.14 : horizDist > 0.20;
  }

  public static final class ClimbHysteresis {
    private boolean latCentering;
    private boolean horizCentering;

    public void reset() {
      latCentering = false;
      horizCentering = false;
    }

    public boolean updateLateral(double latDist) {
      latCentering = shouldCenterLateral(latDist, latCentering);
      return latCentering;
    }

    public boolean updateDescent(double horizDist) {
      horizCentering = shouldCenterDescent(horizDist, horizCentering);
      return horizCentering;
    }
  }

  public static ClimbCommand guideClimb(
      double px,
      double py,
      double pz,
      boolean onClimbable,
      boolean isScaffolding,
      int lx,
      int ly,
      int lz,
      int nx,
      int ny,
      int nz,
      float ladderFaceOppositeYaw,
      float currentYaw) {
    return guideClimb(
        px,
        py,
        pz,
        onClimbable,
        isScaffolding,
        lx,
        ly,
        lz,
        nx,
        ny,
        nz,
        ladderFaceOppositeYaw,
        currentYaw,
        null);
  }

  public static ClimbCommand guideClimb(
      double px,
      double py,
      double pz,
      boolean onClimbable,
      boolean isScaffolding,
      int lx,
      int ly,
      int lz,
      int nx,
      int ny,
      int nz,
      float ladderFaceOppositeYaw,
      float currentYaw,
      ClimbHysteresis hysteresis) {
    double cx = lx + 0.5;
    double cz = lz + 0.5;
    double dx = cx - px;
    double dz = cz - pz;
    double horizDist = Math.hypot(dx, dz);
    float centerYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

    if (!onClimbable && (horizDist > 1.0 || py < Math.min(ly, ny) - 1.5)) {
      return new ClimbCommand(ClimbMode.RECOVER_REPLAN, false, false, false, currentYaw, 0f);
    }

    if (!onClimbable && horizDist > 0.28) {
      return new ClimbCommand(ClimbMode.APPROACH_CENTER, true, false, false, centerYaw, 0f);
    }

    boolean goingUp = ny > py - 0.1;
    if (isScaffolding) {
      boolean horiz = horizDist > 0.30;
      float yaw = horiz ? centerYaw : currentYaw;
      return goingUp
          ? new ClimbCommand(ClimbMode.CLIMB_UP, horiz, true, false, yaw, -25f)
          : new ClimbCommand(ClimbMode.CLIMB_DOWN, horiz, false, true, yaw, 25f);
    }

    if (goingUp) {
      if (onClimbable) {
        double rad = Math.toRadians(ladderFaceOppositeYaw);
        double wx = -Math.sin(rad);
        double wz = Math.cos(rad);
        double dot = dx * wx + dz * wz;
        double latX = dx - dot * wx;
        double latZ = dz - dot * wz;
        double latDist = Math.hypot(latX, latZ);
        float climbYaw = ladderFaceOppositeYaw;
        boolean needCenter =
            hysteresis != null
                ? hysteresis.updateLateral(latDist)
                : shouldCenterLateral(latDist, false);
        if (needCenter && latDist > 1.0e-6) {
          double vx = 0.55 * wx + 0.85 * (latX / latDist);
          double vz = 0.55 * wz + 0.85 * (latZ / latDist);
          climbYaw = (float) Math.toDegrees(Math.atan2(-vx, vz));
        }
        return new ClimbCommand(
            ClimbMode.CLIMB_UP, true, true, false, climbYaw, -25f);
      }
      return new ClimbCommand(ClimbMode.CLIMB_UP, false, true, false, currentYaw, -25f);
    }

    boolean horiz =
        hysteresis != null
            ? hysteresis.updateDescent(horizDist)
            : shouldCenterDescent(horizDist, false);
    return new ClimbCommand(
        ClimbMode.CLIMB_DOWN, horiz, false, false, horiz ? centerYaw : currentYaw, 25f);
  }

  public static boolean madeProgress(
      double prevDistSqToNext,
      double curDistSqToNext,
      double prevY,
      double curY,
      int targetDy) {
    if (prevDistSqToNext - curDistSqToNext >= 0.02) return true;
    if (targetDy > 0 && curY - prevY >= 0.05) return true;
    if (targetDy < 0 && prevY - curY >= 0.05) return true;
    return false;
  }
}
