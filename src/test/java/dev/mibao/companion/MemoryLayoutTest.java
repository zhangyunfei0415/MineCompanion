package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class MemoryLayoutTest {
  @Test
  void persistentWorldFileExcludesSharedSkillsAndDoesNotMutateBackup() {
    var combined =
        JsonParser.parseString(
                "{\"skills\":{\"搬铁\":{}},\"world\":{\"objects\":{}},\"places\":{\"基地\":{}},\"skillChecks\":{\"搬铁\":{\"successes\":1}}}")
            .getAsJsonObject();
    var world = MemoryLayout.world(combined, "worldA");
    assertFalse(world.has("skills"));
    assertTrue(world.has("skillChecks"));
    assertEquals("worldA", world.get("scope").getAsString());
    assertTrue(combined.has("skills"));
    assertTrue(combined.has("places"));
    var library = MemoryLayout.library(combined.get("skills"));
    assertTrue(library.has("skills"));
    assertFalse(library.has("world"));
    assertFalse(library.has("places"));
    assertFalse(library.has("skillChecks"));
    library.getAsJsonObject("skills").remove("搬铁");
    assertTrue(combined.getAsJsonObject("skills").has("搬铁"));
  }

  @Test
  void worldEvidenceRemainsIndependentAcrossSerializationRoundTrip() {
    var data =
        JsonParser.parseString(
                "{\"skills\":{},\"places\":{\"仓库\":{\"x\":1}},\"world\":{\"objects\":{\"overworld@1,64,0\":{\"inventory\":{\"observed\":200}}}}}")
            .getAsJsonObject();
    var a = MemoryLayout.world(data, "A");
    var b = MemoryLayout.world(new JsonObject(), "B");
    var reloaded = JsonParser.parseString(new Gson().toJson(a)).getAsJsonObject();
    assertTrue(reloaded.has("world"));
    assertFalse(b.has("world"));
    assertNotEquals(reloaded.get("scope"), b.get("scope"));
  }
}
