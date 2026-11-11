package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.*;

class RecipeBridgeTest {
  @AfterEach
  void reset() {
    RecipeBridge.unavailable();
  }

  @Test
  void coreLoadsWithoutJeiAndReportsUnavailable() {
    RecipeBridge.unavailable();
    assertFalse(RecipeBridge.status().get("ready").getAsBoolean());
    assertThrows(IllegalArgumentException.class, RecipeBridge::require);
  }

  @Test
  void incompatibleOptionalApiDoesNotEscapeAsClientCrash() {
    RecipeBridge.available(
        new RecipeBridge.Provider() {
          public JsonObject status() {
            throw new AbstractMethodError("old JEI");
          }

          public JsonObject items(String q, int o, int l) {
            return status();
          }

          public JsonObject lookup(String i, String r, String c, int o, int l) {
            return status();
          }

          public JsonObject details(String t) {
            return status();
          }

          public String show(String t) {
            throw new NoSuchMethodError();
          }
        });
    assertThrows(IllegalStateException.class, () -> RecipeBridge.require().items("", 0, 5));
    assertThrows(IllegalStateException.class, () -> RecipeBridge.require().show("old"));
  }

  @Test
  void pagingAndHumanRecipeReportPreserveUnknownConditions() {
    assertEquals(20, RecipeBridge.limit(100));
    assertThrows(IllegalArgumentException.class, () -> RecipeBridge.offset(-1));
    var row =
        JsonParser.parseString(
                "{\"title\":\"烹饪\",\"recipe_id\":\"farmersdelight:soup\",\"category\":\"farmersdelight:cooking\",\"slots\":[{\"role\":\"INPUT\",\"alternatives\":[{\"item\":\"minecraft:bowl\",\"count\":1,\"nbt\":\"variant\"}]}]}")
            .getAsJsonObject();
    String report = RecipeText.details(row);
    assertTrue(report.contains("minecraft:bowl"));
    assertTrue(report.contains("variant"));
    assertTrue(report.contains("并非机器槽编号"));
    assertTrue(report.contains("概率"));
  }

  @Test
  void planReportNeverClaimsProductionCompleted() {
    var j =
        JsonParser.parseString(
                "{\"target\":\"mod:part\",\"additional\":2,\"ready\":true,\"batches\":[\"mod:recipe\"]}")
            .getAsJsonObject();
    assertTrue(RecipeText.plan(j).contains("尚未执行"));
    assertTrue(RecipeText.plan(j).contains("mod:recipe"));
  }
}
