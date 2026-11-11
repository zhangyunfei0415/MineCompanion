package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

class WorldMemoryTest {
  @Test
  void sameIdReplacementAndDoubleChestLayoutInvalidateInventory() {
    var m = new WorldMemory();
    chest(m, "overworld", 0, 1);
    String key = WorldMemory.key("overworld", 0, 64, 0);
    m.inventory(key, new WorldMemory.Inventory());
    m.observe("overworld", 0, 64, 0, "minecraft:air", "air", "空气", "", 2);
    chest(m, "overworld", 0, 3);
    assertNull(m.get(key).inventory);
    assertFalse(m.get(key).invalidReason.isBlank());
    m.inventory(key, new WorldMemory.Inventory());
    assertEquals("", m.get(key).invalidReason);
    m.observe(
        "overworld",
        0,
        64,
        0,
        "minecraft:chest",
        "chest[facing=north,type=left]",
        "箱子",
        "storage",
        4);
    assertNull(m.get(key).inventory);
    assertEquals("storage_layout_changed", m.get(key).invalidReason);
  }

  @Test
  void reopeningReplacesHistoricalQuantitiesInsteadOfAccumulatingThem() {
    var m = new WorldMemory();
    chest(m, "overworld", 0, 1);
    String key = WorldMemory.key("overworld", 0, 64, 0);
    var first = new WorldMemory.Inventory();
    var iron = new WorldMemory.StackInfo();
    iron.id = "minecraft:iron_ingot";
    iron.count = 64;
    first.items.add(iron);
    first.observed = 100;
    m.inventory(key, first);
    var reopened = new WorldMemory.Inventory();
    reopened.observed = 200;
    m.inventory(key, reopened);
    var loaded = new Gson().fromJson(new Gson().toJson(m), WorldMemory.class);
    loaded.normalize();
    assertEquals(200, loaded.get(key).inventory.observed);
    assertTrue(loaded.get(key).inventory.items.isEmpty());
    m.observe("overworld", 0, 64, 0, "minecraft:air", "air", "空气", "", 3);
    assertThrows(IllegalArgumentException.class, () -> m.inventory(key, first));
  }

  private static void chest(WorldMemory m, String dim, int x, long time) {
    m.observe(dim, x, 64, 0, "minecraft:chest", "chest[facing=north]", "箱子", "storage", time);
  }

  @Test
  void dimensionsAndDistanceAreExplicit() {
    var m = new WorldMemory();
    chest(m, "overworld", 10, 1);
    chest(m, "overworld", 2, 1);
    chest(m, "nether", 1, 1);
    var near = m.query("overworld", 0, 64, 0, "", "storage", 16, 5);
    assertEquals(1, near.size());
    assertEquals(2, near.get(0).x);
    assertEquals(2, m.query("overworld", 0, 64, 0, "", "", 16, 0).size());
  }

  @Test
  void blockChangesInvalidateInventoryAndAbsenceNeedsAnObservation() {
    var m = new WorldMemory();
    chest(m, "overworld", 0, 1);
    String key = WorldMemory.key("overworld", 0, 64, 0);
    m.get(key).inventory = new WorldMemory.Inventory();
    chest(m, "overworld", 0, 2);
    assertNotNull(m.get(key).inventory);
    assertEquals(2, m.get(key).lastSeen);
    m.observe("overworld", 0, 64, 0, "minecraft:barrel", "barrel", "木桶", "storage", 3);
    assertNull(m.get(key).inventory);
    m.observe("overworld", 0, 64, 0, "minecraft:air", "air", "空气", "", 4);
    assertFalse(m.get(key).present);
    assertTrue(m.query("overworld", 0, 64, 0, "", "", 16, 0).isEmpty());
    chest(m, "overworld", 0, 5);
    assertTrue(m.get(key).present);
    assertNull(m.get(key).inventory);
  }

  @Test
  void inventorySearchAndEvidenceSurviveJsonReload() {
    var m = new WorldMemory();
    chest(m, "overworld", 0, 100);
    var p = m.get(WorldMemory.key("overworld", 0, 64, 0));
    p.label = "材料仓库";
    p.inventory = new WorldMemory.Inventory();
    p.inventory.observed = 200;
    var item = new WorldMemory.StackInfo();
    item.id = "minecraft:iron_ingot";
    item.name = "铁锭";
    item.count = 32;
    item.fingerprint = "iron#gun-variant-A";
    p.inventory.items.add(item);
    var gson = new Gson();
    var loaded = gson.fromJson(gson.toJson(m), WorldMemory.class);
    loaded.normalize();
    assertEquals(1, loaded.query("overworld", 0, 64, 0, "铁锭", "", 16, 0).size());
    var found = loaded.query("overworld", 0, 64, 0, "材料仓库", "", 16, 0).get(0);
    assertEquals(200, found.inventory.observed);
    assertEquals("iron#gun-variant-A", found.inventory.items.get(0).fingerprint);
  }

  @Test
  void finiteCacheEvictsLeastRecentlySeenObjects() {
    var m = new WorldMemory();
    for (int i = 0; i < WorldMemory.MAX_OBJECTS; i++) chest(m, "overworld", i, i + 1);
    chest(m, "overworld", 0, 5000);
    chest(m, "overworld", WorldMemory.MAX_OBJECTS, 5001);
    assertEquals(WorldMemory.MAX_OBJECTS, m.objects.size());
    assertNotNull(m.get(WorldMemory.key("overworld", 0, 64, 0)));
    assertNull(m.get(WorldMemory.key("overworld", 1, 64, 0)));
  }

  @Test
  void openingABarrelDoesNotEraseItsHistoricalContents() {
    var m = new WorldMemory();
    m.observe("overworld", 0, 64, 0, "minecraft:barrel", "barrel[open=false]", "木桶", "storage", 1);
    String key = WorldMemory.key("overworld", 0, 64, 0);
    m.get(key).inventory = new WorldMemory.Inventory();
    m.observe("overworld", 0, 64, 0, "minecraft:barrel", "barrel[open=true]", "木桶", "storage", 2);
    assertNotNull(m.get(key).inventory);
  }

  @Test
  void denseResourcesDoNotImmediatelyEvictANamedWarehouse() {
    var m = new WorldMemory();
    chest(m, "overworld", 0, 1);
    String key = WorldMemory.key("overworld", 0, 64, 0);
    m.get(key).label = "主仓库";
    for (int i = 1; i <= WorldMemory.MAX_OBJECTS + 10; i++)
      m.observe("overworld", i, 64, 0, "minecraft:oak_log", "log", "原木", "wood", i + 1);
    assertNotNull(m.get(key));
    assertEquals(WorldMemory.MAX_OBJECTS, m.objects.size());
  }
}
