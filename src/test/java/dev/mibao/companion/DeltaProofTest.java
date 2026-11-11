package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DeltaProofTest {
  @Test
  void finalWholeTaskReceiptIncludesPickupsBetweenChildJobBaselines() {
    var wholeStart = Map.of("minecraft:cobblestone#plain", 1);
    var collectionStart = Map.of("minecraft:cobblestone#plain", 2);
    var finalInventory = Map.of("minecraft:cobblestone#plain", 2);
    assertEquals(0, DeltaProof.gained(collectionStart, finalInventory, finalInventory));
    assertEquals(1, DeltaProof.gathered(wholeStart, finalInventory, true));
  }

  @Test
  void finalReceiptRequiresCompleteSyncAndDoesNotCountToolDamage() {
    var before = Map.of("minecraft:stone_axe#damage0", 1, "minecraft:oak_log#plain", 4);
    var after = Map.of("minecraft:stone_axe#damage3", 1, "minecraft:oak_log#plain", 7);
    assertEquals(0, DeltaProof.gathered(before, after, false));
    assertEquals(3, DeltaProof.gathered(before, after, true));
  }

  @Test
  void durabilityOrNbtMutationAloneIsNotANewPickup() {
    assertEquals(
        0,
        DeltaProof.gained(
            Map.of("axe#damage0", 1), Map.of("axe#damage1", 1), Map.of("axe#damage1", 1)));
    assertEquals(
        3,
        DeltaProof.gained(
            Map.of("axe#damage0", 1),
            Map.of("axe#damage1", 1, "log#plain", 3),
            Map.of("axe#damage1", 1, "log#plain", 3)));
  }

  @Test
  void cargoNeverUsesPreexistingOtherNbt() {
    assertEquals(
        Map.of("gun#B", 32),
        DeltaProof.cargo(Map.of("gun#A", 16), Map.of("gun#A", 16, "gun#B", 32), 32));
    assertEquals(Map.of("iron", 32), DeltaProof.cargo(Map.of("iron", 10), Map.of("iron", 42), 32));
  }

  @Test
  void concurrentLossAndUnexpectedGainStopTransport() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DeltaProof.cargo(Map.of("A", 16), Map.of("A", 15, "B", 32), 32));
    assertThrows(
        IllegalArgumentException.class, () -> DeltaProof.cargo(Map.of(), Map.of("B", 33), 32));
  }

  @Test
  void disappearanceOrPredictedIncreaseIsNotPickupProof() {
    assertEquals(0, DeltaProof.gained(Map.of("iron", 4), Map.of("iron", 36), Map.of("iron", 4)));
    assertEquals(0, DeltaProof.gained(Map.of("iron", 4), Map.of("iron", 4), Map.of("iron", 4)));
  }

  @Test
  void confirmsOnlyMatchingNbtVariants() {
    assertEquals(
        32,
        DeltaProof.gained(
            Map.of("gun#A", 1), Map.of("gun#A", 1, "gun#B", 32), Map.of("gun#A", 1, "gun#B", 32)));
    assertEquals(
        0,
        DeltaProof.gained(
            Map.of("gun#A", 1), Map.of("gun#A", 1, "gun#B", 32), Map.of("gun#A", 33)));
  }

  @Test
  void doesNotCountPreexistingItemsOrServerOnlyChanges() {
    assertEquals(0, DeltaProof.gained(Map.of("iron", 64), Map.of("iron", 64), Map.of("iron", 96)));
    assertEquals(2, DeltaProof.gained(Map.of("iron", 64), Map.of("iron", 66), Map.of("iron", 66)));
  }

  @Test
  void plantingRequiresBothBlockReceiptAndExactSeedConsumption() {
    assertTrue(DeltaProof.planted(true, 9, 8, 8));
    assertFalse(DeltaProof.planted(false, 9, 8, 8));
    assertFalse(DeltaProof.planted(true, 9, 8, 9));
    assertFalse(DeltaProof.planted(true, 9, 9, 9));
    assertFalse(DeltaProof.planted(true, 9, 7, 7));
  }
}
