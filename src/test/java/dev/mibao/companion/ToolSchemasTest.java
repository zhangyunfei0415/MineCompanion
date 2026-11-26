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
  void miningAdvertisesItsActualGoalAndExecutionChoices() {
    JsonObject mining = null;
    for (var tool : ToolSchemas.all()) {
      var function = tool.getAsJsonObject().getAsJsonObject("function");
      if (function.get("name").getAsString().equals("mine_expedition")) mining = function;
    }
    assertNotNull(mining);
    var properties = mining.getAsJsonObject("parameters").getAsJsonObject("properties");
    assertEquals(3, properties.getAsJsonObject("goal").getAsJsonArray("enum").size());
    assertEquals(3, properties.getAsJsonObject("mode").getAsJsonArray("enum").size());
    assertEquals(2, properties.getAsJsonObject("terrain").getAsJsonArray("enum").size());
    assertFalse(Rules.WRITE.contains("mining_status"));
    assertTrue(SessionPlan.TOOLS.contains("mine_expedition"));
  }

  @Test
  void fullInventoryAndIncompleteSamplingNeverProveSweepClear() {
    assertTrue(PickupCapacity.sweepClear(0, false, false));
    assertFalse(PickupCapacity.sweepClear(0, false, true));
    assertFalse(PickupCapacity.sweepClear(1, false, false));
    assertFalse(PickupCapacity.sweepClear(0, true, false));
  }

  @Test
  void terraformToolsAreRegisteredWithExpectedWriteFlags() {
    assertTrue(Rules.TOOLS.contains("terraform_preview"));
    assertTrue(Rules.TOOLS.contains("terraform_run"));
    assertTrue(Rules.TOOLS.contains("terraform_control"));
    assertFalse(Rules.WRITE.contains("terraform_preview"));
    assertTrue(Rules.WRITE.contains("terraform_run"));
    assertTrue(Rules.WRITE.contains("terraform_control"));
    assertTrue(SessionPlan.TOOLS.contains("terraform_run"));
  }
}
