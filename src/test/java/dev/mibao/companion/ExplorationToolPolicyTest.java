package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExplorationToolPolicyTest {
  private static final Set<String> MAP = Set.of(
      "explore_mark", "explore_query", "explore_branch", "explore_return");

  @Test void toolsAreRegisteredAndReadAndWritePathsStayDistinct() {
    assertTrue(Rules.TOOLS.containsAll(MAP));
    assertTrue(SessionPlan.TOOLS.containsAll(MAP));
    assertTrue(names(ToolSchemas.all()).containsAll(MAP));
    for (String tool : MAP) {
      assertEquals("exploration", CapabilityCatalog.family(tool));
      assertEquals(!tool.equals("explore_query"), CapabilityCatalog.mutates(tool));
    }
    assertTrue(HarnessCapabilities.describe(Rules.TOOLS.size()).has("exploration_memory"));
    assertTrue(PermissionPolicy.explorationDenial("maze_strict_detour").isBlank());
    assertFalse(PermissionPolicy.explorationDenial("cave_allow_modify").isBlank());
  }

  @Test void compactMenuAdvertisesToolsForExplorationWithoutExpandingFarming() {
    assertTrue(names(ToolMenu.select(ToolSchemas.all(), "探路再回来", Set.of())).containsAll(MAP));
    assertTrue(names(ToolMenu.select(ToolSchemas.all(), "搬运", Set.of())).containsAll(MAP));
    var farming = names(ToolMenu.select(ToolSchemas.all(), "种植和收割", Set.of()));
    assertTrue(farming.size() <= 36);
    assertTrue(java.util.Collections.disjoint(farming, MAP));
  }

  @Test void worldLocalExplorationStepsStayOutOfPortableBundles() {
    var step = JsonParser.parseString("{\"tool\":\"explore_return\",\"args\":{\"target\":\"10,64,20\"}}");
    assertFalse(BundleSanitizer.scanViolations(step).isEmpty());
  }

  private static Set<String> names(JsonArray values) {
    var names = new HashSet<String>();
    values.forEach(v -> names.add(v.getAsJsonObject().getAsJsonObject("function").get("name").getAsString()));
    return names;
  }
}
