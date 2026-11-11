package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class InventoryToleranceTest {
  @Test
  void outsideSuppliesCanRebaseButLossOrNbtReplacementCannot() {
    assertTrue(InventoryTolerance.onlyGains(Map.of("log#A", 2), Map.of("log#A", 2, "seed", 4)));
    assertFalse(InventoryTolerance.onlyGains(Map.of("log#A", 2), Map.of("log#B", 2)));
    assertFalse(InventoryTolerance.onlyGains(Map.of("log#A", 2), Map.of("log#A", 1)));
  }

  @Test
  void unrelatedPickupDoesNotInvalidateClickReceipt() {
    var expected = Map.of("raw#plain", 37, "iron#plain", 23, "flesh#plain", 1);
    assertTrue(
        InventoryTolerance.confirms(
            expected,
            Map.of("raw#plain", 37, "iron#plain", 23, "flesh#plain", 6),
            Set.of("raw#plain", "iron#plain")));
  }

  @Test
  void sameOutputSupplementDuringTakeIsAmbiguousAndDoesNotCountAsProduction() {
    assertFalse(
        InventoryTolerance.confirms(
            Map.of("iron#plain", 23), Map.of("iron#plain", 24), Set.of("iron#plain")));
  }

  @Test
  void missingItemsAndNbtSubstitutionNeverPass() {
    assertFalse(InventoryTolerance.confirms(Map.of("gun#A", 1), Map.of("gun#B", 1), Set.of()));
    assertFalse(InventoryTolerance.confirms(Map.of("coal", 8), Map.of("coal", 7), Set.of("coal")));
  }

  @Test
  void waitRebaseSeparatesIncomingIronFromNextVerifiedTake() {
    var waiting = Map.of("iron", 15, "coal", 58);
    var supplemented = Map.of("iron", 31, "coal", 58);
    assertEquals(Map.of("iron", 16), InventoryTolerance.delta(waiting, supplemented));
    var expected = ProductionProof.crafted(supplemented, Map.of(), "iron", 8);
    assertTrue(
        InventoryTolerance.confirms(expected, Map.of("iron", 39, "coal", 58), Set.of("iron")));
    assertFalse(InventoryTolerance.confirms(expected, supplemented, Set.of("iron")));
  }

  @Test
  void furnaceConservesBatchAtEveryProgressPoint() {
    for (int left = 0; left <= 8; left++)
      assertTrue(InventoryTolerance.furnaceBalance(8, left, 8 - left, 1));
    assertFalse(InventoryTolerance.furnaceBalance(8, 0, 7, 1));
    assertFalse(InventoryTolerance.furnaceBalance(8, 0, 9, 1));
    assertFalse(InventoryTolerance.furnaceBalance(8, 1, 8, 1));
    assertFalse(InventoryTolerance.furnaceBalance(8, 0, 15, 2));
  }
}
