package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class BasePickupAndAutonomyTest {
  @Test
  void dualClientChatGuardBlocksPassiveOwnerWindowButAllowsAiController() {
    // Passive human owner window running with same config/owner MUST NOT reply to public @ mentions.
    assertFalse(ClientRolePolicy.shouldProcessPublicChat("PuuPuuBearX", "PuuPuuBearX", false, false));
    // Passive window without control lock ignores public @ mentions even if not matching owner string.
    assertFalse(ClientRolePolicy.shouldProcessPublicChat("AltPlayer", "PuuPuuBearX", false, false));
    // Active AI controller window processes authorized chat normally.
    assertTrue(ClientRolePolicy.shouldProcessPublicChat("Jull092", "PuuPuuBearX", true, true));
    assertTrue(ClientRolePolicy.isOwnerCompanionWindow(false, false, true));
    assertFalse(ClientRolePolicy.isOwnerCompanionWindow(true, true, true));
    // Passive owner window must never claim exploration shard write lock before AI window joins.
    assertFalse(ClientRolePolicy.shouldClaimExplorationWriteLock(false, false));
    assertTrue(ClientRolePolicy.shouldClaimExplorationWriteLock(true, false));
    assertTrue(ClientRolePolicy.shouldClaimExplorationWriteLock(false, true));
    assertTrue(ClientRolePolicy.shouldClaimExplorationWriteLock(true, true));
  }

  @Test
  void autonomousDutyTransitionsRespectLevelsDebounceAndHumanOverrideGrace() {
    long now = 500_000L;
    // Manual lock prevents all automatic transitions.
    assertNull(
        DutyAutonomyPolicy.recommendTransition(
            DutyAutonomyPolicy.Level.MANUAL_LOCK,
            CompanionDuty.Mode.EXPEDITION_GATHER,
            true,
            true,
            false,
            -1,
            false,
            32,
            2,
            3,
            true,
            now,
            0L,
            0L,
            20_000L));

    // Human manual override grace window (now < manualOverrideUntil) blocks auto-override.
    assertNull(
        DutyAutonomyPolicy.recommendTransition(
            DutyAutonomyPolicy.Level.AUTO_FULL,
            CompanionDuty.Mode.ESCORT_GUARD,
            true,
            true,
            false,
            -1,
            false,
            32,
            10,
            3,
            false,
            now,
            0L,
            now + 60_000L,
            30_000L));

    // Cooldown debounce (< 20s since lastAutoSwitchAt) blocks rapid oscillation.
    assertNull(
        DutyAutonomyPolicy.recommendTransition(
            DutyAutonomyPolicy.Level.AUTO_FULL,
            CompanionDuty.Mode.EXPEDITION_GATHER,
            true,
            true,
            true,
            10.0,
            false,
            32,
            2,
            3,
            true,
            now,
            now - 10_000L,
            0L,
            0L));

    // Expedition return with home transitions to BASE_STEWARD.
    var expReturn =
        DutyAutonomyPolicy.recommendTransition(
            DutyAutonomyPolicy.Level.AUTO_SAFE,
            CompanionDuty.Mode.EXPEDITION_GATHER,
            true,
            true,
            false,
            -1,
            false,
            32,
            2,
            3,
            true,
            now,
            0L,
            0L,
            0L);
    assertNotNull(expReturn);
    assertEquals(CompanionDuty.Mode.BASE_STEWARD, expReturn.targetMode());
    assertTrue(expReturn.armed());

    // Owner offline >= 15s during escort transitions safely to BASE_STEWARD.
    var offlineReturn =
        DutyAutonomyPolicy.recommendTransition(
            DutyAutonomyPolicy.Level.AUTO_SAFE,
            CompanionDuty.Mode.ESCORT_GUARD,
            true,
            true,
            false,
            -1,
            false,
            32,
            12,
            3,
            false,
            now,
            0L,
            0L,
            16_000L);
    assertNotNull(offlineReturn);
    assertEquals(CompanionDuty.Mode.BASE_STEWARD, offlineReturn.targetMode());

    // Owner actively leaving base triggers ESCORT_GUARD only under AUTO_FULL.
    assertNull(
        DutyAutonomyPolicy.recommendTransition(
            DutyAutonomyPolicy.Level.AUTO_SAFE,
            CompanionDuty.Mode.BASE_STEWARD,
            true,
            true,
            true,
            20.0,
            true,
            32,
            12,
            3,
            false,
            now,
            0L,
            0L,
            0L));
    var depart =
        DutyAutonomyPolicy.recommendTransition(
            DutyAutonomyPolicy.Level.AUTO_FULL,
            CompanionDuty.Mode.BASE_STEWARD,
            true,
            true,
            true,
            20.0,
            true,
            32,
            12,
            3,
            false,
            now,
            0L,
            0L,
            0L);
    assertNotNull(depart);
    assertEquals(CompanionDuty.Mode.ESCORT_GUARD, depart.targetMode());
  }

  @Test
  void generatorFluidPolicyPermitsCobbleGenWhileBlockingOverheadAndBroadCaves() {
    // Non-targeted bulk mining is NEVER exempt from neighbor fluid checks.
    assertFalse(GeneratorPolicy.allowFluidNeighbor("minecraft:cobblestone", false, true, false, true, true));
    // Fluid/fire directly overhead or below is NEVER safe.
    assertFalse(GeneratorPolicy.allowFluidNeighbor("minecraft:cobblestone", true, true, true, true, true));
    // Standing in liquid/fire is NEVER safe.
    assertFalse(GeneratorPolicy.allowFluidNeighbor("minecraft:cobblestone", true, true, false, true, false));
    // Targeted cobblestone / stone / basalt in horizontal water+lava trench is safe!
    assertTrue(GeneratorPolicy.allowFluidNeighbor("minecraft:cobblestone", true, false, false, true, true));
    assertTrue(GeneratorPolicy.allowFluidNeighbor("minecraft:stone", true, false, false, true, true));
    assertTrue(GeneratorPolicy.allowFluidNeighbor("minecraft:basalt", true, false, false, true, true));
    // Non-generator block requires explicit allow_fluid_neighbor=true.
    assertFalse(GeneratorPolicy.allowFluidNeighbor("minecraft:iron_ore", true, false, false, true, true));
    assertTrue(GeneratorPolicy.allowFluidNeighbor("minecraft:iron_ore", true, true, false, true, true));
  }

  @Test
  void dropCooldownBlacklistTightPickupAndToolWiringEnforceInvariants() {
    Map<UUID, Long> blocked = new HashMap<>();
    UUID dropId = UUID.randomUUID();
    blocked.put(dropId, 10_000L);
    assertTrue(CompanionDuty.isDropCooldownActive(blocked, dropId, 5_000L));
    assertFalse(CompanionDuty.isDropCooldownActive(blocked, dropId, 10_001L));
    assertFalse(blocked.containsKey(dropId));

    // Preferred close pickup requires tighter reach than outer edge touches.
    var item = new PickupGeometry.Box(10.35, 64.0, 10.35, 10.60, 64.25, 10.60);
    assertTrue(PickupGeometry.touches(11.75, 64.0, 10.5, 0.6, 1.8, item));
    assertFalse(PickupGeometry.preferredCloseTouches(11.75, 64.0, 10.5, 0.6, 1.8, item));
    assertTrue(PickupGeometry.preferredCloseTouches(10.95, 64.0, 10.5, 0.6, 1.8, item));

    // Verify new tools registered cleanly without breaking ToolMenu farming cap (<= 36).
    var all = ToolSchemas.all();
    assertTrue(Rules.TOOLS.containsAll(Set.of("observe_owner_focus", "handoff_to_owner", "auto_action")));
    assertTrue(Rules.WRITE.containsAll(Set.of("handoff_to_owner", "auto_action")));
    assertFalse(Rules.WRITE.contains("observe_owner_focus"));
    assertTrue(ToolMenu.select(all, "种植和收割", Set.of("jei", "ae2")).size() <= 36);

    // Verify StatusFeed classifies auto-action, pickup, handoff, and auto-eat into proper avatar states.
    assertEquals(
        StatusFeed.Kind.MINING, StatusFeed.classify(true, false, false, true, "刷石机连挖 12"));
    assertEquals(
        StatusFeed.Kind.COMBAT,
        StatusFeed.classify(true, false, false, true, "刷怪塔连点 · 已挥击 5 次"));
    assertEquals(
        StatusFeed.Kind.STORAGE,
        StatusFeed.classify(true, false, false, true, "收集掉落物 · 已核实 3/8"));
    assertEquals(
        StatusFeed.Kind.STORAGE,
        StatusFeed.classify(true, false, false, true, "递交物品给 PuuPuuBearX · cobblestone"));
    assertEquals(
        StatusFeed.Kind.EATING,
        StatusFeed.classify(true, false, false, true, "手摇挂机中 · 自动进食恢复饱食"));
  }
}
