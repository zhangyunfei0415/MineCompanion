package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ProductionTest {
  @Test
  void chestRequiresEnoughWoodForBothWorkbenchAndEightPlanks() {
    var p =
        new RecipePlanner(
            List.of(
                r("planks", "plank", 4, false, "log"),
                r(
                    "table",
                    "minecraft:crafting_table",
                    1,
                    false,
                    "plank",
                    "plank",
                    "plank",
                    "plank"),
                r(
                    "chest", "chest", 1, true, "plank", "plank", "plank", "plank", "plank", "plank",
                    "plank", "plank")));
    assertThrows(IllegalArgumentException.class, () -> p.plan("chest", 1, Map.of("log", 2), false));
    var result = p.plan("chest", 1, Map.of("log", 3), false);
    assertEquals(3, result.consumedStock().get("log"));
    assertTrue(result.needsTable());
    assertEquals(1, result.remaining().get("chest"));
  }

  @Test
  void sourcePlanningSeparatesConsumedInitialStockFromCraftedIntermediates() {
    var p =
        new RecipePlanner(
            List.of(
                r("planks", "plank", 4, false, "log"),
                r(
                    "table",
                    "minecraft:crafting_table",
                    1,
                    false,
                    "plank",
                    "plank",
                    "plank",
                    "plank")));
    var result = p.plan("minecraft:crafting_table", 1, Map.of("log", 1), false);
    assertEquals(Map.of("log", 1), result.consumedStock());
    var existing = p.plan("minecraft:crafting_table", 1, Map.of("log", 1, "plank", 4), false);
    assertEquals(Map.of("plank", 4), existing.consumedStock());
  }

  @Test
  void freshToolsHaveDefaultDamageTagButEnchantmentsAndWornToolsStayExcluded() {
    assertTrue(ProductionProof.safeOutputTag(false, false, 0, Set.of()));
    assertTrue(ProductionProof.safeOutputTag(true, true, 0, Set.of("Damage")));
    assertFalse(ProductionProof.safeOutputTag(true, true, 0, Set.of("Damage", "Enchantments")));
    assertFalse(ProductionProof.safeOutputTag(true, true, 1, Set.of("Damage")));
    assertFalse(ProductionProof.safeOutputTag(true, false, 0, Set.of("Damage")));
  }

  private static RecipePlanner.Recipe r(
      String id, String output, int amount, boolean table, String... inputs) {
    return new RecipePlanner.Recipe(
        id, output, amount, Arrays.stream(inputs).map(Set::of).toList(), table);
  }

  @Test
  void recursivePlanIncludesConsumableWorkbenchBeforeThreeByThreeBatch() {
    var p =
        new RecipePlanner(
            List.of(
                r("planks", "plank", 4, false, "log"),
                r(
                    "table",
                    "minecraft:crafting_table",
                    1,
                    false,
                    "plank",
                    "plank",
                    "plank",
                    "plank"),
                r(
                    "furnace", "furnace", 1, true, "stone", "stone", "stone", "stone", "stone",
                    "stone", "stone", "stone")));
    var plan = p.plan("furnace", 1, Map.of("log", 1, "stone", 8), false);
    assertEquals(List.of("planks", "table", "furnace"), plan.batches());
    assertTrue(plan.needsTable());
    assertEquals(1, plan.remaining().get("furnace"));
    assertEquals(0, plan.remaining().get("minecraft:crafting_table"));
    assertEquals(0, plan.remaining().get("stone"));
  }

  @Test
  void preexistingTableAvoidsUnnecessaryCraftAndExistingOutputDoesNotCountAsNew() {
    var p = new RecipePlanner(List.of(r("iron", "pick", 1, true, "iron", "iron", "iron")));
    var plan = p.plan("pick", 1, Map.of("iron", 3, "pick", 2), true);
    assertEquals(List.of("iron"), plan.batches());
    assertEquals(3, plan.remaining().get("pick"));
    assertFalse(plan.needsTable());
  }

  @Test
  void ingredientAlternativesBacktrackWithoutDoubleSpending() {
    var ingredients = List.of(Set.of("a", "b"), Set.of("a"));
    var p =
        new RecipePlanner(List.of(new RecipePlanner.Recipe("mix", "out", 1, ingredients, false)));
    var result = p.plan("out", 1, Map.of("a", 1, "b", 1), false);
    assertEquals(0, result.remaining().get("a"));
    assertEquals(0, result.remaining().get("b"));
  }

  @Test
  void impossibleOrCyclicRecipesStopWithNoMutation() {
    var stock = new HashMap<>(Map.of("log", 1));
    var p = new RecipePlanner(List.of(r("a", "a", 1, false, "b"), r("b", "b", 1, false, "a")));
    assertThrows(IllegalArgumentException.class, () -> p.plan("a", 1, stock, true));
    assertEquals(Map.of("log", 1), stock);
  }

  @Test
  void rejectsLargeJobsAndBoundsBatchExpansion() {
    var p = new RecipePlanner(List.of(r("many", "out", 1, false, "x")));
    assertThrows(IllegalArgumentException.class, () -> p.plan("out", 65, Map.of("x", 65), true));
    assertThrows(IllegalArgumentException.class, () -> p.plan("out", 0, Map.of(), true));
    assertThrows(IllegalArgumentException.class, () -> p.plan("out", 1, Map.of(), true));
  }

  @Test
  void recipeOutputRoundingKeepsSurplusAndConservesMaterials() {
    var p = new RecipePlanner(List.of(r("planks", "planks", 4, false, "log")));
    var result = p.plan("planks", 5, Map.of("log", 2), false);
    assertEquals(2, result.batches().size());
    assertEquals(8, result.remaining().get("planks"));
    assertEquals(0, result.remaining().get("log"));
  }

  @Test
  void ledgerKeepsTaggedVariantsSeparateAndRequiresFreshServerAgreement() {
    var before = Map.of("iron#plain", 3, "gun#A", 1, "gun#B", 1);
    var expected = ProductionProof.crafted(before, Map.of("iron#plain", 3), "pick#plain", 1);
    assertEquals(Map.of("gun#A", 1, "gun#B", 1, "pick#plain", 1), expected);
    assertTrue(ProductionProof.confirms(8, 9, true, expected, expected, true, true));
    assertFalse(ProductionProof.confirms(8, 8, true, expected, expected, true, true));
    assertFalse(ProductionProof.confirms(8, 9, false, expected, expected, true, true));
    assertFalse(ProductionProof.confirms(8, 9, true, expected, before, true, true));
    assertFalse(ProductionProof.confirms(8, 9, true, expected, expected, false, true));
    assertFalse(ProductionProof.confirms(8, 9, true, expected, expected, true, false));
  }

  @Test
  void impossibleMaterialLedgerAndPredictedPlacementNeverPass() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ProductionProof.crafted(Map.of("x", 1), Map.of("x", 2), "out", 1));
    assertTrue(PlacementProof.accepted(true, true, true, 4, 3, 3));
    assertFalse(PlacementProof.accepted(false, true, true, 4, 3, 3));
    assertFalse(PlacementProof.accepted(true, false, true, 4, 3, 3));
    assertFalse(PlacementProof.accepted(true, true, true, 4, 3, 4));
    assertFalse(PlacementProof.accepted(true, true, true, 4, 2, 2));
  }
}
