package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class SkillCatalogTest {
  @Test
  void hugeOrFractionalCountsCannotTruncateIntoValidWork() {
    var a = new JsonObject();
    a.addProperty("count", 1.5);
    assertThrows(IllegalArgumentException.class, () -> SkillCatalog.bounded(a, "count", 32, 1, 64));
    a.add("count", JsonParser.parseString("4294967328"));
    assertThrows(IllegalArgumentException.class, () -> SkillCatalog.bounded(a, "count", 32, 1, 64));
  }

  @Test
  void everyPresetHasCompleteContractAndPermissionGatedExecutionTool() {
    assertEquals(12, SkillCatalog.ALL.size());
    for (var p : SkillCatalog.ALL) {
      assertFalse(p.parameters().isBlank());
      assertFalse(p.prerequisites().isBlank());
      assertFalse(p.steps().isBlank());
      assertFalse(p.verification().isBlank());
      assertFalse(p.recovery().isBlank());
      assertTrue(Rules.TOOLS.contains(p.tool()));
      assertEquals(p, SkillCatalog.get(p.id()));
    }
    assertTrue(Rules.WRITE.contains("harvest_replant"));
    assertTrue(Rules.WRITE.contains("collect_items"));
    assertTrue(Rules.WRITE.contains("mine_resource"));
    assertTrue(Rules.WRITE.contains("hunt_mobs"));
  }

  @Test
  void rejectsOutOfRangeRatherThanSilentlyIncreasingWork() {
    JsonObject a = new JsonObject();
    assertEquals(32, SkillCatalog.bounded(a, "count", 32, 1, 64));
    a.addProperty("count", 65);
    assertThrows(IllegalArgumentException.class, () -> SkillCatalog.bounded(a, "count", 32, 1, 64));
    a.addProperty("count", 0);
    assertThrows(IllegalArgumentException.class, () -> SkillCatalog.bounded(a, "count", 32, 1, 64));
    assertThrows(IllegalArgumentException.class, () -> SkillCatalog.required(a, "source"));
  }

  @Test
  void portableCatalogHasNoConcreteWorldOrEndpoint() {
    String s = SkillCatalog.catalog().toString();
    assertFalse(s.contains("apiKey"));
    assertFalse(s.contains("originScope"));
    assertFalse(s.contains("\"x\":"));
  }
}
