package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ToolMenuTest {
  @Test
  void fieldSkillsAreDiscoverableInTheirTaskProfiles() {
    assertEquals("construction", ToolMenu.profile("给暗处插火把照明"));
    assertEquals("gathering", ToolMenu.profile("去钓鱼"));
    assertTrue(names(ToolMenu.select(ToolSchemas.all(), "照明", Set.of()))
        .containsAll(Set.of("light_survey", "illuminate_area", "find_blocks")));
    assertTrue(names(ToolMenu.select(ToolSchemas.all(), "钓鱼", Set.of())).contains("fish"));
    assertTrue(Rules.WRITE.containsAll(Set.of("fish", "illuminate_area")));
    assertTrue(ControlPolicy.needsInputPermission("fish"));
    assertFalse(Rules.limitedDenial("illuminate_area", true, false, true).isEmpty());
  }
  private Set<String> names(JsonArray tools) {
    var names = new HashSet<String>();
    tools.forEach(
        e -> names.add(e.getAsJsonObject().getAsJsonObject("function").get("name").getAsString()));
    return names;
  }

  @Test
  void farmingMenuOmitsCombatAndExposesTaskInterfaceAndSwitch() {
    var tools = names(ToolMenu.select(ToolSchemas.all(), "种植和收割", Set.of("jei", "ae2")));
    assertTrue(
        tools.containsAll(
            Set.of(
                "farm_cycle", "task_submit", "task_status", "select_tool_profile", "stow_cursor")));
    assertFalse(tools.contains("hunt_mobs"));
    assertTrue(tools.size() <= 36);
  }

  @Test
  void productionOnlyIncludesOptionalCapabilitiesWhenInstalled() {
    var absent = names(ToolMenu.select(ToolSchemas.all(), "AE合成样板", Set.of()));
    assertFalse(absent.contains("jei_transfer_recipe"));
    assertFalse(absent.contains("ae_pattern_action"));
    var present = names(ToolMenu.select(ToolSchemas.all(), "AE合成样板", Set.of("jei", "ae2")));
    assertTrue(
        present.containsAll(
            Set.of("jei_transfer_recipe", "ae_workflow_state", "ae_pattern_action")));
  }

  @Test
  void requestedProfileAffectsOnlyThatTaskAndNeverAddsUnknownTools() {
    ToolMenu.use("gui", "profile-test-task");
    assertEquals("gui", ToolMenu.profile("profile-test-task"));
    assertEquals("logistics", ToolMenu.profile("搬运铁锭"));
    assertThrows(IllegalArgumentException.class, () -> ToolMenu.use("root", "profile-test-task"));
    ToolMenu.use("auto", "profile-test-task");
  }
}
