package dev.mibao.companion;

/**
 * Pure teardown reach, approach, and scaffold column-exit decision rules.
 * Zero {@code net.minecraft.*} dependencies for offline JUnit verification.
 */
public final class TeardownReachPlanner {
  public static final double LOCAL_MINE_MAX_DISTANCE = 18.0;
  public static final double APPROACH_MAX_DISTANCE = 96.0;

  public enum PlanMode {
    LOCAL_MINE,
    APPROACH_THEN_MINE,
    TOO_FAR
  }

  public record Decision(PlanMode mode, int mineRadius, String reason) {}

  public enum ExitAction {
    SAFE,
    DESCEND,
    STEP_OUT,
    BLOCKED
  }

  private TeardownReachPlanner() {}

  public static Decision plan(double distance, boolean chunkLoaded) {
    if (!chunkLoaded) {
      return new Decision(PlanMode.TOO_FAR, 0, "[UNLOADED_TARGET] 栈顶脚手架区块未加载，为防悬空坍塌已保账停拆");
    }
    if (distance <= LOCAL_MINE_MAX_DISTANCE) {
      int radius = Math.max(2, Math.min(24, (int) Math.ceil(distance) + 4));
      return new Decision(PlanMode.LOCAL_MINE, radius, "");
    }
    if (distance <= APPROACH_MAX_DISTANCE) {
      return new Decision(PlanMode.APPROACH_THEN_MINE, 24, "");
    }
    return new Decision(
        PlanMode.TOO_FAR,
        0,
        "[SCAFFOLD_TOO_FAR] 栈顶脚手架距当前位置 "
            + Math.round(distance)
            + " 格（>"
            + (int) APPROACH_MAX_DISTANCE
            + "格），已保留剩余账本停拆");
  }

  public static int descentBudgetTicks(int columnHeight) {
    return Math.min(300, 20 + 12 * Math.max(1, columnHeight));
  }

  public static ExitAction evaluateClimbExit(
      boolean hasPlayer,
      boolean onClimbable,
      boolean onGround,
      boolean belowIsScaffolding,
      boolean inScaffolding,
      boolean hasSafeStepOut,
      int descentTicks,
      int stuckTicks,
      int maxDescentTicks) {
    if (!hasPlayer) return ExitAction.BLOCKED;
    boolean anchoredInScaffold = onClimbable || belowIsScaffolding || inScaffolding;
    if (!anchoredInScaffold) {
      return onGround ? ExitAction.SAFE : ExitAction.BLOCKED;
    }
    if (belowIsScaffolding || !onGround) {
      if (stuckTicks < 12 && descentTicks < maxDescentTicks) {
        return ExitAction.DESCEND;
      }
    }
    return hasSafeStepOut ? ExitAction.STEP_OUT : ExitAction.BLOCKED;
  }

  public static boolean needsSolidStepOff(
      int playerX, int playerY, int playerZ, int targetX, int targetY, int targetZ) {
    return playerX == targetX
        && playerZ == targetZ
        && (targetY == playerY - 1 || targetY == playerY || targetY == playerY + 1);
  }

  public static boolean stepOffVerified(
      boolean onGround, boolean onScaffolding, boolean onTrackedSupport, boolean stillNeedsStepOff) {
    return onGround && !onScaffolding && !onTrackedSupport && !stillNeedsStepOff;
  }

  public static boolean shouldCollectTeardownDrops(
      boolean survivalOrAdventure, int removedCount, boolean hasRemovedTypes) {
    return survivalOrAdventure && removedCount > 0 && hasRemovedTypes;
  }
}
