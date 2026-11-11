package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ControlPolicyTest {
  @Test
  void holdsHaveStrictFiniteBounds() {
    assertEquals(1, ControlPolicy.duration(1));
    assertEquals(200, ControlPolicy.duration(200));
    for (int n : new int[] {0, -1, 201, Integer.MAX_VALUE})
      assertThrows(IllegalArgumentException.class, () -> ControlPolicy.duration(n));
  }

  @Test
  void keyApprovalUsesExactRegistrationNamesAndCannotPressCompanionControls() {
    assertTrue(ControlPolicy.approved("mod.map", "mod.map,mod.menu"));
    assertFalse(ControlPolicy.approved("mod.ma", "mod.map"));
    assertFalse(ControlPolicy.approved("mod.map", ""));
    assertFalse(
        ControlPolicy.approved("key.logistics_companion.stop", "key.logistics_companion.stop"));
    assertFalse(ControlPolicy.approved("mod.map", null));
  }

  @Test
  void ordinaryMotionNeverIncludesDropChatOrAttack() {
    for (String x : new String[] {"drop", "chat", "command", "attack", "use", "alt_f4"})
      assertFalse(ControlPolicy.MOTION.contains(x));
  }

  @Test
  void inventoryPermissionDoesNotImplyGenericMachineButtonOrKeyPermission() {
    for (String tool :
        new String[] {
          "look_at", "press_control", "press_key", "gui_button", "gui_text", "gui_scroll"
        }) {
      assertTrue(ControlPolicy.needsInputPermission(tool));
      assertTrue(Rules.WRITE.contains(tool));
    }
    assertFalse(ControlPolicy.needsInputPermission("move_inventory"));
    assertFalse(ControlPolicy.needsInputPermission("inspect_gui"));
  }

  @Test
  void foodRequiresBothConsumptionAndFreshServerNutrition() {
    assertTrue(ControlPolicy.ate(8, 7, 7, 12, 17, 17, true, true));
    assertFalse(ControlPolicy.ate(8, 7, 8, 12, 17, 17, true, true));
    assertFalse(ControlPolicy.ate(8, 7, 7, 12, 17, 17, false, true));
    assertFalse(ControlPolicy.ate(8, 7, 7, 12, 17, 17, true, false));
    assertFalse(ControlPolicy.ate(8, 7, 7, 12, 12, 12, true, true));
    assertFalse(ControlPolicy.ate(8, 6, 6, 12, 17, 17, true, true));
    assertFalse(ControlPolicy.ate(8, 7, 7, 12, 17, 12, true, true));
  }

  @Test
  void specialOrEffectFoodsAreNotAutomaticallySelected() {
    assertFalse(ControlPolicy.FOOD.contains("minecraft:chorus_fruit"));
    assertFalse(ControlPolicy.FOOD.contains("minecraft:rotten_flesh"));
    assertFalse(ControlPolicy.FOOD.contains("minecraft:pufferfish"));
    assertFalse(ControlPolicy.FOOD.contains("mod:unknown_food"));
    assertTrue(ControlPolicy.FOOD.contains("minecraft:bread"));
  }
}
