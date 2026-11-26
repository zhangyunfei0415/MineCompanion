package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class InteractionPolicyTest {
  @Test
  void allowsFunctionalStorageAndStorageDrawersLeftTapSafely() {
    assertTrue(
        InteractionPolicy.leftTap(
            true, true, "main", "functionalstorage:oak_1", 0.2f));
    assertTrue(
        InteractionPolicy.leftTap(
            true, true, "main", "storagedrawers:compacting_drawers_2", 0.2f));
    assertTrue(
        InteractionPolicy.leftTap(
            true, true, "main", "storagedrawers:oak_full_drawers_4", 0.1f));
    assertFalse(
        InteractionPolicy.leftTap(
            true, false, "main", "storagedrawers:oak_full_drawers_1", 0.2f));
    assertFalse(
        InteractionPolicy.leftTap(
            true, true, "off", "storagedrawers:oak_full_drawers_1", 0.2f));
    assertFalse(
        InteractionPolicy.leftTap(
            false, true, "main", "storagedrawers:oak_full_drawers_1", 0.2f));
    assertFalse(
        InteractionPolicy.leftTap(
            true, true, "main", "minecraft:chest", 0.2f));
  }

  @Test
  void respectsCustomLeftTapWhitelistEntries() {
    var extra = List.of("frameddrawers:", "ae2:sky_stone_chest");
    assertTrue(
        InteractionPolicy.leftTap(
            true, true, "main", "frameddrawers:half_4", 0.2f, extra));
    assertTrue(
        InteractionPolicy.leftTap(
            true, true, "main", "ae2:sky_stone_chest", 0.2f, extra));
    assertFalse(
        InteractionPolicy.leftTap(
            true, true, "main", "minecraft:stone", 0.2f, extra));
  }
}
