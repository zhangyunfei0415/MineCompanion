package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OverlayLayoutTest {
  @Test
  void layoutsAvoidSidebarAvatarAndVanillaStatusArea() {
    for (int w : new int[] {320, 427, 682, 960})
      for (int h : new int[] {240, 360, 540})
        for (boolean left : new boolean[] {true, false})
          for (boolean edit : new boolean[] {true, false}) {
            var l = OverlayLayout.calculate(w, h, true, left, 190, 5, edit);
            var d = l.dialogue();
            var a = l.avatar();
            assertTrue(d.x() >= 0 && d.right() <= w && d.bottom() <= h - 64);
            int sw = OverlayLayout.sidebarWidth(w, 190);
            var sidebar = new OverlayLayout.Rect(left ? 8 : w - sw - 8, 8, sw, h - 80);
            assertTrue(d.width() >= 100);
            assertFalse(d.overlaps(sidebar));
            if (a.width() > 0) {
              assertFalse(a.overlaps(d));
              assertFalse(a.overlaps(sidebar));
            }
          }
  }

  @Test
  void editingHasMoreSpaceAndIdleKeepsOnlyTwoRows() {
    var idle = OverlayLayout.calculate(682, 365, true, false, 190, 5, false);
    var edit = OverlayLayout.calculate(682, 365, true, false, 190, 5, true);
    assertEquals(34, idle.dialogue().height());
    assertEquals(70, edit.dialogue().height());
    assertEquals(idle.dialogue().bottom(), edit.dialogue().bottom());
  }
}
