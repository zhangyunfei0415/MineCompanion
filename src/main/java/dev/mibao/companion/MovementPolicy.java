package dev.mibao.companion;

import java.util.Locale;

/**
 * Pure movement policy for auto-sprinting, shallow-water wading, climbable blocks, and bounded
 * parkour jumps. Zero {@code net.minecraft.*} dependencies for offline JUnit testing.
 */
public final class MovementPolicy {
  public enum MoveKind {
    WALK,
    CLIMB_UP,
    CLIMB_DOWN,
    WADE_WATER,
    PARKOUR_SHORT,
    PARKOUR_SPRINT
  }

  private MovementPolicy() {}

  public static double stepCost(MoveKind kind) {
    return switch (kind) {
      case WALK -> 1.0;
      case CLIMB_UP, CLIMB_DOWN -> 1.8;
      case WADE_WATER -> 2.2;
      case PARKOUR_SHORT -> 2.6;
      case PARKOUR_SPRINT -> 4.5;
    };
  }

  public static String normalizeParkourMode(String raw) {
    if (raw == null || raw.isBlank()) return "SAFE";
    return switch (raw.strip().toUpperCase(Locale.ROOT)) {
      case "OFF", "NONE", "DISABLED", "关" -> "OFF";
      case "FULL", "COMPLETE", "完整" -> "FULL";
      default -> "SAFE";
    };
  }

  public static boolean shouldSprint(
      boolean enabled,
      int remainingSteps,
      double distToGoal,
      int foodLevel,
      boolean forward,
      boolean sharpTurn,
      boolean stepUp,
      boolean inWater,
      boolean onClimbable,
      boolean parkourRunup) {
    if (!forward || foodLevel <= 6 || inWater || onClimbable) return false;
    if (parkourRunup) return true;
    return enabled && !sharpTurn && !stepUp && (remainingSteps >= 3 || distToGoal >= 5.0);
  }

  public static boolean parkourAllowed(
      String mode,
      int gapBlocks,
      int deltaY,
      int dropDepthBelowGap,
      int runupCells,
      boolean headroomClear,
      int foodLevel) {
    String m = normalizeParkourMode(mode);
    if ("OFF".equals(m) || !headroomClear) return false;
    if (gapBlocks < 1 || gapBlocks > 3) return false;
    if ("SAFE".equals(m)) {
      if (gapBlocks > 2 || dropDepthBelowGap > 5) return false;
      if (deltaY > (gapBlocks == 1 ? 1 : 0) || deltaY < -1) return false;
    } else {
      // FULL parkour: allows up to 3-block horizontal sprint jumps and deeper drop tolerance.
      if (dropDepthBelowGap > 10) return false;
      if (deltaY > (gapBlocks == 1 ? 1 : 0) || deltaY < -2) return false;
    }
    if (gapBlocks == 2) return runupCells >= 1 && foodLevel > 6;
    if (gapBlocks == 3) return runupCells >= 2 && foodLevel > 6;
    return true;
  }

  public static boolean takeoffReady(double distToTakeoffEdge, double horizSpeed, int gapBlocks) {
    if (gapBlocks <= 1) return distToTakeoffEdge <= 0.36;
    return distToTakeoffEdge <= 0.30 && horizSpeed >= 0.15;
  }
}
