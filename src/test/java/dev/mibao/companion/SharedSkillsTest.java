package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SharedSkillsTest {
  @Test
  void conflictsArePreservedAndMigrationIsIdempotent() {
    Map<String, String> global = new LinkedHashMap<>(Map.of("搬铁", "fetch32"));
    Map<String, String> incoming = Map.of("搬铁", "fetch64", "采木", "mine16");
    SharedSkills.merge(global, incoming, "worldB", Objects::equals);
    SharedSkills.merge(global, incoming, "worldB", Objects::equals);
    assertEquals(3, global.size());
    assertEquals("fetch32", global.get("搬铁"));
    assertEquals("fetch64", global.get("搬铁 (worldB)"));
    assertEquals("mine16", global.get("采木"));
  }

  @Test
  void equivalentDefinitionsAreSharedWithoutOverwritingExistingEntry() {
    Map<String, String> global = new LinkedHashMap<>(Map.of("搬铁", "fetch32"));
    SharedSkills.merge(global, Map.of("搬铁", "fetch32"), "worldB", Objects::equals);
    assertEquals(Map.of("搬铁", "fetch32"), global);
  }
}
