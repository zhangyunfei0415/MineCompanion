package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PointerStateTest {
  @Test
  void pointerIsBoundToScreenEpochSizeAndExpiry() {
    var p = new PointerState();
    var screen = new Object();
    p.begin(screen, 3, 854, 457, 400, 200, 10);
    p.button(0, true);
    assertTrue(p.down(0));
    assertTrue(p.active(screen, 3, 854, 457, 11));
    assertFalse(p.active(new Object(), 3, 854, 457, 11));
    assertFalse(p.active(screen, 4, 854, 457, 11));
    assertFalse(p.active(screen, 3, 427, 229, 11));
    assertFalse(p.active(screen, 3, 854, 457, 2_000_000_010L));
    p.clear();
    assertFalse(p.down(0));
    assertFalse(p.active(screen, 3, 854, 457, 11));
  }

  @Test
  void interpolationAndButtonReleaseDoNotNeedOsInput() {
    var p = new PointerState();
    p.begin(this, 1, 800, 400, 700, 300, 0);
    p.button(1, true);
    p.move(100, 50, 1);
    assertEquals(100, p.x());
    assertEquals(50, p.y());
    assertTrue(p.down(1));
    p.button(1, false);
    assertFalse(p.down(1));
    assertThrows(IllegalArgumentException.class, () -> p.move(Double.NaN, 0, 2));
    assertThrows(IllegalArgumentException.class, () -> p.move(800, 0, 2));
  }

  @Test
  void clippingUsesScreenIntersectionNotFullContainment() {
    var viewport = new GuiViewport(0, 0, 854, 457);
    var part = viewport.intersect(new GuiViewport(-10, 50, 10, 70));
    assertTrue(part.visible());
    assertEquals(5, part.centerX());
    assertFalse(viewport.intersect(new GuiViewport(1200, 900, 1220, 920)).visible());
    assertFalse(viewport.intersect(new GuiViewport(854, 0, 900, 20)).visible());
    assertFalse(
        new GuiViewport(100, 100, 200, 200).intersect(new GuiViewport(10, 10, 20, 20)).visible());
  }

  @Test
  void semanticOpenIsWriteAndNotAnUnrestrictedRewardClick() {
    assertTrue(Rules.TOOLS.contains("quest_book_open"));
    assertTrue(Rules.WRITE.contains("quest_book_open"));
    assertFalse(
        PermissionPolicy.denial(
                "quest_book_open",
                new PermissionPolicy.Grants("LIMITED", true, false, false, false))
            .isBlank());
  }
}
