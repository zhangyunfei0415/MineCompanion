package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CooperativeBatchTest {
  @Test
  void flexibleIngredientDoesNotConsumeTheOnlySpecificMaterial() {
    assertEquals(
        List.of(11, 10),
        CraftAllocation.choose(List.of(List.of(10, 11), List.of(10)), Map.of(10, 1, 11, 1)));
  }

  @Test
  void repeatedIngredientsShareAStackWithoutOverconsumption() {
    assertEquals(
        List.of(10, 10, 10),
        CraftAllocation.choose(List.of(List.of(10), List.of(10), List.of(10)), Map.of(10, 3)));
    assertThrows(
        IllegalArgumentException.class,
        () -> CraftAllocation.choose(List.of(List.of(10), List.of(10)), Map.of(10, 1)));
  }

  @Test
  void shapedStickPositionsRemainVerticalInBothMenus() {
    assertEquals(1, CraftAllocation.cell(0, 1, 2));
    assertEquals(3, CraftAllocation.cell(1, 1, 2));
    assertEquals(4, CraftAllocation.cell(1, 1, 3));
  }

  @Test
  void recipeRingPreservesTheEmptyCenter() {
    assertEquals(5, CraftAllocation.cell(4, 3, 3));
    assertEquals(9, CraftAllocation.cell(8, 3, 3));
  }

  @Test
  void rightDragUsesVanillaHeaderAndTypeMasks() {
    var clicks = GesturePlan.oneEach(12, List.of(1, 3), 64, true);
    assertEquals(new GesturePlan.Click(12, 0, "PICKUP"), clicks.get(0));
    assertEquals(new GesturePlan.Click(-999, 4, "QUICK_CRAFT"), clicks.get(1));
    assertEquals(new GesturePlan.Click(1, 5, "QUICK_CRAFT"), clicks.get(2));
    assertEquals(new GesturePlan.Click(3, 5, "QUICK_CRAFT"), clicks.get(3));
    assertEquals(new GesturePlan.Click(-999, 6, "QUICK_CRAFT"), clicks.get(4));
    assertEquals(new GesturePlan.Click(12, 0, "PICKUP"), clicks.get(5));
  }

  @Test
  void clickFallbackNeverDropsOutsideTheMenu() {
    var clicks = GesturePlan.oneEach(12, List.of(1, 3), 2, false);
    assertEquals(4, clicks.size());
    assertTrue(clicks.stream().allMatch(c -> c.slot() >= 0 && c.type().equals("PICKUP")));
    assertEquals(1, clicks.get(1).button());
  }

  @Test
  void duplicateOrInsufficientGestureTargetsFailBeforeAnyClick() {
    assertThrows(
        IllegalArgumentException.class, () -> GesturePlan.oneEach(12, List.of(1, 1), 64, true));
    assertThrows(
        IllegalArgumentException.class, () -> GesturePlan.oneEach(12, List.of(12), 64, true));
    assertThrows(
        IllegalArgumentException.class, () -> GesturePlan.oneEach(12, List.of(1, 3), 1, true));
    assertThrows(
        IllegalArgumentException.class, () -> GesturePlan.oneEach(12, List.of(-999), 64, true));
  }

  @Test
  void physicalHoldPausesEvenAfterIdleDelay() {
    assertTrue(CooperativePolicy.yield(true, true, true, false, false, false, 9000, 1000));
    assertFalse(CooperativePolicy.yield(false, true, true, false, false, false, 9000, 1000));
  }

  @Test
  void humanOwnedMenuDoesNotResumeJustBecauseMouseStopped() {
    assertTrue(CooperativePolicy.yield(true, true, false, true, true, false, 9000, 1000));
    assertFalse(CooperativePolicy.yield(true, true, false, true, false, false, 9000, 1000));
  }

  @Test
  void explicitResumeModeWaitsWhileAutomaticModeExpires() {
    assertTrue(CooperativePolicy.yield(true, true, false, false, false, true, 9000, 1000));
    assertFalse(CooperativePolicy.yield(true, true, false, false, false, false, 9000, 1000));
    assertFalse(CooperativePolicy.yield(true, false, false, false, false, true, 9000, 1000));
  }

  @Test
  void idleDelayAllowsShortManualCorrections() {
    assertTrue(CooperativePolicy.yield(true, true, false, false, false, false, 1000, 2200));
    assertFalse(CooperativePolicy.yield(true, true, false, false, false, false, 2200, 2200));
  }

  @Test
  void reconnectPreservesOldWorldReceiptButDoesNotReviveIt() {
    var session = new HarnessSession();
    String old = session.connect("worldA|overworld", 0);
    var receipt = session.begin("one", "fetch", 1, "fetch");
    session.interrupt("world changed");
    session.connect("worldA|nether", 1);
    var history = session.receipt("one", "worldA|nether");
    assertTrue(history.get("historical_context").getAsBoolean());
    assertFalse(history.get("replay_allowed").getAsBoolean());
    assertEquals("interrupted", history.get("state").getAsString());
    assertThrows(IllegalArgumentException.class, () -> session.check(old, "worldA|overworld", 2));
    assertThrows(IllegalArgumentException.class, () -> session.begin("one", "fetch", 2, "fetch"));
    assertEquals("worldA|overworld", receipt.get("scope").getAsString());
  }

  @Test
  void stoppedCurrentScopeRemainsReadOnly() {
    var s = new HarnessSession();
    s.connect("A", 0);
    s.begin("one", "craft", 4, "craft");
    s.interrupt("stop");
    assertFalse(s.receipt("one", "A").get("historical_context").getAsBoolean());
    assertFalse(s.active(1));
  }

  @Test
  void placementFailuresDoNotSuggestInventoryCleanup() {
    assertEquals("placement", FailureAdvice.forResult("失败：[NO_PLACE_SITE] 没有空位").code());
  }

  @Test
  void gestureSharesExistingGuiPermissionSurface() {
    assertTrue(Rules.TOOLS.contains("gui_distribute"));
    assertTrue(Rules.WRITE.contains("gui_distribute"));
    assertEquals("interface", CapabilityCatalog.family("gui_distribute"));
  }
}
