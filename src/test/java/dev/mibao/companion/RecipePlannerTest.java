package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class RecipePlannerTest {
  private static RecipePlanner.Recipe r(
      String id, String output, int amount, boolean table, String... inputs) {
    return new RecipePlanner.Recipe(
        id, output, amount, Arrays.stream(inputs).map(Set::of).toList(), table);
  }

  @Test
  void craftAllocationSolvesOverlappingTagMultisetInPolynomialTime() {
    // 8 flexible slots accepting {0,1} plus 1 forced slot accepting {0}; legacy order must not starve slot 0.
    var alternatives = new ArrayList<List<Integer>>();
    for (int i = 0; i < 8; i++) alternatives.add(List.of(0, 1));
    alternatives.add(List.of(0));

    var chosen = CraftAllocation.choose(alternatives, Map.of(0, 1, 1, 8));
    assertEquals(9, chosen.size());
    assertEquals(0, chosen.get(8));
    for (int i = 0; i < 8; i++) assertEquals(1, chosen.get(i));

    assertThrows(
        IllegalArgumentException.class,
        () -> CraftAllocation.choose(alternatives, Map.of(0, 0, 1, 9)));
  }

  @Test
  void planDetailedDistinguishesFewestBatchesFromLeastInitialStock() {
    var planner =
        new RecipePlanner(
            List.of(
                r("planks_from_log", "plank", 4, false, "log"),
                r("table", "minecraft:crafting_table", 1, false, "plank", "plank", "plank", "plank")));

    var stock = Map.of("log", 1, "plank", 4);
    var fewestBatches =
        planner.planDetailed(
            "minecraft:crafting_table",
            1,
            stock,
            false,
            RecipePlanner.Objective.FEWEST_BATCHES,
            RecipePlanner.Limits.defaults());
    assertEquals(RecipePlanner.Status.OPTIMAL, fewestBatches.status());
    var batchPlan = fewestBatches.plan().orElseThrow();
    assertEquals(List.of("table"), batchPlan.batches());
    assertEquals(4, batchPlan.totalInitialConsumed());

    var leastInitial =
        planner.planDetailed(
            "minecraft:crafting_table",
            1,
            stock,
            false,
            RecipePlanner.Objective.LEAST_INITIAL_STOCK,
            RecipePlanner.Limits.defaults());
    assertEquals(RecipePlanner.Status.OPTIMAL, leastInitial.status());
    var stockPlan = leastInitial.plan().orElseThrow();
    assertEquals(List.of("planks_from_log", "table"), stockPlan.batches());
    assertEquals(1, stockPlan.totalInitialConsumed());
    assertEquals(1, stockPlan.uses().get("log").fromInitial());
    assertEquals(4, stockPlan.uses().get("plank").fromCrafted());
  }

  @Test
  void planDetailedHandlesSearchLimitDistinctFromImpossible() {
    var planner =
        new RecipePlanner(
            List.of(
                r("a", "b", 1, false, "seed"),
                r("c", "out", 1, true, "b", "b")));
    var impossible =
        planner.planDetailed(
            "out",
            1,
            Map.of("seed", 1),
            false,
            RecipePlanner.Objective.FEWEST_BATCHES,
            RecipePlanner.Limits.defaults());
    assertEquals(RecipePlanner.Status.IMPOSSIBLE, impossible.status());
    assertTrue(impossible.plan().isEmpty());

    var tightLimit =
        planner.planDetailed(
            "b",
            4,
            Map.of("seed", 10),
            false,
            RecipePlanner.Objective.FEWEST_BATCHES,
            new RecipePlanner.Limits(2, 16));
    assertEquals(RecipePlanner.Status.SEARCH_LIMIT, tightLimit.status());
  }

  @Test
  void firstBatchPicksDetailedPinsCompatibleVariantUnderFutureNeed() {
    var planner =
        new RecipePlanner(
            List.of(
                new RecipePlanner.Recipe(
                    "mix", "half", 1, List.of(Set.of("oak", "birch")), false),
                r("strict_oak", "final", 1, false, "oak", "half")));

    var res =
        planner.firstBatchPicksDetailed(
            List.of("mix", "strict_oak"), Map.of("oak", 1, "birch", 1));
    assertEquals(RecipePlanner.PickStatus.FEASIBLE, res.status());
    assertEquals(List.of("birch"), res.picks());

    var unknown = planner.firstBatchPicksDetailed(List.of("missing_id"), Map.of("oak", 1));
    assertEquals(RecipePlanner.PickStatus.UNKNOWN_RECIPE, unknown.status());

    var infeasible = planner.firstBatchPicksDetailed(List.of("mix", "strict_oak"), Map.of("oak", 1));
    assertEquals(RecipePlanner.PickStatus.INFEASIBLE, infeasible.status());
  }

  @Test
  void planDetailedCapsMultiWoodChestAssignmentsAndReportsSearchLimitOnTightBatches() {
    var woods = Set.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "crimson");
    var slots = Collections.nCopies(8, woods);
    var planner =
        new RecipePlanner(
            List.of(
                new RecipePlanner.Recipe("chest", "minecraft:chest", 1, slots, true),
                r("a", "b", 1, false, "seed"),
                r("c", "out", 1, false, "b")));

    var hugeStock = new HashMap<String, Integer>();
    for (String w : woods) hugeStock.put(w, 4096);
    var res =
        planner.planDetailed(
            "minecraft:chest",
            1,
            hugeStock,
            true,
            RecipePlanner.Objective.FEWEST_BATCHES,
            RecipePlanner.Limits.defaults());
    assertEquals(RecipePlanner.Status.OPTIMAL, res.status());
    assertTrue(res.expandedStates() <= 16);

    var limitRes =
        planner.planDetailed(
            "out",
            1,
            Map.of("seed", 1),
            true,
            RecipePlanner.Objective.FEWEST_BATCHES,
            new RecipePlanner.Limits(4096, 1));
    assertEquals(RecipePlanner.Status.SEARCH_LIMIT, limitRes.status());
  }
}
