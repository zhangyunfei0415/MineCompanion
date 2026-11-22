package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CompanionDutyTest {
  @Test
  void baseTetherAndFollowRangeBandsClampAndEvaluateCorrectly() {
    var duty = new CompanionDuty();
    duty.mode = "驻守基地";
    duty.baseRadius = 999;
    duty.guardLeash = 2;
    duty.hasHome = true;
    duty.homeDimension = "minecraft:overworld";
    duty.homeX = 100;
    duty.homeY = 64;
    duty.homeZ = -50;
    duty.normalize();

    assertEquals(CompanionDuty.Mode.BASE_STEWARD, duty.modeEnum());
    assertEquals(96, duty.baseRadius);
    assertEquals(8, duty.guardLeash);

    assertTrue(duty.insideBaseTether("minecraft:overworld", 150, 70, -50));
    assertFalse(duty.insideBaseTether("minecraft:overworld", 210, 64, -50));
    assertFalse(duty.insideBaseTether("minecraft:the_nether", 100, 64, -50));

    assertTrue(FollowPolicy.chase(5.0, false, "CLOSE"));
    assertFalse(FollowPolicy.chase(5.0, false, "NORMAL"));
    assertTrue(FollowPolicy.chase(12.0, false, "WIDE"));
  }

  @Test
  void explosionProtectionToggleGovernsCreeperWhileFriendlyAndBossGuardsRemainLocked() {
    var duty = new CompanionDuty();
    duty.avoidTerrainDestroyingMobs = true;
    duty.guardRangedSafe = false;
    assertEquals(
        CompanionDuty.GuardAction.ALERT_AND_AVOID,
        duty.evaluateGuardTarget(
            "minecraft:creeper", false, false, false, false, false, false, false, true));

    duty.guardRangedSafe = true;
    assertEquals(
        CompanionDuty.GuardAction.ENGAGE_RANGED_SAFE,
        duty.evaluateGuardTarget(
            "minecraft:creeper", false, false, false, false, false, false, false, true));
    assertEquals(
        CompanionDuty.GuardAction.ALERT_AND_AVOID,
        duty.evaluateGuardTarget(
            "minecraft:creeper", false, false, false, false, false, false, false, false));

    // User toggled off terrain-mob protection on an explosion-protected server:
    duty.avoidTerrainDestroyingMobs = false;
    assertEquals(
        CompanionDuty.GuardAction.ENGAGE_MELEE,
        duty.evaluateGuardTarget(
            "minecraft:creeper", false, false, false, false, false, false, false, false));

    // Friendly entities, players (without PvP), named mobs (without attackNamed) & bosses stay ignored:
    assertEquals(
        CompanionDuty.GuardAction.IGNORE,
        duty.evaluateGuardTarget(
            "minecraft:zombie", true, false, false, false, false, false, false, false));
    assertEquals(
        CompanionDuty.GuardAction.IGNORE,
        duty.evaluateGuardTarget(
            "minecraft:wolf", false, true, false, false, false, false, false, false));
    assertEquals(
        CompanionDuty.GuardAction.IGNORE,
        duty.evaluateGuardTarget(
            "minecraft:iron_golem", false, false, true, false, false, false, false, false));
    assertEquals(
        CompanionDuty.GuardAction.IGNORE,
        duty.evaluateGuardTarget(
            "minecraft:wither", false, false, false, false, true, false, false, false));
  }

  @Test
  void wildContainerAndLootrSwitchesProtectBaseStorageAndSeparatePerPlayerChests() {
    var duty = new CompanionDuty();
    duty.preferLootrContainers = true;
    duty.scavengeWildContainers = false;

    assertEquals(
        CompanionDuty.ContainerClass.LOOTR_INDEPENDENT,
        duty.classifyContainer("lootr:lootr_chest", true));
    assertEquals(
        CompanionDuty.ContainerClass.BASE_PROTECTED,
        duty.classifyContainer("minecraft:chest", true));
    assertEquals(
        CompanionDuty.ContainerClass.WILD_CONTAINER,
        duty.classifyContainer("minecraft:barrel", false));

    assertFalse(duty.canLootContainer(CompanionDuty.ContainerClass.LOOTR_INDEPENDENT));
    assertFalse(duty.canLootContainer(CompanionDuty.ContainerClass.BASE_PROTECTED));

    duty.scavengeWildContainers = true;
    assertTrue(duty.canLootContainer(CompanionDuty.ContainerClass.LOOTR_INDEPENDENT));
    assertTrue(duty.canLootContainer(CompanionDuty.ContainerClass.WILD_CONTAINER));
    // Registered base chests MUST NEVER be looted even with wild scavenging turned on:
    assertFalse(duty.canLootContainer(CompanionDuty.ContainerClass.BASE_PROTECTED));
  }

  @Test
  void localChatCommandsPermissionsAndToolMenusRemainValidWithinCaps() {
    assertEquals(ChatCommand.DutyAction.BASE_STEWARD, ChatCommand.duty("驻守基地！").action());
    assertEquals(ChatCommand.DutyAction.ESCORT_GUARD, ChatCommand.duty("跟着并保护我").action());
    assertEquals(
        ChatCommand.DutyAction.EXPEDITION_GATHER, ChatCommand.duty("外出探索采集").action());
    assertEquals(ChatCommand.DutyAction.STANDBY, ChatCommand.duty("纯待命").action());
    assertEquals(ChatCommand.DutyAction.PAUSE, ChatCommand.duty("暂停值守").action());

    var duty = new CompanionDuty();
    duty.mode = CompanionDuty.Mode.ESCORT_GUARD.name();
    duty.guardOwner = true;
    var gaps =
        duty.missingPermissions(false, "LIMITED", false, false, false, false, true, false);
    assertFalse(gaps.isEmpty());
    assertTrue(gaps.stream().anyMatch(s -> s.contains("允许清怪")));

    var allTools = ToolSchemas.all();
    assertTrue(Rules.TOOLS.containsAll(Set.of("resync_inventory", "duty_status", "duty_set")));
    assertTrue(Rules.WRITE.containsAll(Set.of("resync_inventory", "duty_set")));
    assertFalse(Rules.WRITE.contains("duty_status"));

    assertEquals("follow", ToolMenu.profile("开启守护模式陪同玩家"));
    Set<String> followNames = new HashSet<>();
    ToolMenu.select(allTools, "开启守护模式陪同玩家", Set.of())
        .forEach(e -> followNames.add(e.getAsJsonObject().getAsJsonObject("function").get("name").getAsString()));
    assertTrue(followNames.containsAll(Set.of("duty_status", "duty_set")));

    // ToolMenuTest hard ceiling: COMMON + farming <= 36
    assertTrue(ToolMenu.select(allTools, "种植和收割", Set.of("jei", "ae2")).size() <= 36);
  }

  @Test
  void baseAutoDepositProtectsHotbarToolsFoodTorchesAndReserves() {
    // Hotbar slot 0..8 never deposited
    assertFalse(
        CompanionDuty.isSafeAutoDepositCandidate(
            0, "minecraft:cobblestone", false, false, false, false, 64, 0));
    // Damageable tools, food, equipment, protected items, torches/arrows never deposited
    assertFalse(
        CompanionDuty.isSafeAutoDepositCandidate(
            9, "minecraft:diamond_pickaxe", true, false, true, false, 1, 0));
    assertFalse(
        CompanionDuty.isSafeAutoDepositCandidate(
            10, "minecraft:cooked_beef", false, true, false, false, 32, 0));
    assertFalse(
        CompanionDuty.isSafeAutoDepositCandidate(
            11, "minecraft:torch", false, false, false, false, 64, 0));
    assertFalse(
        CompanionDuty.isSafeAutoDepositCandidate(
            12, "minecraft:arrow", false, false, false, false, 64, 0));
    assertFalse(
        CompanionDuty.isSafeAutoDepositCandidate(
            13, "minecraft:iron_ingot", false, false, false, true, 64, 0));
    assertFalse(
        CompanionDuty.isSafeAutoDepositCandidate(
            14, "minecraft:cobblestone", false, false, false, false, 32, 32));
    // Main inventory surplus bulk block IS eligible
    assertTrue(
        CompanionDuty.isSafeAutoDepositCandidate(
            15, "minecraft:cobblestone", false, false, false, false, 64, 16));
  }
}
