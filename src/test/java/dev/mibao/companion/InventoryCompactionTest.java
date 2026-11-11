package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class InventoryCompactionTest {
  private List<InventoryCompaction.Stack> bag() {
    return new ArrayList<>(Collections.nCopies(36, new InventoryCompaction.Stack("empty", 0, 64)));
  }

  private void put(List<InventoryCompaction.Stack> b, int slot, String fp, int n, int max) {
    b.set(slot, new InventoryCompaction.Stack(fp, n, max));
  }

  @Test
  void mergeCreatesSpaceWithoutMovingHotbar() {
    var b = bag();
    put(b, 0, "iron", 1, 64);
    put(b, 9, "iron", 40, 64);
    put(b, 35, "iron", 24, 64);
    var m = InventoryCompaction.next(b);
    assertEquals(new InventoryCompaction.Merge(35, 9, 24), m);
  }

  @Test
  void overflowIsReturnedToItsSource() {
    var b = bag();
    put(b, 9, "iron", 60, 64);
    put(b, 35, "iron", 20, 64);
    assertEquals(4, InventoryCompaction.next(b).moved());
  }

  @Test
  void differentNbtNeverMerges() {
    var b = bag();
    put(b, 9, "gun#A", 1, 64);
    put(b, 35, "gun#B", 1, 64);
    assertNull(InventoryCompaction.next(b));
  }

  @Test
  void identicalNbtMayStack() {
    var b = bag();
    put(b, 9, "mod:material#same", 5, 16);
    put(b, 35, "mod:material#same", 8, 16);
    assertEquals(8, InventoryCompaction.next(b).moved());
  }

  @Test
  void toolsAndFullStacksStayInPlace() {
    var b = bag();
    put(b, 9, "sword#A", 1, 1);
    put(b, 35, "sword#A", 1, 1);
    assertNull(InventoryCompaction.next(b));
    put(b, 9, "iron", 64, 64);
    put(b, 35, "iron", 12, 64);
    assertNull(InventoryCompaction.next(b));
  }

  @Test
  void noCrossHotbarMerge() {
    var b = bag();
    put(b, 0, "iron", 1, 64);
    put(b, 35, "iron", 24, 64);
    assertNull(InventoryCompaction.next(b));
  }

  @Test
  void repeatedPlansConserveAllItemsAndTerminate() {
    var b = bag();
    for (int i = 9; i < 36; i++) put(b, i, i % 2 == 0 ? "iron#A" : "iron#B", i, 64);
    int before = b.stream().mapToInt(InventoryCompaction.Stack::count).sum(), steps = 0;
    InventoryCompaction.Merge m;
    while ((m = InventoryCompaction.next(b)) != null) {
      assertTrue(++steps < 128);
      var a = b.get(m.from());
      var z = b.get(m.to());
      put(b, m.from(), a.fingerprint(), a.count() - m.moved(), a.maximum());
      put(b, m.to(), z.fingerprint(), z.count() + m.moved(), z.maximum());
    }
    assertEquals(before, b.stream().mapToInt(InventoryCompaction.Stack::count).sum());
  }
}
