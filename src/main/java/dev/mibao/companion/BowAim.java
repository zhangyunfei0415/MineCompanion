package dev.mibao.companion;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Discrete vanilla arrow ballistics matching Forge 1.20.1 BowItem / AbstractArrow physics
 * (drag {@code (double) 0.99F} and gravity {@code (double) 0.05F} per tick).
 */
public final class BowAim {
  public static final double DRAG = (double) 0.99F;
  public static final double GRAVITY = (double) 0.05F;
  private static final int SCAN_MIN_TICKS = 600;
  private static final double MISS_TOLERANCE_STATIC = 1e-6;
  private static final double MISS_TOLERANCE_MOVING = 0.01;

  public record V3(double x, double y, double z) {
    public V3 add(V3 other) {
      return new V3(x + other.x, y + other.y, z + other.z);
    }

    public V3 sub(V3 other) {
      return new V3(x - other.x, y - other.y, z - other.z);
    }

    public V3 scale(double factor) {
      return new V3(x * factor, y * factor, z * factor);
    }

    public double length() {
      return Math.sqrt(x * x + y * y + z * z);
    }

    public double distanceTo(V3 other) {
      return Math.hypot(Math.hypot(x - other.x, y - other.y), z - other.z);
    }

    public boolean isFinite() {
      return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
  }

  public enum Arc {
    LOW,
    HIGH
  }

  public enum AimStatus {
    HIT,
    UNDERCHARGED,
    OUT_OF_RANGE,
    TIME_LIMIT,
    LEAD_NOT_CONVERGED,
    INVALID_INPUT
  }

  public record Solution(
      double yaw,
      double elevation,
      double flightTicks,
      V3 intercept,
      double missDistance,
      Arc arc) {}

  public record AimResult(AimStatus status, Optional<Solution> solution) {
    static AimResult fail(AimStatus status) {
      return new AimResult(status, Optional.empty());
    }

    static AimResult hit(Solution solution) {
      return new AimResult(AimStatus.HIT, Optional.of(solution));
    }
  }

  private BowAim() {}

  /** Computes vanilla launch speed from bow charge ticks, or empty when undercharged (&lt; 0.1). */
  public static OptionalDouble launchSpeed(int chargeTicks) {
    if (chargeTicks < 0) return OptionalDouble.empty();
    float u = Math.min(chargeTicks, 20) / 20.0F;
    float power = Math.min((u * u + 2.0F * u) / 3.0F, 1.0F);
    if ((double) power < 0.1D) return OptionalDouble.empty();
    return OptionalDouble.of((double) (3.0F * power));
  }

  /** Simulates exact vanilla discrete tick arrow trajectory with linear fractional tail. */
  public static V3 positionAt(V3 origin, V3 launchVelocity, double ticks) {
    if (origin == null || launchVelocity == null || !origin.isFinite() || !launchVelocity.isFinite()
        || !Double.isFinite(ticks) || ticks < 0.0) {
      throw new IllegalArgumentException("Invalid trajectory parameters");
    }
    int whole = (int) Math.floor(ticks);
    double frac = ticks - whole;
    double px = origin.x;
    double py = origin.y;
    double pz = origin.z;
    double vx = launchVelocity.x;
    double vy = launchVelocity.y;
    double vz = launchVelocity.z;
    for (int i = 0; i < whole; i++) {
      px += vx;
      py += vy;
      pz += vz;
      vx *= DRAG;
      vy = vy * DRAG - GRAVITY;
      vz *= DRAG;
    }
    return new V3(px + frac * vx, py + frac * vy, pz + frac * vz);
  }

  /** Legacy low-arc signature kept finite for long-range callers and tests. */
  public static double elevation(double horizontal, double vertical, double speed) {
    if (!Double.isFinite(horizontal) || !Double.isFinite(vertical) || !Double.isFinite(speed) || speed <= 0.0) {
      return Math.atan2(vertical, Math.max(.01, horizontal));
    }
    var res = elevation(Math.max(0.0, horizontal), vertical, speed, Arc.LOW, 240);
    if (res.status() == AimStatus.HIT && res.solution().isPresent()) {
      return res.solution().get().elevation();
    }
    return Math.atan2(vertical, Math.max(.01, horizontal));
  }

  /** Solves exact discrete drag+gravity elevation for the requested arc without swapping arcs. */
  public static AimResult elevation(
      double horizontal, double vertical, double speed, Arc arc, int maxTicks) {
    if (!Double.isFinite(horizontal) || !Double.isFinite(vertical) || !Double.isFinite(speed)
        || horizontal < 0.0 || speed <= 0.0 || arc == null || maxTicks <= 0) {
      return AimResult.fail(AimStatus.INVALID_INPUT);
    }
    V3 origin = new V3(0.0, 0.0, 0.0);
    V3 target = new V3(0.0, vertical, horizontal);
    return solveTrajectoryRoot(
        origin, target, new V3(0.0, 0.0, 0.0), speed, arc, maxTicks, MISS_TOLERANCE_STATIC);
  }

  /** Solves moving-target lead + exact discrete drag+gravity trajectory for charged bow release. */
  public static AimResult solve(
      V3 origin, V3 target, V3 targetVelocity, int chargeTicks, Arc arc, int maxTicks) {
    if (origin == null || target == null || targetVelocity == null || arc == null
        || !origin.isFinite() || !target.isFinite() || !targetVelocity.isFinite()
        || chargeTicks < 0 || maxTicks <= 0) {
      return AimResult.fail(AimStatus.INVALID_INPUT);
    }
    OptionalDouble speedOpt = launchSpeed(chargeTicks);
    if (speedOpt.isEmpty()) {
      return AimResult.fail(AimStatus.UNDERCHARGED);
    }
    double speed = speedOpt.getAsDouble();
    if (targetVelocity.length() < 1e-12) {
      return solveTrajectoryRoot(
          origin, target, targetVelocity, speed, arc, maxTicks, MISS_TOLERANCE_STATIC);
    }

    // Fast 4-round time-of-flight fixed-point pass on static intercepts.
    double tof = Math.min(maxTicks, origin.distanceTo(target) / speed);
    Solution lastValid = null;
    for (int round = 0; round < 4; round++) {
      V3 predicted = target.add(targetVelocity.scale(tof));
      AimResult step =
          solveTrajectoryRoot(
              origin, predicted, new V3(0.0, 0.0, 0.0), speed, arc, maxTicks, MISS_TOLERANCE_STATIC);
      if (step.status() != AimStatus.HIT || step.solution().isEmpty()) {
        break;
      }
      Solution sol = step.solution().get();
      lastValid = sol;
      double nextTof = sol.flightTicks();
      if (Math.abs(nextTof - tof) < 1e-5) {
        V3 launchVel = velocityFromAngles(sol.yaw(), sol.elevation(), speed);
        V3 finalTarget = target.add(targetVelocity.scale(nextTof));
        double miss = positionAt(origin, launchVel, nextTof).distanceTo(finalTarget);
        if (miss <= MISS_TOLERANCE_MOVING) {
          return AimResult.hit(
              new Solution(sol.yaw(), sol.elevation(), nextTof, finalTarget, miss, arc));
        }
        break;
      }
      tof = nextTof;
    }

    // Fallback: solve the exact moving-target envelope root r(t) along discrete ticks.
    AimResult exact =
        solveTrajectoryRoot(
            origin, target, targetVelocity, speed, arc, maxTicks, MISS_TOLERANCE_MOVING);
    if (exact.status() != AimStatus.HIT && lastValid != null) {
      return AimResult.fail(
          exact.status() == AimStatus.OUT_OF_RANGE ? AimStatus.LEAD_NOT_CONVERGED : exact.status());
    }
    return exact;
  }

  public static int chargeTicks() {
    return 22;
  }

  private static V3 velocityFromAngles(double yawRad, double elevRad, double speed) {
    double cosE = Math.cos(elevRad);
    double vx = -Math.sin(yawRad) * cosE * speed;
    double vy = Math.sin(elevRad) * speed;
    double vz = Math.cos(yawRad) * cosE * speed;
    return new V3(vx, vy, vz);
  }

  private static AimResult solveTrajectoryRoot(
      V3 origin,
      V3 target,
      V3 targetVel,
      double speed,
      Arc arc,
      int maxTicks,
      double missTolerance) {
    V3 rel0 = target.sub(origin);
    if (rel0.length() < 1e-9) {
      double yaw = Math.atan2(-targetVel.x, targetVel.z);
      return AimResult.hit(new Solution(yaw, 0.0, 0.0, target, 0.0, arc));
    }

    int targetRootOrdinal = arc == Arc.LOW ? 1 : 2;
    int rootsFound = 0;
    double prevRootT = -1.0;

    double a = 0.0;
    double b = 0.0;
    double d = 1.0;
    double gAccum = 0.0;
    int scanLimit = Math.max(maxTicks, SCAN_MIN_TICKS);

    for (int n = 0; n < scanLimit; n++) {
      double rx0 = rel0.x + n * targetVel.x;
      double ry0 = rel0.y + n * targetVel.y + b;
      double rz0 = rel0.z + n * targetVel.z;
      double drx = targetVel.x;
      double dry = targetVel.y + gAccum;
      double drz = targetVel.z;

      double alpha = drx * drx + dry * dry + drz * drz - speed * speed * d * d;
      double beta = 2.0 * (rx0 * drx + ry0 * dry + rz0 * drz - speed * speed * a * d);
      double gamma = rx0 * rx0 + ry0 * ry0 + rz0 * rz0 - speed * speed * a * a;

      double split = 0.5;
      boolean hasInteriorVertex = false;
      if (Math.abs(alpha) > 1e-18) {
        double vertex = -beta / (2.0 * alpha);
        if (vertex > 1e-9 && vertex < 1.0 - 1e-9) {
          split = vertex;
          hasInteriorVertex = true;
        }
      }

      double[] bounds = hasInteriorVertex ? new double[] {0.0, split, 1.0} : new double[] {0.0, 1.0};
      for (int seg = 0; seg < bounds.length - 1; seg++) {
        double lo = bounds[seg];
        double hi = bounds[seg + 1];
        double qLo = evalQuad(alpha, beta, gamma, lo);
        double qHi = evalQuad(alpha, beta, gamma, hi);

        Double foundF = findRootOnMonotonicSubinterval(alpha, beta, gamma, lo, hi, qLo, qHi, n);
        if (foundF == null) continue;
        double t = n + foundF;
        if (t <= 1e-9 || (prevRootT >= 0.0 && Math.abs(t - prevRootT) < 1e-6)) continue;

        prevRootT = t;
        rootsFound++;
        if (rootsFound < targetRootOrdinal) continue;

        if (t > maxTicks) {
          return AimResult.fail(AimStatus.TIME_LIMIT);
        }
        double aAtT = a + foundF * d;
        double cAtT = b + foundF * gAccum;
        if (aAtT <= 1e-12) continue;

        double ix = rel0.x + targetVel.x * t;
        double iy = rel0.y + targetVel.y * t + cAtT;
        double iz = rel0.z + targetVel.z * t;
        V3 launchVel = new V3(ix / aAtT, iy / aAtT, iz / aAtT);
        double horizSpeed = Math.hypot(launchVel.x, launchVel.z);
        double elev = Math.atan2(launchVel.y, horizSpeed);
        double yaw = horizSpeed > 1e-12
            ? Math.atan2(-launchVel.x, launchVel.z)
            : Math.atan2(-rel0.x, rel0.z);

        V3 actualVel = velocityFromAngles(yaw, elev, speed);
        V3 intercept = target.add(targetVel.scale(t));
        double miss = positionAt(origin, actualVel, t).distanceTo(intercept);
        if (miss > missTolerance) {
          return AimResult.fail(AimStatus.LEAD_NOT_CONVERGED);
        }
        return AimResult.hit(new Solution(yaw, elev, t, intercept, miss, arc));
      }

      a += d;
      b += gAccum;
      d *= DRAG;
      gAccum = DRAG * gAccum + GRAVITY;
    }

    return AimResult.fail(AimStatus.OUT_OF_RANGE);
  }

  private static double evalQuad(double alpha, double beta, double gamma, double f) {
    return (alpha * f + beta) * f + gamma;
  }

  private static Double findRootOnMonotonicSubinterval(
      double alpha, double beta, double gamma, double lo, double hi, double qLo, double qHi, int n) {
    if (n > 0 && Math.abs(qLo) <= 1e-11) return lo;
    if (Math.abs(qHi) <= 1e-11) return hi;

    if ((qLo > 0.0 && qHi < 0.0) || (qLo < 0.0 && qHi > 0.0)) {
      double left = lo;
      double right = hi;
      double fLeft = qLo;
      double fRight = qHi;
      double cur = (left * fRight - right * fLeft) / (fRight - fLeft);
      if (!(cur > left && cur < right)) cur = 0.5 * (left + right);

      for (int iter = 0; iter < 52; iter++) {
        double fCur = evalQuad(alpha, beta, gamma, cur);
        if (Math.abs(fCur) <= 1e-13 || right - left <= 1e-13) {
          return cur;
        }
        double prevSpan = right - left;
        if ((fLeft > 0.0 && fCur > 0.0) || (fLeft < 0.0 && fCur < 0.0)) {
          left = cur;
          fLeft = fCur;
        } else {
          right = cur;
          fRight = fCur;
        }
        if ((iter & 1) == 1 || (right - left) > prevSpan * 0.65) {
          // Force guaranteed interval halving on alternate steps so flat endpoints never stall.
          cur = 0.5 * (left + right);
        } else {
          double denom = fRight - fLeft;
          double secant =
              Math.abs(denom) > 1e-18
                  ? (left * fRight - right * fLeft) / denom
                  : 0.5 * (left + right);
          cur = (secant > left && secant < right) ? secant : 0.5 * (left + right);
        }
      }
      return 0.5 * (left + right);
    }

    // Catch near-tangent maximum-range graze where Q(vertex) touches ~0 without strictly crossing.
    if (alpha > 1e-18) {
      double vertex = -beta / (2.0 * alpha);
      if (vertex >= lo && vertex <= hi) {
        double qVertex = evalQuad(alpha, beta, gamma, vertex);
        if (Math.abs(qVertex) <= 1e-8) return vertex;
      }
    }
    return null;
  }
}
