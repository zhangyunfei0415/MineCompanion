package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BowAimTest {
  @Test
  void launchSpeedMatchesVanillaThresholdsAndCapsAtTwentyTicks() {
    assertTrue(BowAim.launchSpeed(-1).isEmpty());
    assertTrue(BowAim.launchSpeed(0).isEmpty());
    assertTrue(BowAim.launchSpeed(2).isEmpty());
    assertTrue(BowAim.launchSpeed(3).isPresent());
    assertEquals(3.0, BowAim.launchSpeed(20).orElseThrow(), 1e-6);
    assertEquals(BowAim.launchSpeed(20).orElseThrow(), BowAim.launchSpeed(22).orElseThrow(), 1e-6);
  }

  @Test
  void discreteTrajectoryHitsStaticLowAndHighArcWithinTolerance() {
    double speed = BowAim.launchSpeed(20).orElseThrow();
    var low = BowAim.elevation(24.0, 2.0, speed, BowAim.Arc.LOW, 200);
    assertEquals(BowAim.AimStatus.HIT, low.status());
    var lowSol = low.solution().orElseThrow();
    assertTrue(lowSol.missDistance() <= 1e-6);

    var high = BowAim.elevation(24.0, 2.0, speed, BowAim.Arc.HIGH, 200);
    assertEquals(BowAim.AimStatus.HIT, high.status());
    var highSol = high.solution().orElseThrow();
    assertTrue(highSol.missDistance() <= 1e-6);
    assertTrue(highSol.elevation() > lowSol.elevation());
    assertTrue(highSol.flightTicks() > lowSol.flightTicks());
  }

  @Test
  void distinguishesTimeLimitFromOutOfRangeAndNeverSwapsArcs() {
    double speed = BowAim.launchSpeed(20).orElseThrow();
    // Low arc succeeds inside 30 ticks, whereas high arc takes much longer and hits TIME_LIMIT.
    var low = BowAim.elevation(12.0, 0.0, speed, BowAim.Arc.LOW, 30);
    assertEquals(BowAim.AimStatus.HIT, low.status());

    var highTight = BowAim.elevation(12.0, 0.0, speed, BowAim.Arc.HIGH, 30);
    assertEquals(BowAim.AimStatus.TIME_LIMIT, highTight.status());
    assertTrue(highTight.solution().isEmpty());

    var tooFar = BowAim.elevation(250.0, 50.0, speed, BowAim.Arc.LOW, 200);
    assertEquals(BowAim.AimStatus.OUT_OF_RANGE, tooFar.status());
    assertTrue(tooFar.solution().isEmpty());
  }

  @Test
  void movingTargetSolvePredictsInterceptAndRejectsUndercharged() {
    var origin = new BowAim.V3(0.0, 65.5, 0.0);
    var target = new BowAim.V3(16.0, 65.5, 4.0);
    var vel = new BowAim.V3(-0.15, 0.0, 0.22);

    var undercharged = BowAim.solve(origin, target, vel, 2, BowAim.Arc.LOW, 100);
    assertEquals(BowAim.AimStatus.UNDERCHARGED, undercharged.status());

    var hit = BowAim.solve(origin, target, vel, 22, BowAim.Arc.LOW, 100);
    assertEquals(BowAim.AimStatus.HIT, hit.status());
    var sol = hit.solution().orElseThrow();
    assertTrue(sol.missDistance() <= 0.01);

    // Verify velocity reconstructed from (yaw, elevation) lands on intercept at flightTicks.
    double speed = BowAim.launchSpeed(22).orElseThrow();
    double cosE = Math.cos(sol.elevation());
    var launchVel =
        new BowAim.V3(
            -Math.sin(sol.yaw()) * cosE * speed,
            Math.sin(sol.elevation()) * speed,
            Math.cos(sol.yaw()) * cosE * speed);
    var landed = BowAim.positionAt(origin, launchVel, sol.flightTicks());
    var expectedTarget = target.add(vel.scale(sol.flightTicks()));
    assertEquals(0.0, landed.distanceTo(expectedTarget), 0.01);
  }

  @Test
  void shortFlatShotConvergesToAccurateFlightTimeAndPrescribedSpeed() {
    double speed = BowAim.launchSpeed(20).orElseThrow();
    var res = BowAim.elevation(0.3, 0.0, speed, BowAim.Arc.LOW, 100);
    assertEquals(BowAim.AimStatus.HIT, res.status());
    var sol = res.solution().orElseThrow();
    assertTrue(sol.flightTicks() < 0.25);
    assertTrue(sol.missDistance() <= 1e-6);
  }
}
