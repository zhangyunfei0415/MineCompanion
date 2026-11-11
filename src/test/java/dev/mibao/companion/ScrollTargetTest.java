package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ScrollTargetTest {
  @Test
  void validateAcceptsOnlyTheTwoPanels() {
    assertNull(ScrollTarget.validate("chapters"));
    assertNull(ScrollTarget.validate("tree"));
    assertEquals("[ARGUMENT_INVALID] area须为chapters或tree", ScrollTarget.validate("Tree"));
    assertEquals("[ARGUMENT_INVALID] area须为chapters或tree", ScrollTarget.validate(""));
  }

  @Test
  void planUsesThePanelCentre() {
    var plan = ScrollTarget.plan(new int[] {20, 1, 814, 455}, 854, 457, 0.62);
    assertEquals(427, plan.x);
    assertEquals(228, plan.y);
    assertFalse(plan.sidebarCollapsed);
  }

  @Test
  void planFallsBackToTheViewportFractionWithoutARectangle() {
    var plan = ScrollTarget.plan(null, 854, 457, 0.62);
    assertEquals(529, plan.x);
    assertEquals(228, plan.y);
    assertFalse(plan.sidebarCollapsed);
  }

  @Test
  void planReportsACollapsedSidebar() {
    var plan = ScrollTarget.plan(new int[] {-120, 0, 120, 457}, 854, 457, 0.06);
    assertTrue(plan.sidebarCollapsed);
    assertEquals(51, plan.x);
    assertEquals(228, plan.y);
  }

  @Test
  void planClampsPointsOutsideTheViewport() {
    var plan = ScrollTarget.plan(new int[] {800, 400, 200, 200}, 854, 457, 0.62);
    assertEquals(853, plan.x);
    assertEquals(456, plan.y);
  }

  @Test
  void planFallsBackOnAZeroSizedRectangle() {
    var plan = ScrollTarget.plan(new int[] {10, 10, 0, 0}, 854, 457, 0.5);
    assertEquals(427, plan.x);
    assertEquals(228, plan.y);
    assertFalse(plan.sidebarCollapsed);
  }

  @Test
  void planStaysInsideATinyViewport() {
    var plan = ScrollTarget.plan(null, 0, 0, 0.5);
    assertEquals(0, plan.x);
    assertEquals(0, plan.y);
  }
}
