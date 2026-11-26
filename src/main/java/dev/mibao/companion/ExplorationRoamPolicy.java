package dev.mibao.companion;

/**
 * Pure outbound exploration roaming, negative-direction cooldowns, anti-0-step progress grading,
 * and guide-player-home pacing policies. Zero {@code net.minecraft.*} imports so offline JUnit tests
 * verify every rule directly.
 */
public final class ExplorationRoamPolicy {
  public static final long NEGATIVE_CACHE_MS = 5 * 60_000L;
  public static final long COMMITMENT_WINDOW_MS = 20_000L;
  public static final double COMMITMENT_MIN_DISPLACEMENT = 16.0;
  public static final int STUCK_WINDOW_TICKS = 100;
  public static final int STUCK_GRACE_TICKS = 25;

  // N, NE, E, SE, S, SW, W, NW
  public static final int[] HEADING_DX = {0, 1, 1, 1, 0, -1, -1, -1};
  public static final int[] HEADING_DZ = {-1, -1, 0, 1, 1, 1, 0, -1};

  public enum GuideAction {
    LEAD,
    WAIT_FACE_PLAYER,
    REGROUP_STEP_BACK,
    TARGET_LOST
  }

  public record Progress(
      int stepsWalked,
      double netDisplacement,
      int newCells,
      int newChunks,
      long elapsedMs,
      long stuckMs) {
    public boolean madeProgress() {
      return stepsWalked > 0 || newCells > 0 || netDisplacement >= 1.5;
    }

    public double chunksPerMinute() {
      if (elapsedMs <= 0 || newChunks <= 0) return 0.0;
      return newChunks * 60_000.0 / elapsedMs;
    }

    public double stuckRatio() {
      return elapsedMs <= 0 ? 0.0 : Math.min(1.0, (double) stuckMs / elapsedMs);
    }
  }

  private ExplorationRoamPolicy() {}

  public static long chunkKey(int x, int z) {
    return (((long) (x >> 4)) << 32) | ((z >> 4) & 0xffffffffL);
  }

  public static long sectorKey(int x, int z) {
    return (((long) (x >> 3)) << 32) | ((z >> 3) & 0xffffffffL);
  }

  public static int headingIndexFromVector(double dx, double dz) {
    if (Math.abs(dx) < 1e-4 && Math.abs(dz) < 1e-4) return 0;
    double angle = Math.atan2(dx, -dz); // 0 = North (-Z), pi/2 = East (+X)
    int idx = (int) Math.round(angle / (Math.PI / 4.0));
    return Math.floorMod(idx, 8);
  }

  public static int pickHeading(
      int currentHeading,
      int[] sectorVisits8,
      long[] blockedUntilMs8,
      long nowMs,
      boolean committed,
      long committedSinceMs,
      double displacedSinceCommit) {
    if (sectorVisits8 == null || sectorVisits8.length != 8)
      throw new IllegalArgumentException("sectorVisits8 must have length 8");
    if (blockedUntilMs8 == null || blockedUntilMs8.length != 8)
      throw new IllegalArgumentException("blockedUntilMs8 must have length 8");
    if (committed
        && currentHeading >= 0
        && currentHeading < 8
        && nowMs >= blockedUntilMs8[currentHeading]
        && (nowMs - committedSinceMs < COMMITMENT_WINDOW_MS
            || displacedSinceCommit < COMMITMENT_MIN_DISPLACEMENT)) {
      if (nowMs - committedSinceMs < COMMITMENT_WINDOW_MS
          && displacedSinceCommit < COMMITMENT_MIN_DISPLACEMENT) {
        return currentHeading;
      }
    }
    int best = -1;
    int bestVisits = Integer.MAX_VALUE;
    int bestTurn = Integer.MAX_VALUE;
    for (int h = 0; h < 8; h++) {
      if (nowMs < blockedUntilMs8[h]) continue;
      int visits = sectorVisits8[h];
      int turn = currentHeading < 0 ? 0 : angularDistance(currentHeading, h);
      if (visits < bestVisits || (visits == bestVisits && turn < bestTurn)) {
        best = h;
        bestVisits = visits;
        bestTurn = turn;
      }
    }
    return best;
  }

  private static int angularDistance(int a, int b) {
    int d = Math.floorMod(a - b, 8);
    return Math.min(d, 8 - d);
  }

  public static boolean isStuckTick(int consecutiveUnprogressedTicks) {
    return consecutiveUnprogressedTicks > STUCK_GRACE_TICKS;
  }

  public static double headingAlignment(double dx, double dz, int heading) {
    if (heading < 0 || heading >= 8) return 0.0;
    double dist = Math.hypot(dx, dz);
    if (dist < 1e-4) return 0.0;
    double hdx = HEADING_DX[heading];
    double hdz = HEADING_DZ[heading];
    return (dx * hdx + dz * hdz) / (dist * Math.hypot(hdx, hdz));
  }

  public static int pickHeadingGuided(
      int currentHeading,
      int preferredHeading,
      int[] sectorVisits8,
      long[] blockedUntilMs8,
      long nowMs,
      boolean committed,
      long committedSinceMs,
      double displacedSinceCommit) {
    if (sectorVisits8 == null || sectorVisits8.length != 8)
      throw new IllegalArgumentException("sectorVisits8 must have length 8");
    if (blockedUntilMs8 == null || blockedUntilMs8.length != 8)
      throw new IllegalArgumentException("blockedUntilMs8 must have length 8");
    if (committed
        && currentHeading >= 0
        && currentHeading < 8
        && nowMs >= blockedUntilMs8[currentHeading]
        && nowMs - committedSinceMs < COMMITMENT_WINDOW_MS
        && displacedSinceCommit < COMMITMENT_MIN_DISPLACEMENT) {
      return currentHeading;
    }
    int best = -1;
    double bestScore = Double.MAX_VALUE;
    int bestTurn = Integer.MAX_VALUE;
    for (int h = 0; h < 8; h++) {
      if (nowMs < blockedUntilMs8[h]) continue;
      double score = sectorVisits8[h];
      int turn = currentHeading < 0 ? 0 : angularDistance(currentHeading, h);
      if (currentHeading >= 0 && currentHeading < 8) {
        score += turn == 4 ? 12.0 : turn == 3 ? 6.0 : turn * 0.75;
      }
      if (preferredHeading >= 0 && preferredHeading < 8) {
        score += angularDistance(preferredHeading, h) * 3.5;
      }
      if (score < bestScore - 1e-6 || (Math.abs(score - bestScore) <= 1e-6 && turn < bestTurn)) {
        best = h;
        bestScore = score;
        bestTurn = turn;
      }
    }
    return best;
  }

  public static double scoreFrontier(
      double dx,
      double dz,
      int currentHeading,
      int preferredHeading,
      int chunkVisits,
      boolean walkedBefore,
      boolean visitedThisRun) {
    double dist = Math.hypot(dx, dz);
    double curAlign = headingAlignment(dx, dz, currentHeading);
    double prefAlign = headingAlignment(dx, dz, preferredHeading);
    double score = dist * 0.65 + chunkVisits * 5.0 - curAlign * 14.0;
    if (preferredHeading >= 0) score -= prefAlign * 12.0;
    if (curAlign < -0.25) score += 22.0;
    if (walkedBefore) score += 18.0;
    if (visitedThisRun) score += 36.0;
    return score;
  }

  public static double scoreFrontier3d(
      double dx,
      double dy,
      double dz,
      int currentHeading,
      int preferredHeading,
      int chunkVisits,
      boolean walkedBefore,
      boolean visitedThisRun,
      boolean verticalConnector) {
    double score =
        scoreFrontier(
            dx, dz, currentHeading, preferredHeading, chunkVisits, walkedBefore, visitedThisRun);
    score += Math.abs(dy) * 0.45;
    if (verticalConnector && !visitedThisRun && Math.abs(dy) >= 1.0) {
      score -= 8.0;
    }
    return score;
  }

  public static double scoreCorridorCell(
      double dx,
      double dz,
      int pathSteps,
      int openNeighbors,
      int currentHeading,
      int preferredHeading,
      int chunkVisits,
      boolean walkedBefore,
      boolean visitedThisRun) {
    double curAlign = headingAlignment(dx, dz, currentHeading);
    double prefAlign = headingAlignment(dx, dz, preferredHeading);
    double score = !walkedBefore ? 30.0 : (!visitedThisRun ? 6.0 : -24.0);
    score += curAlign * 14.0 + (preferredHeading >= 0 ? prefAlign * 14.0 : 0.0);
    if (curAlign < -0.35 && visitedThisRun) score -= 18.0;
    score +=
        Math.min(pathSteps, 16) * 1.1
            + openNeighbors * 2.2
            - Math.min(chunkVisits, 12) * 1.2;
    return score;
  }

  public static GuideAction guideAction(
      boolean playerLoaded, double playerDist, boolean lineOfSight, int waitTicks) {
    if (!playerLoaded || playerDist > 48.0) return GuideAction.TARGET_LOST;
    if (playerDist > 16.0 || (playerDist > 9.0 && waitTicks >= 160))
      return GuideAction.REGROUP_STEP_BACK;
    if (playerDist > 7.5 || (!lineOfSight && playerDist > 5.0))
      return GuideAction.WAIT_FACE_PLAYER;
    return GuideAction.LEAD;
  }
}
