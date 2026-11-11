package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductionDiagnosisTest {
  @Test
  void reservedMaterialsAreReportedAsReserved() {
    var receipt =
        ProductionDiagnosis.diagnose(
            "",
            Map.of("minecraft:oak_planks", 6),
            Map.of("minecraft:oak_planks", 8),
            Map.of("minecraft:oak_planks", 2),
            Map.of());
    assertEquals(ProductionDiagnosis.RESERVED, receipt.reason());
    assertEquals(1, receipt.missing().size());
    var missing = receipt.missing().get(0);
    assertEquals("minecraft:oak_planks", missing.item());
    assertEquals(6, missing.need());
    assertEquals(8, missing.have());
    assertEquals(6, missing.reserved());
    assertEquals(2, missing.usable());
  }

  @Test
  void nbtOnlyMaterialsAreReportedAsNbtExcluded() {
    var receipt =
        ProductionDiagnosis.diagnose(
            "",
            Map.of("minecraft:iron_ingot", 3),
            Map.of(),
            Map.of(),
            Map.of("minecraft:iron_ingot", 5));
    assertEquals(ProductionDiagnosis.NBT_EXCLUDED, receipt.reason());
    assertEquals(1, receipt.missing().size());
    assertTrue(receipt.detail().contains("带NBT"));
  }

  @Test
  void plainShortageFallsBackToMaterialsMissing() {
    var receipt =
        ProductionDiagnosis.diagnose(
            "",
            Map.of("minecraft:stick", 4),
            Map.of("minecraft:stick", 1),
            Map.of("minecraft:stick", 1),
            Map.of());
    assertEquals(ProductionDiagnosis.MATERIALS_MISSING, receipt.reason());
    assertEquals(4, receipt.missing().get(0).need());
    assertEquals(1, receipt.missing().get(0).have());
    assertEquals(0, receipt.missing().get(0).reserved());
  }

  @Test
  void explicitReasonWins() {
    assertEquals(
        ProductionDiagnosis.RECIPE_TOO_LARGE,
        ProductionDiagnosis.diagnose(
                ProductionDiagnosis.RECIPE_TOO_LARGE, Map.of(), Map.of(), Map.of(), Map.of())
            .reason());
    assertEquals(
        ProductionDiagnosis.NO_RECIPE,
        ProductionDiagnosis.diagnose(
                ProductionDiagnosis.NO_RECIPE,
                Map.of("minecraft:blaze_rod", 1),
                Map.of(),
                Map.of(),
                Map.of())
            .reason());
  }

  @Test
  void jsonCarriesStructuredMissingList() {
    var receipt =
        ProductionDiagnosis.diagnose(
            "",
            Map.of("minecraft:oak_log", 2, "minecraft:coal", 1),
            Map.of("minecraft:oak_log", 1),
            Map.of("minecraft:oak_log", 1),
            Map.of());
    JsonObject json = receipt.json();
    assertEquals(ProductionDiagnosis.MATERIALS_MISSING, json.get("reason").getAsString());
    assertEquals(2, json.getAsJsonArray("missing").size());
    JsonObject log = json.getAsJsonArray("missing").get(0).getAsJsonObject();
    assertEquals("minecraft:coal", log.get("item").getAsString());
    assertEquals(1, log.get("need").getAsInt());
    assertEquals(0, log.get("have").getAsInt());
    assertTrue(json.has("detail"));
  }

  @Test
  void textIsSingleLineWithErrorCode() {
    var text =
        ProductionDiagnosis.diagnose(
                "", Map.of("minecraft:oak_planks", 4), Map.of(), Map.of(), Map.of())
            .text();
    assertTrue(text.startsWith("[CRAFT_MATERIALS]"));
    assertFalse(text.contains("\n"));
    assertTrue(Rules.actionFailed("失败：" + text));
  }

  @Test
  void satisfiedDemandProducesNoMissingEntries() {
    var receipt =
        ProductionDiagnosis.diagnose(
            "",
            Map.of("minecraft:oak_planks", 4),
            Map.of("minecraft:oak_planks", 8),
            Map.of("minecraft:oak_planks", 8),
            Map.of());
    assertTrue(receipt.missing().isEmpty());
    assertEquals(ProductionDiagnosis.MATERIALS_MISSING, receipt.reason());
  }
}
