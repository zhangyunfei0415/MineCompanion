package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class InventoryQueryTest {
  @Test
  void parsesExactIdsAndFingerprintSuffixes() {
    assertEquals(new InventoryQuery.Query("minecraft:stone", ""), InventoryQuery.parse("stone"));
    assertEquals(
        new InventoryQuery.Query("mekanism:crusher", ""),
        InventoryQuery.parse(" mekanism:crusher "));
    assertEquals(
        new InventoryQuery.Query("minecraft:stone", "sha256:abc"),
        InventoryQuery.parse("stone#sha256:abc"));
    assertEquals(new InventoryQuery.Query("", "sha256:abc"), InventoryQuery.parse("sha256:abc"));
    assertNull(InventoryQuery.parse(""));
    assertNull(InventoryQuery.parse(null));
  }

  @Test
  void matchesExactIdAndFingerprintOnly() {
    var idOnly = InventoryQuery.parse("stone");
    assertTrue(InventoryQuery.matches(idOnly, "minecraft:stone", "minecraft:stone#sha256:abc"));
    assertFalse(
        InventoryQuery.matches(
            idOnly, "minecraft:cobblestone", "minecraft:cobblestone#sha256:abc"));
    var fingerprint = InventoryQuery.parse("stone#sha256:abc");
    assertTrue(
        InventoryQuery.matches(fingerprint, "minecraft:stone", "minecraft:stone#sha256:abc"));
    assertFalse(
        InventoryQuery.matches(fingerprint, "minecraft:stone", "minecraft:stone#sha256:xyz"));
    assertFalse(InventoryQuery.matches(null, "minecraft:stone", "minecraft:stone#sha256:abc"));
  }

  @Test
  void resultUsesMinCountAndReportsTheExactRule() {
    var query = InventoryQuery.parse("stone");
    var ok = InventoryQuery.result(query, 5, 3);
    assertTrue(ok.get("has_item").getAsBoolean());
    assertEquals(5, ok.get("matched_count").getAsInt());
    assertEquals(3, ok.get("min_count").getAsInt());
    assertFalse(InventoryQuery.result(query, 2, 3).get("has_item").getAsBoolean());
    assertTrue(ok.get("match_rule").getAsString().contains("精确匹配"));
  }
}
