package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RulesTest {
  @org.junit.jupiter.api.Test
  void partialDeliveryStopsARecordedSkillInsteadOfAdvancingToTheNextStep() {
    org.junit.jupiter.api.Assertions.assertTrue(Rules.actionFailed("部分完成：已核实 16/32；源库存缺货"));
    org.junit.jupiter.api.Assertions.assertTrue(Rules.actionFailed("未转移：背包已满"));
    org.junit.jupiter.api.Assertions.assertFalse(Rules.actionFailed("成功：已核实 32/32"));
  }

  @org.junit.jupiter.api.Test
  void unknownMachinesAreNotAutomaticallyTreatedAsOrdinaryStorage() {
    org.junit.jupiter.api.Assertions.assertTrue(Rules.standardStorage("minecraft:chest"));
    org.junit.jupiter.api.Assertions.assertTrue(Rules.standardStorage("minecraft:red_shulker_box"));
    org.junit.jupiter.api.Assertions.assertFalse(Rules.standardStorage("mod:chest"));
    org.junit.jupiter.api.Assertions.assertFalse(Rules.standardStorage("mod:virtual_terminal"));
    org.junit.jupiter.api.Assertions.assertFalse(Rules.standardStorage("minecraft:furnace"));
  }

  @Test
  void webCannotTargetLocalServicesOrNonHttps() {
    for (String url :
        new String[] {
          "http://minecraft.wiki/",
          "https://127.0.0.1/",
          "https://[::1]/",
          "https://localhost/",
          "file:///C:/settings.json",
          "https://user:secret@example.com/",
          "https://service.local/",
          "https://10.0.0.1/"
        }) assertFalse(Rules.safePublicUrl(url), url);
    assertTrue(Rules.safePublicUrl("https://minecraft.wiki/w/Crafting"));
    assertTrue(Rules.safePublicUrl("https://github.com/MCMod/README.md"));
  }

  @Test
  void externalControlHasNoArbitraryHostExecutionTools() {
    assertFalse(Rules.TOOLS.contains("shell"));
    assertFalse(Rules.TOOLS.contains("run_command"));
    for (String tool : Rules.WRITE) assertTrue(Rules.TOOLS.contains(tool));
    assertTrue(Rules.WRITE.contains("deposit"));
    assertTrue(Rules.WRITE.contains("click_slot"));
  }

  @Test
  void boundedCountsAndControlCharacters() {
    assertEquals(4096, Rules.bounded(Integer.MAX_VALUE, 1, 4096));
    assertEquals(1, Rules.bounded(-1, 1, 4096));
    assertEquals("hello\nworld", Rules.clean("hello\nworld\u0000", 100));
    assertEquals("abc", Rules.clean("abcdef", 3));
    assertEquals("", Rules.clean(null, 3));
  }
}
