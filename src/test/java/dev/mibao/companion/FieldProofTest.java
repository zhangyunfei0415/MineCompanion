package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldProofTest {
  @Test
  void nbtChangesAndDurabilityCannotMasqueradeAsConsumption() {
    assertFalse(FieldProof.consumed(false, 1, 0, 1, 1));
    assertFalse(FieldProof.consumed(true, 1, 0, 1, 0));
    assertTrue(FieldProof.consumed(false, 16, 15, 20, 19));
  }

  @Test
  void fasterFurnacesAlsoConsumeFuelAtDoubleSpeed() {
    assertEquals(1600, FieldProof.fuelTicks(1600, false));
    assertEquals(800, FieldProof.fuelTicks(1600, true));
    assertEquals(0, FieldProof.fuelTicks(-1, true));
  }

  @Test
  void incompleteAndUnknownSurveysCannotClaimCoverage() {
    assertFalse(FieldProof.covered(false, 0, 0));
    assertFalse(FieldProof.covered(true, 0, 1));
    assertFalse(FieldProof.covered(true, 1, 0));
    assertTrue(FieldProof.covered(true, 0, 0));
  }

  @Test
  void externalSupplyWithoutPickupReceiptDoesNotCreditFishing() {
    assertTrue(FieldProof.caught(Map.of(), Map.of("iron", 64), Map.of()).isEmpty());
  }

  @Test
  void exactVariantsRequireBothPickupAndInventoryIncrease() {
    assertEquals(
        Map.of("cod#plain", 1),
        FieldProof.caught(
            Map.of("cod#plain", 4),
            Map.of("cod#plain", 8, "cod#named", 1),
            Map.of("cod#plain", 1)));
    assertTrue(FieldProof.caught(Map.of("cod", 4), Map.of("cod", 4), Map.of("cod", 1)).isEmpty());
  }
}
