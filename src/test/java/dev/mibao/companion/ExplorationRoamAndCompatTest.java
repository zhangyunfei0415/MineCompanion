package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ExplorationRoamAndCompatTest {
  @Test
  void roamPolicyEnforcesHeadingCommitmentNegativeCacheAndZeroStepFailure() {
    long now = 1_000_000L;
    int[] visits = {10, 8, 2, 5, 9, 11, 4, 6};
    long[] blockedUntil = new long[8];

    // Commitment window (< 20s and < 16m displaced) keeps current heading instead of oscillating.
    assertEquals(
        0,
        ExplorationRoamPolicy.pickHeading(
            0, visits, blockedUntil, now, true, now - 10_000L, 4.0));

    // After 20s window expires, switches to least-visited unblocked heading (index 2, visits=2).
    assertEquals(
        2,
        ExplorationRoamPolicy.pickHeading(
            0, visits, blockedUntil, now, true, now - 25_000L, 4.0));

    // After 16m displacement, re-evaluates to least-visited heading cleanly.
    assertEquals(
        2,
        ExplorationRoamPolicy.pickHeading(
            0, visits, blockedUntil, now, true, now - 8_000L, 18.0));

    // 5-minute negative cooldown excludes blocked headings; skips index 2 -> picks index 6 (visits=4).
    blockedUntil[2] = now + ExplorationRoamPolicy.NEGATIVE_CACHE_MS;
    assertEquals(
        6,
        ExplorationRoamPolicy.pickHeading(
            0, visits, blockedUntil, now, false, 0L, 0.0));

    // All headings on cooldown returns -1 cleanly.
    Arrays.fill(blockedUntil, now + 60_000L);
    assertEquals(
        -1,
        ExplorationRoamPolicy.pickHeading(
            0, visits, blockedUntil, now, false, 0L, 0.0));

    // Anti-0-step-fake-success grading: 0 steps / 0 new cells / <1.5 displacement -> madeProgress() == false!
    var zeroProgress = new ExplorationRoamPolicy.Progress(0, 0.4, 0, 0, 12_000L, 12_000L);
    assertFalse(zeroProgress.madeProgress());
    assertEquals(0.0, zeroProgress.chunksPerMinute());

    var activeProgress = new ExplorationRoamPolicy.Progress(48, 34.5, 42, 4, 30_000L, 1_000L);
    assertTrue(activeProgress.madeProgress());
    assertEquals(8.0, activeProgress.chunksPerMinute(), 0.01);

    // Guide player home pacing checks
    assertEquals(
        ExplorationRoamPolicy.GuideAction.LEAD,
        ExplorationRoamPolicy.guideAction(true, 4.2, true, 0));
    assertEquals(
        ExplorationRoamPolicy.GuideAction.WAIT_FACE_PLAYER,
        ExplorationRoamPolicy.guideAction(true, 8.5, true, 10));
    assertEquals(
        ExplorationRoamPolicy.GuideAction.WAIT_FACE_PLAYER,
        ExplorationRoamPolicy.guideAction(true, 6.0, false, 20));
    assertEquals(
        ExplorationRoamPolicy.GuideAction.REGROUP_STEP_BACK,
        ExplorationRoamPolicy.guideAction(true, 18.0, true, 0));
    assertEquals(
        ExplorationRoamPolicy.GuideAction.REGROUP_STEP_BACK,
        ExplorationRoamPolicy.guideAction(true, 10.5, true, 180));
    assertEquals(
        ExplorationRoamPolicy.GuideAction.TARGET_LOST,
        ExplorationRoamPolicy.guideAction(false, 99.0, false, 0));
  }

  @Test
  void roamGuidedHeadingAndCorridorScoringAvoidUturnsAndFalseStuck() {
    // Normal 4..5 tick stepping must NOT be counted into stuck_ms until grace window passes.
    assertFalse(ExplorationRoamPolicy.isStuckTick(5));
    assertFalse(ExplorationRoamPolicy.isStuckTick(ExplorationRoamPolicy.STUCK_GRACE_TICKS));
    assertTrue(ExplorationRoamPolicy.isStuckTick(ExplorationRoamPolicy.STUCK_GRACE_TICKS + 1));

    // When explicit prefer_heading=east(2) hits a wall (cooldown), guided pick detours +/-45° or 90°
    // instead of flipping 180° west(6) even if west has fewer sector visits.
    long now = 2_000_000L;
    int[] visits = {4, 3, 2, 3, 4, 8, 1, 8};
    long[] blockedUntil = new long[8];
    blockedUntil[2] = now + 45_000L;
    int detour =
        ExplorationRoamPolicy.pickHeadingGuided(2, 2, visits, blockedUntil, now, false, 0L, 0.0);
    assertTrue(detour == 1 || detour == 3, "Expected NE/SE side turn around east wall, got " + detour);
    assertNotEquals(6, detour);

    // East unvisited frontier (16m east) strictly outscores closer west frontier (-8m west) when heading east(2).
    double eastFrontier = ExplorationRoamPolicy.scoreFrontier(16, 0, 2, 2, 0, false, false);
    double westFrontier = ExplorationRoamPolicy.scoreFrontier(-8, 0, 2, 2, 0, false, false);
    assertTrue(eastFrontier < westFrontier);

    // Unvisited corridor cell going east/side outscores already-visited corridor cell behind (west).
    double unvisitedSide =
        ExplorationRoamPolicy.scoreCorridorCell(4, 6, 8, 3, 2, 2, 0, false, false);
    double visitedBack =
        ExplorationRoamPolicy.scoreCorridorCell(-10, 0, 10, 3, 2, 2, 2, true, true);
    assertTrue(unvisitedSide > visitedBack);
  }

  @Test
  void graphRevalidateHealsStaleAndBlockedEdgesAndSummarizesChunks() {
    var g = new ExplorationGraph();
    var c0 = new ExplorationGraph.Cell(0, 64, 0);
    var c1 = new ExplorationGraph.Cell(1, 64, 0);
    var c2 = new ExplorationGraph.Cell(2, 64, 0);
    g.mark(c0, ExplorationGraph.Kind.ENTRY, "spawn", List.of(), 100L);
    g.walk(c0, c1, false, 100L);
    g.walk(c1, c2, false, 100L);

    var routeBefore = g.route(c2, Set.of(c0));
    assertEquals(2, routeBefore.size());

    // Mark link c1->c0 as STALE (simulating temporary block placement).
    var step = routeBefore.stream().filter(s -> s.from().equals(c1) && s.to().equals(c0)).findFirst().orElseThrow();
    g.update(step, ExplorationGraph.State.STALE, "temp_block", 200L);
    assertTrue(g.route(c2, Set.of(c0)).isEmpty());

    // Once terrain clears, revalidateEdges restores VERIFIED and route becomes walkable again.
    int healed = g.revalidateEdges(e -> true, 300L);
    assertEquals(1, healed);
    assertEquals(2, g.route(c2, Set.of(c0)).size());
    assertEquals(c0, g.oldestWalkedCell());
    assertTrue(g.chunkVisitCounts().getOrDefault(ExplorationRoamPolicy.chunkKey(0, 0), 0) >= 3);
  }

  @Test
  void genericChestMenuAndPortableBackpackPoliciesEnforceSafetyBoundaries() {
    // Refurbished Furniture drawer / standard 27-slot ChestMenu -> allowed for deposit/fetch!
    assertTrue(
        StorageMenuPolicy.isSafeGenericStorageMenu(
            "net.minecraft.world.inventory.ChestMenu", true, 27, 36, false, false));
    assertTrue(
        StorageMenuPolicy.isSafeGenericStorageMenu(
            "com.mrcrayfish.furniture.refurbished.inventory.PostBoxOrStorageMenu",
            true,
            54,
            36,
            false,
            false));

    // Reject menus with result slots, widgets, machine keywords, or non-standard player slots.
    assertFalse(
        StorageMenuPolicy.isSafeGenericStorageMenu(
            "net.minecraft.world.inventory.ChestMenu", true, 27, 36, true, false));
    assertFalse(
        StorageMenuPolicy.isSafeGenericStorageMenu(
            "com.mekanism.common.inventory.container.MachineContainer", false, 27, 36, false, false));
    assertFalse(
        StorageMenuPolicy.isSafeGenericStorageMenu(
            "com.mod.CustomStorageScreenMenu", false, 27, 36, false, true));

    // Sophisticated Backpacks: sneak-to-place & breakable without empty check (self-retaining NBT) when confirmed!
    assertTrue(BreakPolicy.isSelfRetainingPortableContainer("sophisticatedbackpacks:backpack"));
    assertTrue(BreakPolicy.isSelfRetainingPortableContainer("sophisticatedbackpacks:netherite_backpack"));
    assertTrue(BreakPolicy.requiresSneakToPlace("sophisticatedbackpacks:iron_backpack"));
    assertEquals(
        "",
        BreakPolicy.denial("sophisticatedbackpacks:backpack", true, true, true, false));
    assertTrue(
        BreakPolicy.denial("sophisticatedbackpacks:backpack", true, true, false, false)
            .contains("CONFIRM_REQUIRED"));

    // Verified-empty generic storage block is allowed; unknown AE2 machine stays strictly blocked.
    assertEquals(
        "",
        BreakPolicy.denial("refurbished_furniture:oak_drawer", true, true, true, true, true));
    assertTrue(
        BreakPolicy.denial("ae2:drive", true, true, true, true, false)
            .contains("UNSUPPORTED_MACHINE"));
  }

  @Test
  void chatBufferWaystoneDetectorAndReasonCodeRefinementsBehaveCorrectly() {
    ChatBuffer.clear();
    ChatBuffer.record("world-a", "player", "Hydrogen", "uuid-1", "帮我开图找路石", 1000L);
    ChatBuffer.record("world-a", "player", "Hydrogen", "uuid-1", "/login secret123", 1001L);
    ChatBuffer.record("world-a", "system", "", "", "Joined the game", 1002L);
    var arr = ChatBuffer.recentJson("world-a", 10);
    assertEquals(2, arr.size());
    assertEquals("Hydrogen", arr.get(0).getAsJsonObject().get("sender").getAsString());
    assertEquals(0, ChatBuffer.recentJson("world-b", 10).size());

    // Waystones block matcher
    assertTrue(CompanionDuty.isWaystoneBlock("waystones:waystone"));
    assertTrue(CompanionDuty.isWaystoneBlock("waystones:mossy_waystone"));
    assertTrue(CompanionDuty.isWaystoneBlock("waystones:black_sharestone"));
    assertFalse(CompanionDuty.isWaystoneBlock("minecraft:stone"));

    // Guide home chat command
    var guide = ChatCommand.duty("带我回家");
    assertNotNull(guide);
    assertEquals(ChatCommand.DutyAction.GUIDE_HOME, guide.action());
    var guideTo = ChatCommand.duty("带我去 地堡入口");
    assertNotNull(guideTo);
    assertEquals(ChatCommand.DutyAction.GUIDE_HOME, guideTo.action());
    assertEquals("地堡入口", guideTo.argument());

    // ReasonCodes: normal slot message with mouse/item words must NOT misclassify as CURSOR_OCCUPIED
    var quickNoEffect =
        ReasonCodes.describe(
            "部分完成：[QUICK_MOVE_NO_EFFECT] 鼠标操作未引起物品变动，请改用 deposit");
    assertEquals("QUICK_MOVE_NO_EFFECT", quickNoEffect.get("reason_code").getAsString());
    var heldCursor =
        ReasonCodes.describe("失败：鼠标持有物品，请先 stow_cursor 收好再 click_slot");
    assertEquals("CURSOR_OCCUPIED", heldCursor.get("reason_code").getAsString());
  }
}
