package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class InventoryLayoutTest {
  @Test
  void allPlayerSlotsMapUniquelyOutsideCrafting() {
    Set<Integer> mapped = new HashSet<>();
    for (int i = 0; i <= 40; i++) {
      int m = InventoryLayout.menuSlot(i);
      assertTrue(m >= 5 && m <= 45);
      assertTrue(mapped.add(m));
    }
    assertEquals(41, mapped.size());
  }

  @Test
  void hotbarMainArmorAndOffhandDoNotConfuseIndices() {
    assertEquals(36, InventoryLayout.menuSlot(0));
    assertEquals(44, InventoryLayout.menuSlot(8));
    assertEquals(9, InventoryLayout.menuSlot(9));
    assertEquals(35, InventoryLayout.menuSlot(35));
    assertEquals(8, InventoryLayout.menuSlot(36));
    assertEquals(5, InventoryLayout.menuSlot(39));
    assertEquals(45, InventoryLayout.menuSlot(40));
    assertEquals("craft_result", InventoryLayout.role(0));
    assertEquals("craft_input", InventoryLayout.role(4));
    assertEquals("offhand", InventoryLayout.role(45));
  }

  @Test
  void invalidEquipmentOrIndicesCannotTargetCraftResult() {
    assertThrows(IllegalArgumentException.class, () -> InventoryLayout.menuSlot(-1));
    assertThrows(IllegalArgumentException.class, () -> InventoryLayout.menuSlot(41));
    assertThrows(IllegalArgumentException.class, () -> InventoryLayout.equipment("output"));
    assertEquals(6, InventoryLayout.equipment("chest"));
  }
}
