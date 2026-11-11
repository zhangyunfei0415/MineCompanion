package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class InteractionPolicyTest {
  @Test
  void onlyNonInstantSurvivalDrawerTap() {
    assertTrue(InteractionPolicy.leftTap(true, true, "main", "functionalstorage:oak_1", .02f));
    assertFalse(InteractionPolicy.leftTap(false, true, "main", "functionalstorage:oak_1", .02f));
    assertFalse(InteractionPolicy.leftTap(true, false, "main", "functionalstorage:oak_1", .02f));
  }

  @Test
  void refusesWrongHandOtherBlocksAndInstantBreak() {
    assertFalse(InteractionPolicy.leftTap(true, true, "off", "functionalstorage:oak_1", .02f));
    assertFalse(InteractionPolicy.leftTap(true, true, "main", "minecraft:stone", .02f));
    assertFalse(InteractionPolicy.leftTap(true, true, "main", "functionalstorage:oak_1", 1));
    assertFalse(InteractionPolicy.leftTap(true, true, "main", "functionalstorage:oak_1", 0));
  }
}
