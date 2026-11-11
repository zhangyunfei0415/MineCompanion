package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldSkillsTest {
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
