package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldSkillsTest {
  @Test void survivalPackagesResolveWorldBindingsThroughNormalSessionValidation() throws Exception {
    var bindings = java.util.Map.of(
        "survival-start", List.of("grove", "stone_site", "camp"),
        "survival-mine-return", List.of("mine", "workshop", "source", "delivery"),
        "survival-camp", List.of("origin", "bed", "chest", "lamp"));
    for (var entry : bindings.entrySet()) {
      try (var stream = getClass().getResourceAsStream("/skill-library/" + entry.getKey() + ".json")) {
        assertNotNull(stream, entry.getKey());
        var definition = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        var skill = new SkillPackage(definition);
        var params = new JsonObject();
        entry.getValue().forEach(name -> params.addProperty(name, name));
        var expanded = skill.expand(params);
        var fromWorld = java.util.Set.copyOf(entry.getValue());
        var steps = SkillSteps.prepare(expanded.getAsJsonArray("steps"), false, fromWorld, new JsonObject());
        assertTrue(steps.size() >= 4);
        assertTrue(new SessionPlan(expanded).steps.stream().allMatch(step -> Rules.TOOLS.contains(step.tool())));
      }
    }
  }

  @Test void newWorkstationOutputUsesBothContainerAndGuiPermission() {
    String tool = "take_vanilla_output";
    assertFalse(PermissionPolicy.denial(tool, new PermissionPolicy.Grants("LIMITED", false, true, true, true)).isEmpty());
    assertFalse(PermissionPolicy.denial(tool, new PermissionPolicy.Grants("LIMITED", true, false, true, true)).isEmpty());
    assertTrue(PermissionPolicy.denial(tool, new PermissionPolicy.Grants("LIMITED", true, true, false, false)).isEmpty());
  }

  @Test void bundledSkillsExpandThroughNormalSessionValidation() throws Exception {
    for (String name : List.of("fish", "illuminate")) {
      try (var stream = getClass().getResourceAsStream("/skill-library/" + name + ".json")) {
        assertNotNull(stream);
        var definition = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        var skill = new SkillPackage(definition);
        var expanded = skill.expand(new JsonObject());
        assertEquals(1, expanded.getAsJsonArray("steps").size());
        assertFalse(skill.requires.isEmpty());
        assertTrue(skill.requires.stream().allMatch(Rules.WRITE::contains));
      }
    }
  }
}
