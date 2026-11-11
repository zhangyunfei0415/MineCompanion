package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkillStepsTest {
  @org.junit.jupiter.api.Test
  void preparationAndDeliverySourcesNeedCurrentWorldBindings() {
    var steps =
        com.google.gson.JsonParser.parseString(
                "[{\"tool\":\"craft_item\",\"args\":{\"item\":\"minecraft:chest\",\"source\":\"old@1,2,3\"}}]")
            .getAsJsonArray();
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            SkillSteps.prepare(
                steps, false, java.util.Set.of("材料仓库"), new com.google.gson.JsonObject()));
    var bindings = new com.google.gson.JsonObject();
    bindings.addProperty("old@1,2,3", "材料仓库");
    org.junit.jupiter.api.Assertions.assertEquals(
        "材料仓库",
        SkillSteps.prepare(steps, false, java.util.Set.of("材料仓库"), bindings)
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("args")
            .get("source")
            .getAsString());
  }

  @Test
  void transportPresetsCannotCarryForeignMapBindings() {
    var steps =
        JsonParser.parseString(
                "[{\"tool\":\"transport\",\"args\":{\"source\":\"overworld@1,64,2\",\"destination\":\"旧交付箱\",\"item\":\"minecraft:iron_ingot\",\"count\":32}}]")
            .getAsJsonArray();
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillSteps.prepare(steps, false, Set.of("源箱", "目标箱"), new JsonObject()));
    var bindings = new JsonObject();
    bindings.addProperty("overworld@1,64,2", "源箱");
    bindings.addProperty("旧交付箱", "目标箱");
    var a =
        SkillSteps.prepare(steps, false, Set.of("源箱", "目标箱"), bindings)
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("args");
    assertEquals("源箱", a.get("source").getAsString());
    assertEquals("目标箱", a.get("destination").getAsString());
  }

  @Test
  void importedNestedPresetCannotBypassMapRebinding() {
    var steps =
        JsonParser.parseString(
                "[{\"tool\":\"run_preset\",\"args\":{\"name\":\"transport\",\"parameters\":{\"source\":\"old\"}}}]")
            .getAsJsonArray();
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillSteps.prepare(steps, false, Set.of(), new JsonObject()));
  }

  @Test
  void restockSkillRebindsBothLocationsAndDoesNotReplayForeignWorldObjects() {
    var old =
        JsonParser.parseString(
                "[{\"tool\":\"restock\",\"args\":{\"destination\":\"旧交付\",\"source\":\"旧仓库\",\"item\":\"minecraft:iron_ingot\",\"target\":128}}]")
            .getAsJsonArray();
    var bindings = new JsonObject();
    bindings.addProperty("旧交付", "新交付");
    bindings.addProperty("旧仓库", "新仓库");
    var result =
        SkillSteps.prepare(old, false, Set.of("新交付", "新仓库"), bindings)
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("args");
    assertEquals("新交付", result.get("destination").getAsString());
    assertEquals("新仓库", result.get("source").getAsString());
    old.get(0)
        .getAsJsonObject()
        .getAsJsonObject("args")
        .addProperty("destination", "minecraft:overworld@1,64,2");
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillSteps.prepare(old, false, Set.of("旧仓库"), new JsonObject()));
  }

  @Test
  void emptySkillCannotStartAndRemainStuckWithoutAStep() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillSteps.prepare(new JsonArray(), true, Set.of(), new JsonObject()));
  }

  private JsonArray steps(String args) {
    return JsonParser.parseString("[{\"tool\":\"fetch\",\"args\":" + args + "}]").getAsJsonArray();
  }

  @Test
  void namedPlacesResolveOnlyInCurrentWorldAndCanBeRebound() {
    var original = steps("{\"place\":\"旧仓库\",\"item\":\"minecraft:iron_ingot\",\"count\":32}");
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillSteps.prepare(original, false, Set.of("新仓库"), new JsonObject()));
    var bindings = new JsonObject();
    bindings.addProperty("旧仓库", "新仓库");
    var result = SkillSteps.prepare(original, false, Set.of("新仓库"), bindings);
    assertEquals(
        "新仓库", result.get(0).getAsJsonObject().getAsJsonObject("args").get("place").getAsString());
    assertEquals(
        "旧仓库",
        original.get(0).getAsJsonObject().getAsJsonObject("args").get("place").getAsString());
  }

  @Test
  void crossWorldCoordinatesAndObjectsNeedExplicitCurrentWorldBinding() {
    var original = steps("{\"object\":\"overworld@1,64,2\"}");
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillSteps.prepare(original, false, Set.of("仓库"), new JsonObject()));
    var bindings = new JsonObject();
    bindings.addProperty("overworld@1,64,2", "仓库");
    var result =
        SkillSteps.prepare(original, false, Set.of("仓库"), bindings)
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("args");
    assertFalse(result.has("object"));
    assertEquals("仓库", result.get("place").getAsString());
    var coordinates = steps("{\"x\":1,\"y\":64,\"z\":2}");
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillSteps.prepare(coordinates, false, Set.of(), new JsonObject()));
    assertEquals(coordinates, SkillSteps.prepare(coordinates, true, Set.of(), new JsonObject()));
  }

  @Test
  void savedMenuObservationTokensAreNeverReplayable() {
    var clicks =
        JsonParser.parseString("[{\"tool\":\"click_slot\",\"args\":{\"slot\":0,\"view_id\":1}}]")
            .getAsJsonArray();
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillSteps.prepare(clicks, true, Set.of(), new JsonObject()));
  }

  @Test
  void genericObservationAndSameNamedLocalPlacesArePortable() {
    var original =
        JsonParser.parseString(
                "[{\"tool\":\"observe\",\"args\":{}},{\"tool\":\"go_to\",\"args\":{\"place\":\"基地\"}}]")
            .getAsJsonArray();
    assertEquals(original, SkillSteps.prepare(original, false, Set.of("基地"), new JsonObject()));
  }
}
