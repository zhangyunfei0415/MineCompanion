package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ToolSchemasTest {
  @Test
  void everyDispatchToolIsDiscoverableAndThereAreNoDuplicates() {
    Set<String> names = new HashSet<>();
    for (var e : ToolSchemas.all()) {
      var fn = e.getAsJsonObject().getAsJsonObject("function");
      assertTrue(names.add(fn.get("name").getAsString()));
    }
    assertEquals(Rules.TOOLS, names);
  }

  @Test
  void collectionAndFarmDescribeDifferentCompletionContracts() {
    Map<String, JsonObject> tools = new HashMap<>();
    for (var e : ToolSchemas.all()) {
      var f = e.getAsJsonObject().getAsJsonObject("function");
      tools.put(f.get("name").getAsString(), f);
    }
    assertTrue(tools.containsKey("transport"));
    assertTrue(tools.containsKey("harvest_replant"));
    assertEquals(
        2,
        tools
            .get("collect_items")
            .getAsJsonObject("parameters")
            .getAsJsonObject("properties")
            .getAsJsonObject("mode")
            .getAsJsonArray("enum")
            .size());
    assertTrue(
        tools
            .get("work_session")
            .getAsJsonObject("parameters")
            .getAsJsonArray("required")
            .asList()
            .stream()
            .anyMatch(x -> x.getAsString().equals("steps")));
  }

  @Test
  void fullInventoryAndIncompleteSamplingNeverProveSweepClear() {
    assertTrue(PickupCapacity.sweepClear(0, false, false));
    assertFalse(PickupCapacity.sweepClear(0, false, true));
    assertFalse(PickupCapacity.sweepClear(1, false, false));
    assertFalse(PickupCapacity.sweepClear(0, true, false));
  }
}
