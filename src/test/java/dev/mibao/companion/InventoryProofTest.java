package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class InventoryProofTest {
  record Stack(String id, String nbt, int count) {}

  private final Stack a = new Stack("gun", "variant:A", 1),
      b = new Stack("gun", "variant:B", 1),
      empty = new Stack("air", "", 0);

  @Test
  void newerFullSnapshotMustAgreeWithPredictionAndLiveContents() {
    var expected = List.of(b, a);
    assertTrue(
        InventoryProof.confirms(
            5, 6, expected, expected, expected, empty, empty, empty, Objects::equals));
    assertFalse(
        InventoryProof.confirms(
            5, 5, expected, expected, expected, empty, empty, empty, Objects::equals));
    assertFalse(
        InventoryProof.confirms(
            5, 4, expected, expected, expected, empty, empty, empty, Objects::equals));
  }

  @Test
  void predictedSwapWithoutServerAcceptanceCannotSucceed() {
    assertFalse(
        InventoryProof.confirms(
            5,
            6,
            List.of(b, a),
            List.of(a, b),
            List.of(b, a),
            empty,
            empty,
            empty,
            Objects::equals));
  }

  @Test
  void nbtAndCountsRemainDistinct() {
    var changed = new Stack("gun", "variant:A", 2);
    assertFalse(
        InventoryProof.confirms(
            1, 2, List.of(a), List.of(b), List.of(b), empty, empty, empty, Objects::equals));
    assertFalse(
        InventoryProof.confirms(
            1,
            2,
            List.of(a),
            List.of(changed),
            List.of(changed),
            empty,
            empty,
            empty,
            Objects::equals));
  }

  @Test
  void missingSlotsOrCursorMismatchRefusesConfirmation() {
    assertFalse(
        InventoryProof.confirms(
            1, 2, List.of(a, b), List.of(a), List.of(a), empty, empty, empty, Objects::equals));
    assertFalse(
        InventoryProof.confirms(
            1, 2, List.of(a), List.of(a), List.of(a), empty, a, empty, Objects::equals));
    assertFalse(
        InventoryProof.confirms(
            1, 2, List.of(a), List.of(a), List.of(a), empty, empty, a, Objects::equals));
  }

  @Test
  void concurrentManualOrMachineChangeIsNotHidden() {
    assertFalse(
        InventoryProof.confirms(
            1,
            2,
            List.of(b, a),
            List.of(b, a),
            List.of(b, empty),
            empty,
            empty,
            empty,
            Objects::equals));
  }

  @Test
  void fullStackSwapPreservesVariantsQuantityAndSource() {
    var before = List.of(a, new Stack("iron", "", 32), b);
    var after = InventoryProof.swapped(before, 1, 2);
    assertEquals(List.of(a, b, new Stack("iron", "", 32)), after);
    assertEquals(32, before.get(1).count());
    assertThrows(IllegalArgumentException.class, () -> InventoryProof.swapped(before, -1, 2));
    assertThrows(IllegalArgumentException.class, () -> InventoryProof.swapped(before, 0, 3));
  }
}
