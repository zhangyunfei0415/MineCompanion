package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class PortableLibraryTest {
  JsonObject read(String name) throws Exception {
    try (var in = getClass().getResourceAsStream("/skill-library/" + name + ".json")) {
      assertNotNull(in);
      return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  JsonObject parameters(SkillPackage p) {
    var o = new JsonObject();
    for (var e : p.definition.getAsJsonObject("parameters").entrySet()) {
      var s = e.getValue().getAsJsonObject();
      o.add(
          e.getKey(),
          s.has("default")
              ? s.get("default").deepCopy()
              : new JsonPrimitive(e.getKey().equals("source") ? "材料仓库" : "交付箱"));
    }
    return o;
  }

  @Test
  void allFiveShippedPackagesExpandAndBindWithoutCoordinates() throws Exception {
    for (String name : List.of("transport", "craft", "smelt", "farm", "gather")) {
      var p = new SkillPackage(read(name));
      var session = p.expand(parameters(p));
      assertFalse(new SessionPlan(session).steps.isEmpty());
      var steps =
          SkillSteps.prepare(
              session.getAsJsonArray("steps"), false, Set.of("材料仓库", "交付箱"), new JsonObject());
      assertEquals(session.get("steps"), steps);
      assertFalse(steps.toString().contains("${"));
    }
  }

  @Test
  void typedValuesAreNotStringInterpolatedOrMutated() throws Exception {
    var p = new SkillPackage(read("transport"));
    var params = parameters(p);
    params.addProperty("count", 32);
    var before = p.definition.deepCopy();
    var s = p.expand(params);
    assertTrue(
        s.getAsJsonArray("steps")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("args")
            .get("count")
            .getAsJsonPrimitive()
            .isNumber());
    assertEquals(before, p.definition);
  }

  @Test
  void rejectsMissingUnknownFractionalAndOversizedParameters() throws Exception {
    var p = new SkillPackage(read("transport"));
    assertThrows(IllegalArgumentException.class, () -> p.expand(new JsonObject()));
    var params = parameters(p);
    params.addProperty("unexpected", "value");
    assertThrows(IllegalArgumentException.class, () -> p.expand(params));
    params.remove("unexpected");
    params.addProperty("count", 3.5);
    assertThrows(IllegalArgumentException.class, () -> p.expand(params));
    params.addProperty("count", 65);
    assertThrows(IllegalArgumentException.class, () -> p.expand(params));
    params.addProperty("count", "32");
    assertThrows(IllegalArgumentException.class, () -> p.expand(params));
  }

  @Test
  void foreignCoordinatesAndOldMenuViewsAreRejectedBeforeExecution() throws Exception {
    for (String key : List.of("x", "object", "gui_view")) {
      var o = read("transport");
      o.getAsJsonArray("steps")
          .get(0)
          .getAsJsonObject()
          .getAsJsonObject("args")
          .addProperty(key, 1);
      assertThrows(IllegalArgumentException.class, () -> new SkillPackage(o));
    }
  }

  @Test
  void preventsNestedSkillsUnknownSchemaAndUnsupportedActions() throws Exception {
    var o = read("transport");
    o.addProperty("schema", 2);
    assertThrows(IllegalArgumentException.class, () -> new SkillPackage(o));
    o.addProperty("schema", 1);
    o.getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("tool", "game_command");
    assertThrows(IllegalArgumentException.class, () -> new SkillPackage(o));
  }

  @Test
  void newWorldMustActuallyContainTheBoundPlace() throws Exception {
    var p = new SkillPackage(read("transport"));
    var s = p.expand(parameters(p));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SkillSteps.prepare(s.getAsJsonArray("steps"), false, Set.of("另一仓库"), new JsonObject()));
  }
}
