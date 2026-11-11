package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class GatheringCargoTest {
  @Test
  void deliverOnlyNewResourceVariantsAndProtectExistingGear() {
    var before = Map.of("minecraft:oak_log#plain", 2, "minecraft:stone_axe#d0", 1);
    var after =
        Map.of(
            "minecraft:oak_log#plain",
            6,
            "minecraft:stone_axe#d3",
            1,
            "minecraft:iron_ingot#gift",
            8);
    assertEquals(
        Map.of("minecraft:oak_log#plain", 4),
        GatheringCargo.available(
            before, after, fp -> GatheringCargo.allowed("#minecraft:logs", true, fp)));
  }

  @Test
  void deliveredCargoIsCountedOnceWhenFinalInventoryReturnsToBaseline() {
    var before = Map.of("minecraft:cobblestone#plain", 2);
    var after = Map.of("minecraft:cobblestone#plain", 2);
    assertEquals(
        8,
        DeltaProof.gathered(
            before,
            GatheringCargo.includeDelivered(after, Map.of("minecraft:cobblestone#plain", 8)),
            true));
    assertEquals(Map.of(), GatheringCargo.available(before, after, fp -> true));
  }

  @Test
  void repeatedTripsDoNotDepositPreexistingStock() {
    var before = Map.of("minecraft:oak_log#plain", 2);
    var second = Map.of("minecraft:oak_log#plain", 5);
    assertEquals(
        Map.of("minecraft:oak_log#plain", 3), GatheringCargo.available(before, second, fp -> true));
    assertEquals(
        7,
        DeltaProof.gathered(
            before,
            GatheringCargo.includeDelivered(second, Map.of("minecraft:oak_log#plain", 4)),
            true));
  }

  @Test
  void unknownAndModdedLootCannotBeAutoDeliveredByGuessing() {
    assertEquals("minecraft:raw_iron", GatheringCargo.commonDrop("minecraft:deepslate_iron_ore"));
    assertEquals("minecraft:cobblestone", GatheringCargo.commonDrop("minecraft:stone"));
    assertFalse(GatheringCargo.allowed("example:ore", false, "example:ingot#plain"));
    assertFalse(GatheringCargo.allowed("#minecraft:logs", true, "example:oak_log#plain"));
    assertThrows(
        IllegalArgumentException.class,
        () -> GatheringCargo.includeDelivered(Map.of(), Map.of("log", -1)));
  }
}
