package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class WorkSlotPolicyTest {
  private static WorkSlotPolicy.Slot slot(String id) {
    return new WorkSlotPolicy.Slot(id, false, false);
  }

  @Test
  void fullHotbarKeepsToolsFoodAndBucketButAllowsOrdinaryMaterials() {
    var slots =
        List.of(
            slot("minecraft:cooked_beef"),
            slot("minecraft:coal"),
            slot("minecraft:oak_planks"),
            slot("minecraft:bucket"),
            slot("minecraft:rotten_flesh"),
            slot("minecraft:stick"),
            slot("minecraft:raw_iron"),
            new WorkSlotPolicy.Slot("minecraft:diamond_pickaxe", false, true),
            new WorkSlotPolicy.Slot("minecraft:stone_axe", false, true));
    assertEquals(6, WorkSlotPolicy.choose(slots, 8));
    assertEquals(5, WorkSlotPolicy.choose(slots, 6));
  }

  @Test
  void taggedOrModdedSuppliesAreNeverImplicitVictims() {
    var slots = new ArrayList<>(Collections.nCopies(9, slot("minecraft:diamond_sword")));
    slots.set(8, new WorkSlotPolicy.Slot("minecraft:iron_ingot", false, true));
    slots.set(7, slot("example:oak_planks"));
    slots.set(6, slot("minecraft:cooked_beef"));
    assertEquals(-1, WorkSlotPolicy.choose(slots, 0));
  }

  @Test
  void prefersAnEmptySlotAndDoesNotDisplaceTheSelectedMaterial() {
    var slots = new ArrayList<>(Collections.nCopies(9, slot("minecraft:diamond_sword")));
    slots.set(8, slot("minecraft:cobblestone"));
    assertEquals(-1, WorkSlotPolicy.choose(slots, 8));
    slots.set(2, new WorkSlotPolicy.Slot("minecraft:air", true, false));
    assertEquals(2, WorkSlotPolicy.choose(slots, 8));
  }

  @Test
  void noCandidateIsAControlledFailure() {
    assertEquals(-1, WorkSlotPolicy.choose(List.of(), 0));
    assertEquals(-1, WorkSlotPolicy.choose(Collections.nCopies(9, slot("minecraft:shield")), 0));
  }
}
