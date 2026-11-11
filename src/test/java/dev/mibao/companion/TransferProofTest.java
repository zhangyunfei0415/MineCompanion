package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TransferProofTest {
  @Test
  void exactFetchAndDepositRequireBothInventories() {
    assertTrue(
        TransferProof.verify(
                Map.of("iron", 5),
                Map.of("iron", 64),
                Map.of("iron", 37),
                Map.of("iron", 32),
                Map.of("iron", 32),
                false)
            .verified());
    assertTrue(
        TransferProof.verify(
                Map.of("iron", 64),
                Map.of("iron", 5),
                Map.of("iron", 32),
                Map.of("iron", 37),
                Map.of("iron", 32),
                true)
            .verified());
  }

  @Test
  void clientPredictionAloneIsNotEvidenceOfDelivery() {
    assertFalse(
        TransferProof.verify(
                Map.of(),
                Map.of("iron", 64),
                Map.of("iron", 32),
                Map.of("iron", 64),
                Map.of("iron", 32),
                false)
            .verified());
    assertFalse(
        TransferProof.verify(
                Map.of(),
                Map.of("iron", 64),
                Map.of(),
                Map.of("iron", 64),
                Map.of("iron", 32),
                false)
            .verified());
  }

  @Test
  void sameItemIdWithDifferentNbtCannotCompensateForEachOther() {
    assertFalse(
        TransferProof.verify(
                Map.of(),
                Map.of("gun#A", 2, "gun#B", 2),
                Map.of("gun#B", 1),
                Map.of("gun#A", 1, "gun#B", 2),
                Map.of("gun#A", 1),
                false)
            .verified());
  }

  @Test
  void concurrentContainerChangesAreReportedAsUncertain() {
    assertFalse(
        TransferProof.verify(
                Map.of(),
                Map.of("iron", 64),
                Map.of("iron", 32),
                Map.of("iron", 48),
                Map.of("iron", 32),
                false)
            .verified());
    var partial =
        TransferProof.verify(
            Map.of(), Map.of("iron", 16), Map.of("iron", 16), Map.of(), Map.of("iron", 16), false);
    assertTrue(partial.verified());
    assertEquals(16, partial.moved());
  }

  @Test
  void boundaryCountsRemainConservedInBothDirections() {
    for (boolean deposit : new boolean[] {false, true})
      for (int n : new int[] {1, 16, 32, 63, 64, 65, 4096}) {
        var beforeP = Map.of("item", deposit ? n + 3 : 3);
        var beforeB = Map.of("item", deposit ? 5 : n + 5);
        var afterP = Map.of("item", deposit ? 3 : n + 3);
        var afterB = Map.of("item", deposit ? n + 5 : 5);
        var proof =
            TransferProof.verify(beforeP, beforeB, afterP, afterB, Map.of("item", n), deposit);
        assertTrue(proof.verified());
        assertEquals(n, proof.moved());
      }
  }
}
