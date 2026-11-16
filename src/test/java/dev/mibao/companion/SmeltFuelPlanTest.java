package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SmeltFuelPlanTest {
  @Test
  void batchBalanceDoesNotCountOldStockAsRecovered() {
    assertEquals(new SmeltBalance.Evidence(1, 0), SmeltBalance.check(3, 100, 98, 2, 1));
    assertEquals(new SmeltBalance.Evidence(3, 0), SmeltBalance.check(3, 3, 3, 0, 1));
    assertEquals(new SmeltBalance.Evidence(0, 3), SmeltBalance.check(3, 3, 0, 0, 1));
    assertEquals(new SmeltBalance.Evidence(0, 0), SmeltBalance.check(3, 3, 0, 3, 1));
  }

  @Test
  void furnaceAndInventoryFuelAreCombined() {
    var p =
        SmeltFuelPlan.choose(
            600,
            "minecraft:oak_planks",
            12,
            Map.of("minecraft:oak_planks", 16),
            Map.of("minecraft:oak_planks", 300),
            "minecraft:raw_iron",
            3);
    assertEquals(2, p.required());
    assertEquals(0, p.toAdd());
    assertEquals(0, p.missing());
    assertEquals(28, p.inFurnace() + p.inInventory());
  }

  @Test
  void existingFuelDoesNotRequireAnyBackpackFuel() {
    var p =
        SmeltFuelPlan.choose(
            600,
            "minecraft:oak_planks",
            12,
            Map.of(),
            Map.of("minecraft:oak_planks", 300),
            "minecraft:raw_iron",
            3);
    assertEquals(0, p.missing());
    assertEquals(0, p.toAdd());
  }

  @Test
  void inputReservedForCookingCannotAlsoBeFuel() {
    var p =
        SmeltFuelPlan.choose(
            600,
            "",
            0,
            Map.of("minecraft:oak_log", 3),
            Map.of("minecraft:oak_log", 300),
            "minecraft:oak_log",
            3);
    assertEquals(0, p.inInventory());
    assertEquals(2, p.missing());
  }

  @Test
  void shortageIsExactAndSelectionIsDeterministic() {
    var p =
        SmeltFuelPlan.choose(
            600,
            "",
            0,
            Map.of("minecraft:oak_planks", 1),
            Map.of("minecraft:oak_planks", 300),
            "minecraft:raw_iron",
            3);
    assertEquals(1, p.missing());
    assertEquals(2, p.required());
    assertEquals(2, p.toAdd());
  }
}
