package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExecutorRegressionTest {
  @Test
  void changedWorldCacheIsInvalidatedWithoutRestartingEveryPacket() {
    var freshness = new ScanFreshness();
    assertTrue(freshness.refresh(-1, false, false));
    freshness.begin();
    freshness.complete();
    assertFalse(freshness.dirty());
    freshness.changed();
    assertTrue(freshness.dirty());
    assertFalse(freshness.refresh(500, false, false));
    assertTrue(freshness.refresh(750, false, false));
    freshness.begin();
    freshness.changed();
    freshness.complete();
    assertTrue(freshness.dirty());
    freshness.begin();
    freshness.complete();
    assertFalse(freshness.dirty());
    assertTrue(freshness.refresh(0, true, false));
    assertTrue(freshness.refresh(2500, false, false));
  }

  @Test
  void airborneCrossingAndTwoBlockHeightMismatchAreNotArrival() {
    var gate = new ArrivalGate();
    for (int i = 0; i < 20; i++) assertFalse(gate.step(true, false, -.3));
    assertFalse(ArrivalGate.near(80.46, 85.249, 53.52, 81.5, 86, 54.5, false));
    assertFalse(ArrivalGate.near(81.5, 84, 54.5, 81.5, 86, 54.5, false));
    assertTrue(ArrivalGate.near(81.5, 85.5, 54.5, 81.5, 86, 54.5, false));
  }

  @Test
  void groundedArrivalNeedsConsecutiveStableFramesAndResets() {
    var gate = new ArrivalGate();
    assertFalse(gate.step(true, true, -.0784));
    assertFalse(gate.step(true, true, -.0784));
    assertFalse(gate.step(false, true, 0));
    assertFalse(gate.step(true, true, 0));
    assertFalse(gate.step(true, true, 0));
    assertTrue(gate.step(true, true, 0));
  }

  @Test
  void exactAndNearbyGoalsHaveDifferentContracts() {
    assertTrue(ArrivalGate.near(1.5, 64, 0, 0, 64, 0, false));
    assertFalse(ArrivalGate.near(1.5, 64, 0, 0, 64, 0, true));
    assertTrue(ArrivalGate.near(.2, 64.1, 0, 0, 64, 0, true));
    assertTrue(ArrivalGate.transition(64.5, 65.5));
    assertFalse(ArrivalGate.transition(64.5, 66));
    assertFalse(ArrivalGate.transition(66, 64));
  }

  @Test
  void pickupHasVerticalRoomFromTwoBlocksBelowAndUsesSlabSurface() {
    var item = new PickupGeometry.Box(87.75, 86, 59.2, 88, 86.25, 59.45);
    assertTrue(PickupGeometry.touches(87.5, 84, 59.5, .6, 1.8, item));
    var slab = new GridSearch.Cell(88, 86, 59);
    assertEquals(
        Set.of(slab), PickupGeometry.stances(item, .6, 1.8, p -> p.y() - .5, slab::equals));
    assertTrue(PickupGeometry.stances(item, .6, 1.8, p -> Double.NaN, p -> true).isEmpty());
  }

  @Test
  void fullInventoryCanMergeOnlyTheMatchingVariant() {
    var slots =
        List.of(
            new PickupCapacity.Slot("gun#A", 1, 1), new PickupCapacity.Slot("iron#plain", 60, 64));
    assertEquals(4, PickupCapacity.room(slots, "iron#plain", 64));
    assertEquals(0, PickupCapacity.room(slots, "iron#tagged", 64));
    assertEquals(0, PickupCapacity.room(slots, "gun#B", 1));
  }

  @Test
  void anEmptySlotAcceptsAnUnstackableItemWithoutCountingEquipment() {
    assertEquals(
        1, PickupCapacity.room(List.of(new PickupCapacity.Slot("", 0, 64)), "tool#damage", 1));
    assertEquals(
        0, PickupCapacity.room(List.of(new PickupCapacity.Slot("iron", 65, 64)), "iron", 64));
  }

  @Test
  void pausedAndMenuTransactionsProtectInputAndDefenseDoesNotApprovePendingTools() {
    assertEquals(
        ExecutionPolicy.Lane.PAUSED,
        ExecutionPolicy.choose(true, false, true, false, true, false, true, true));
    assertEquals(
        ExecutionPolicy.Lane.DEFENSE,
        ExecutionPolicy.choose(true, false, false, true, true, false, true, true));
    assertEquals(
        ExecutionPolicy.Lane.APPROVAL,
        ExecutionPolicy.choose(true, false, false, true, false, false, false, true));
    assertEquals(
        ExecutionPolicy.Lane.MENU_TRANSACTION,
        ExecutionPolicy.choose(true, false, false, false, true, true, true, true));
    assertEquals(
        ExecutionPolicy.Lane.DEFENSE,
        ExecutionPolicy.choose(true, false, false, false, true, false, true, true));
    assertEquals(
        ExecutionPolicy.Lane.LOCAL_ACTION,
        ExecutionPolicy.choose(true, false, false, false, false, false, true, true));
    assertEquals(
        ExecutionPolicy.Lane.REST,
        ExecutionPolicy.choose(false, false, false, false, true, false, true, true));
  }

  @Test
  void statusDoesNotEmbedInventoryJsonButToolPayloadStaysUnchanged() {
    String raw =
        "{\"connected\":true,\"screen\":\"箱子\",\"inventory\":[{\"id\":\"minecraft:iron_ingot\",\"count\":32}]}";
    assertFalse(ActionSummary.describe(raw).contains("inventory"));
    assertTrue(ActionSummary.describe(raw).contains("箱子"));
    assertTrue(raw.contains("32"));
    assertTrue(ActionSummary.describe("x".repeat(10000)).length() <= 220);
    assertFalse(ActionSummary.describe("{broken").contains("broken"));
  }

  @Test
  void worldSummaryAndProductionStepAreHumanReadable() {
    assertTrue(ActionSummary.describe("{\"objects\":[{},{}]}").contains("2"));
    assertEquals("从指定来源补齐材料", ActionSummary.step("fetch"));
    assertTrue(ActionSummary.describe("失败：材料不足\n请提供木材").startsWith("失败"));
  }

  @Test
  void experienceEnvironmentDependsOnVersionsNotOrderOrWorld() {
    String a = EnvironmentKey.of(List.of("minecraft@1.20.1", "forge@47.4.28"));
    assertEquals(a, EnvironmentKey.of(List.of("forge@47.4.28", "minecraft@1.20.1")));
    assertNotEquals(a, EnvironmentKey.of(List.of("minecraft@1.20.1", "forge@47.4.29")));
    assertEquals(64, a.length());
  }
}
