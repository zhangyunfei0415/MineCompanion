package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GameCommandPolicyTest {
  private final CommandProfile profile = new CommandProfile();

  @Test void teleportAcceptsFiniteDecimalsAndKeepsRadiusChecks() {
    var plan = GameCommandPolicy.parse("tp @s -10.5 64.25 ~2.5", profile, 0, 64, 0);
    assertArrayEquals(new double[] {-10.5, 64.25, 2.5}, plan.targetPosition());
    for (String coordinate : new String[] {"NaN", "Infinity", "999.5"})
      assertThrows(IllegalArgumentException.class,
          () -> GameCommandPolicy.parse("tp @s " + coordinate + " 64 0", profile, 0, 64, 0));
  }

  @Test
  void playerPluginCommandsDoNotNeedAnOpGate() {
    assertEquals("home base", GameCommandPolicy.parse("/home base", profile, 0, 64, 0).command());
    assertTrue(GameCommandPolicy.playerCommand("/home base", profile));
    profile.playerCommands += ",land";
    assertEquals("land claim", GameCommandPolicy.parse("land claim", profile, 0, 64, 0).command());
    assertTrue(GameCommandPolicy.playerCommand("land claim", profile));
  }

  @Test
  void administrativeAliasDoesNotReclassifyKnownCommands() {
    profile.playerCommands += ",give,minecraft:fill";
    assertFalse(GameCommandPolicy.playerCommand("give @s stone", profile));
    assertFalse(GameCommandPolicy.playerCommand("minecraft:fill 0 0 0 1 1 1 stone", profile));
  }

  @Test
  void platformFillUsesInclusiveVolumeAndResolvesRelativeCoordinates() {
    var p =
        GameCommandPolicy.parse(
            "fill ~ ~-1 ~ ~11 ~-1 ~8 minecraft:oak_planks", profile, 10, 64, -20);
    assertEquals(108, p.volume());
    assertEquals("minecraft:oak_planks", p.expectedBlock());
    assertArrayEquals(new int[] {10, 63, -20, 21, 63, -12}, p.bounds());
    assertEquals("fill 10 63 -20 21 63 -12 minecraft:oak_planks", p.command());
    profile.maxBlocks = 107;
    assertThrows(
        IllegalArgumentException.class,
        () -> GameCommandPolicy.parse("fill 0 0 0 11 0 8 stone", profile, 0, 0, 0));
  }

  @Test
  void coordinateCommandsRespectRadiusAndRejectIncompleteOrLocalNotation() {
    for (String s :
        new String[] {
          "tp @s ^ ^ ^1", "fill 0 0 0 10", "setblock 999 64 0 stone", "tp AnotherPlayer 0 64 0"
        })
      assertThrows(
          IllegalArgumentException.class, () -> GameCommandPolicy.parse(s, profile, 0, 64, 0), s);
  }

  @Test
  void advancedCloneChecksDestinationExtentNotJustItsOrigin() {
    assertThrows(
        IllegalArgumentException.class,
        () -> GameCommandPolicy.parse("clone 0 64 0 10 64 0 125 64 0", profile, 0, 64, 0));
    profile.advancedCommands = true;
    assertThrows(
        IllegalArgumentException.class,
        () -> GameCommandPolicy.parse("clone 0 64 0 10 64 0 125 64 0", profile, 0, 64, 0));
    assertEquals(
        11, GameCommandPolicy.parse("clone 0 64 0 10 64 0 12 64 0", profile, 0, 64, 0).volume());
    assertEquals(
        "summon",
        GameCommandPolicy.parse("summon minecraft:zombie ~1 ~ ~", profile, 0, 64, 0).root());
  }

  @Test
  void customCommandsRequireOptInAndPreserveQuotedText() {
    String s = "custom:machine configure \"one   two\"";
    assertThrows(
        IllegalArgumentException.class, () -> GameCommandPolicy.parse(s, profile, 0, 64, 0));
    profile.customCommands = true;
    assertEquals(s, GameCommandPolicy.parse(s, profile, 0, 64, 0).command());
    assertFalse(GameCommandPolicy.playerCommand(s, profile));
  }

  @Test
  void singleLineAndBroadSelectorRulesRemainIndependent() {
    for (String s :
        new String[] {
          "home base\nstop", "give @s stone\t64", "give @a stone 1", "give @e[type=pig] stone 1"
        })
      assertThrows(
          IllegalArgumentException.class, () -> GameCommandPolicy.parse(s, profile, 0, 64, 0));
    profile.broadSelectors = true;
    assertEquals(
        "give @a stone 1", GameCommandPolicy.parse("give @a stone 1", profile, 0, 64, 0).command());
  }

  @Test
  void namespaceAndBlockPropertiesArePreservedWithoutInventingMachineMeaning() {
    var p =
        GameCommandPolicy.parse(
            "minecraft:setblock 0 64 0 minecraft:furnace[facing=east]", profile, 0, 64, 0);
    assertEquals("minecraft:furnace", p.expectedBlock());
    assertTrue(p.command().contains("facing=east"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GameCommandPolicy.parse(
                "setblock 0 64 0 minecraft:chest{Items:[]}", profile, 0, 64, 0));
  }
}
