package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class QuestUiPolicyTest {
  @Test
  void questClicksAreWritesAndRespectGuiPermission() {
    assertTrue(Rules.TOOLS.contains("quest_book_state"));
    assertFalse(Rules.WRITE.contains("quest_book_state"));
    assertTrue(Rules.WRITE.contains("quest_book_click"));
    assertFalse(
        PermissionPolicy.denial(
                "quest_book_click",
                new PermissionPolicy.Grants("LIMITED", true, false, false, false))
            .isBlank());
    assertEquals(
        "",
        PermissionPolicy.denial(
            "quest_book_click", new PermissionPolicy.Grants("LIMITED", false, true, false, false)));
  }

  @Test
  void gamerulesAreBoundedToThreeBooleanSettings() {
    for (String rule : new String[] {"keepInventory", "mobGriefing", "doFireTick"}) {
      assertEquals("gamerule " + rule, TestCommandPolicy.validate("/gamerule " + rule));
      assertEquals(rule, TestCommandPolicy.readOnlyGamerule("gamerule " + rule));
      assertEquals(
          "gamerule " + rule + " true", TestCommandPolicy.validate("/gamerule " + rule + " true"));
      assertEquals("", TestCommandPolicy.readOnlyGamerule("gamerule " + rule + " true"));
    }
    for (String command :
        new String[] {
          "gamerule randomTickSpeed 999",
          "gamerule keepInventory TRUE",
          "gamerule keepInventory true\nop @s",
          "gamerule keepInventory true extra",
          "gamerule"
        }) assertThrows(IllegalArgumentException.class, () -> TestCommandPolicy.validate(command));
  }
}
