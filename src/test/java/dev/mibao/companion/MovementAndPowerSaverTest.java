package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class MovementAndPowerSaverTest {
  @Test
  void sprintRespectsHungerCornersWaterAndParkourRunup() {
    assertTrue(
        MovementPolicy.shouldSprint(true, 4, 8.0, 20, true, false, false, false, false, false));
    assertFalse(
        MovementPolicy.shouldSprint(true, 4, 8.0, 6, true, false, false, false, false, false));
    assertFalse(
        MovementPolicy.shouldSprint(true, 4, 8.0, 20, true, true, false, false, false, false));
    assertFalse(
        MovementPolicy.shouldSprint(true, 4, 8.0, 20, true, false, false, true, false, false));
    assertFalse(
        MovementPolicy.shouldSprint(true, 4, 8.0, 20, true, false, false, false, true, false));
    assertFalse(
        MovementPolicy.shouldSprint(false, 4, 8.0, 20, true, false, false, false, false, false));
    // Even if general followSprint is off, a parkour 2-3 gap runup still triggers sprint when fed.
    assertTrue(
        MovementPolicy.shouldSprint(false, 1, 2.0, 18, true, false, false, false, false, true));
  }

  @Test
  void parkourPolicyEnforcesSafeVsFullLimitsHeadroomRunupAndDropDepth() {
    assertFalse(MovementPolicy.parkourAllowed("OFF", 1, 0, 2, 2, true, 20));
    assertTrue(MovementPolicy.parkourAllowed("SAFE", 1, 1, 3, 0, true, 20));
    assertTrue(MovementPolicy.parkourAllowed("SAFE", 2, 0, 4, 1, true, 20));
    assertFalse(MovementPolicy.parkourAllowed("SAFE", 2, 0, 4, 0, true, 20));
    assertFalse(MovementPolicy.parkourAllowed("SAFE", 3, 0, 4, 2, true, 20));
    assertFalse(MovementPolicy.parkourAllowed("SAFE", 2, 0, 8, 2, true, 20));
    assertFalse(MovementPolicy.parkourAllowed("SAFE", 1, 0, 2, 1, false, 20));

    assertTrue(MovementPolicy.parkourAllowed("FULL", 3, 0, 8, 2, true, 20));
    assertFalse(MovementPolicy.parkourAllowed("FULL", 3, 0, 8, 1, true, 20));
    assertFalse(MovementPolicy.parkourAllowed("FULL", 3, 0, 8, 2, true, 6));

    assertFalse(MovementPolicy.parkourExecutionAllowed(false, false, 2));
    assertFalse(MovementPolicy.parkourExecutionAllowed(true, true, 2));
    assertFalse(MovementPolicy.parkourExecutionAllowed(true, false, 0));
    assertTrue(MovementPolicy.parkourExecutionAllowed(true, false, 1));

    assertFalse(MovementPolicy.parkourTimedOut(1, 25));
    assertFalse(MovementPolicy.parkourTimedOut(2, 60));
    assertTrue(MovementPolicy.parkourTimedOut(3, 20));
    assertTrue(MovementPolicy.parkourTimedOut(1, 61));

    assertTrue(MovementPolicy.canSkipCollinearWaypoint((byte) 0, (byte) 0));
    assertFalse(MovementPolicy.canSkipCollinearWaypoint((byte) 3, (byte) 0));
    assertFalse(MovementPolicy.canSkipCollinearWaypoint((byte) 0, (byte) 3));
    assertFalse(MovementPolicy.canSkipCollinearWaypoint((byte) 1, (byte) 0));
  }

  @Test
  void weightedGridSearchPrefersDryDetourWhenShortAndWadesWaterWhenBlocked() {
    // 2D corridor at y=0: (0,0) -> (1,0) WATER -> (2,0); dry detour around (0,1)->(1,1)->(2,1)->(2,0)
    var start = new GridSearch.Cell(0, 0, 0);
    var goal = new GridSearch.Cell(2, 0, 0);
    var waterCell = new GridSearch.Cell(1, 0, 0);

    // Case 1: puddle directly through (1,0) costs 2.2 + 1.0 = 3.2, shorter than 4-step dry detour (4.0)
    var gs =
        GridSearch.weighted(
            start,
            c -> c.equals(goal),
            c -> {
              List<GridSearch.Step> list = new ArrayList<>();
              for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                var n = new GridSearch.Cell(c.x() + d[0], 0, c.z() + d[1]);
                if (n.x() < 0 || n.x() > 2 || n.z() < 0 || n.z() > 1) continue;
                double w =
                    n.equals(waterCell)
                        ? MovementPolicy.stepCost(MovementPolicy.MoveKind.WADE_WATER)
                        : MovementPolicy.stepCost(MovementPolicy.MoveKind.WALK);
                list.add(new GridSearch.Step(n, w));
              }
              return list;
            },
            c -> Math.hypot(c.x() - goal.x(), c.z() - goal.z()),
            500);
    assertEquals(GridSearch.State.FOUND, gs.advance(500, 1_000_000_000L));
    assertEquals(List.of(waterCell, goal), gs.path());

    // Case 2: if dry 1-step-equivalent walk around z=1 is cheaper than sprint jump (4.5), dry wins!
    var dryStep = new GridSearch.Cell(1, 0, 1);
    var jumpOver = new GridSearch.Cell(2, 0, 0);
    var gs2 =
        GridSearch.weighted(
            start,
            c -> c.equals(jumpOver),
            c -> {
              if (c.equals(start))
                return List.of(
                    new GridSearch.Step(dryStep, MovementPolicy.stepCost(MovementPolicy.MoveKind.WALK)),
                    new GridSearch.Step(
                        jumpOver, MovementPolicy.stepCost(MovementPolicy.MoveKind.PARKOUR_SPRINT)));
              if (c.equals(dryStep))
                return List.of(
                    new GridSearch.Step(
                        jumpOver, MovementPolicy.stepCost(MovementPolicy.MoveKind.WALK)));
              return List.of();
            },
            c -> 0,
            100);
    assertEquals(GridSearch.State.FOUND, gs2.advance(100, 1_000_000_000L));
    assertEquals(List.of(dryStep, jumpOver), gs2.path());
  }

  @Test
  void watchPowerStateSnapshotsOnceAndRestoresLosslessly() {
    var ps = new WatchPowerState();
    assertEquals(15, WatchPowerState.clampFpsCap(5));
    assertEquals(60, WatchPowerState.clampFpsCap(240));

    // Entering F4 WatchMode captures baseline FPS=144 and Volume=0.75
    var enter = ps.sync(true, true, 20, true, 144, 0.75);
    assertTrue(enter.applyCap());
    assertEquals(20, enter.fpsCap());
    assertTrue(enter.applyMute());
    assertTrue(ps.isApplied());

    // Re-syncing while already active MUST NOT overwrite saved baseline 144/0.75 with 20/0.0!
    var steady = ps.sync(true, true, 20, true, 20, 0.0);
    assertTrue(steady.applyCap());
    assertFalse(steady.restoreFps());

    // Disabling audio mute mid-watch restores 0.75 while keeping 20 FPS cap
    var midAudio = ps.sync(true, true, 20, false, 20, 0.0);
    assertTrue(midAudio.restoreAudio());
    assertEquals(0.75, midAudio.restoredVolume(), 1e-6);
    assertTrue(midAudio.applyCap());

    // Exiting WatchMode restores 144 FPS
    var exit = ps.sync(false, true, 20, false, 20, 0.75);
    assertTrue(exit.restoreFps());
    assertEquals(144, exit.restoredFps());
    assertFalse(ps.isApplied());
  }

  @Test
  void manualPowerSaveDefaultsToBothFpsCapAndMuteAndRestoresCleanly() {
    var ps = new WatchPowerState();
    assertFalse(WatchPowerState.shouldApply(false, false, false, false));
    assertTrue(WatchPowerState.shouldApply(false, true, false, false));

    ps.setManualOverride(true);
    var enter = ps.sync(false, false, 20, false, 165, 0.8);
    assertTrue(enter.applyCap());
    assertEquals(20, enter.fpsCap());
    assertTrue(enter.applyMute());
    assertTrue(ps.isApplied());
    assertTrue(ps.isFpsApplied());
    assertTrue(ps.isAudioApplied());

    ps.setManualOverride(false);
    var exit = ps.sync(false, false, 20, false, 20, 0.8);
    assertTrue(exit.restoreFps());
    assertEquals(165, exit.restoredFps());
    assertTrue(exit.restoreAudio());
    assertEquals(0.8, exit.restoredVolume(), 1e-6);
    assertFalse(ps.isApplied());
    assertFalse(ps.isFpsApplied());
    assertFalse(ps.isAudioApplied());
  }

  @Test
  void settingsCatalogIndexesPowerSaverMovementAndDutyEntries() {
    assertFalse(SettingsCatalog.search("fps").isEmpty());
    assertFalse(SettingsCatalog.search("静音").isEmpty());
    assertFalse(SettingsCatalog.search("跑酷").isEmpty());
    assertFalse(SettingsCatalog.search("梯子").isEmpty());
    assertFalse(SettingsCatalog.search("水坑").isEmpty());
    assertFalse(SettingsCatalog.search("lootr").isEmpty());
  }

  @Test
  void rc12ScaffoldingLedgeSneakHorizontalProgressAndStarvationPause() {
    assertTrue(MovementPolicy.shouldLedgeSneak(true, true, false, false));
    assertFalse(MovementPolicy.shouldLedgeSneak(true, true, false, true));
    assertFalse(MovementPolicy.shouldLedgeSneak(true, true, true, false));

    assertFalse(MovementPolicy.madeHorizontalWalkProgress(87.5, 119.7, 87.5, 119.7));
    assertTrue(MovementPolicy.madeHorizontalWalkProgress(87.5, 119.7, 87.5, 119.83));

    assertFalse(SurvivalPolicy.shouldPauseBuildWork(4.0, 20.0, 0, true, false));
    assertFalse(SurvivalPolicy.shouldPauseBuildWork(4.0, 20.0, 0, false, true));
    assertFalse(SurvivalPolicy.shouldPauseBuildWork(20.0, 20.0, 6, false, false));
    assertFalse(SurvivalPolicy.shouldPauseBuildWork(20.0, 20.0, 0, false, false));
    assertTrue(SurvivalPolicy.shouldPauseBuildWork(19.0, 20.0, 0, false, false));
    assertTrue(SurvivalPolicy.shouldPauseBuildWork(5.0, 20.0, 18, false, false));
  }
}
