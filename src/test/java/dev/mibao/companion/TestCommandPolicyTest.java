package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TestCommandPolicyTest {
  @Test
  void acceptsPreparationWithOptionalSlash() {
    assertEquals("gamemode creative @s", TestCommandPolicy.validate("/gamemode creative @s"));
    assertEquals(
        "give @s mekanism:crusher 1", TestCommandPolicy.validate("give @s mekanism:crusher 1"));
  }

  @Test
  void rejectsMultipleLinesAndControlCharacters() {
    for (String s : new String[] {"give @s stone\nstop", "weather clear\rstop", "time\tset day"})
      assertThrows(IllegalArgumentException.class, () -> TestCommandPolicy.validate(s));
  }

  @Test
  void excludesDestructiveAndIndirectRoots() {
    for (String s :
        new String[] {
          "stop",
          "execute run give @s stone",
          "function test:prepare",
          "fill 0 0 0 16 16 16 air",
          "//give @s stone"
        }) assertThrows(IllegalArgumentException.class, () -> TestCommandPolicy.validate(s));
  }

  @Test
  void rejectsBlankAndOversizedCommands() {
    assertThrows(IllegalArgumentException.class, () -> TestCommandPolicy.validate(""));
    assertThrows(
        IllegalArgumentException.class,
        () -> TestCommandPolicy.validate("give " + "x".repeat(256)));
  }

  @Test
  void allowsReadOnlyQueriesForTheThreeBooleanGamerules() {
    for (String name : new String[] {"keepInventory", "mobGriefing", "doFireTick"}) {
      String command = "gamerule " + name;
      assertEquals(command, TestCommandPolicy.validate(command));
      assertEquals(name, TestCommandPolicy.readOnlyGamerule(command));
      assertEquals(name, TestCommandPolicy.readOnlyGamerule("/" + command));
      assertEquals("", TestCommandPolicy.readOnlyGamerule(command + " true"));
    }
    assertEquals("", TestCommandPolicy.readOnlyGamerule("gamerule randomTickSpeed"));
    assertEquals("", TestCommandPolicy.readOnlyGamerule("give @s stone"));
    assertEquals("", TestCommandPolicy.readOnlyGamerule(null));
  }

  @Test
  void stillLimitsGameruleWritesToTheThreeBooleanSettings() {
    assertEquals(
        "gamerule keepInventory true", TestCommandPolicy.validate("gamerule keepInventory true"));
    assertThrows(
        IllegalArgumentException.class,
        () -> TestCommandPolicy.validate("gamerule keepInventory 5"));
    assertThrows(
        IllegalArgumentException.class,
        () -> TestCommandPolicy.validate("gamerule randomTickSpeed"));
    assertThrows(
        IllegalArgumentException.class,
        () -> TestCommandPolicy.validate("gamerule randomTickSpeed 3"));
  }
}
