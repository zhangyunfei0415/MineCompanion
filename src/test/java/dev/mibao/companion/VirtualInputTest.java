package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class VirtualInputTest {
  @Test
  void virtualKeysNeverEscapeWindowEpochOrDeadline() {
    var s = new VirtualKeyState();
    s.begin(55, 2, 100, List.of(341, 49));
    assertEquals(1, s.query(55, 2, 101, 341));
    assertEquals(1, s.query(55, 2, 101, 49));
    assertEquals(-1, s.query(55, 2, 101, 50));
    assertEquals(-1, s.query(56, 2, 101, 49));
    assertEquals(-1, s.query(55, 3, 101, 49));
    assertEquals(-1, s.query(55, 2, 500_000_100L, 49));
    s.clear();
    assertFalse(s.active(55, 2, 101));
  }

  @Test
  void emptyKeyboardSetCanOwnMouseFocusLeaseWithoutPressingUnknownKeys() {
    var s = new VirtualKeyState();
    s.begin(1, 1, 0, List.of());
    assertTrue(s.active(1, 1, 1));
    assertEquals(-1, s.query(1, 1, 1, 49));
    s.clear();
    assertTrue(s.empty());
  }

  @Test
  void permissionsAndModifiersReflectFullAskAndLimited() {
    assertEquals("allowed", InputPolicy.access("FULL", false, false, false));
    assertEquals("approval_required", InputPolicy.access("ASK", false, false, false));
    assertEquals("allowed", InputPolicy.access("ASK", false, false, true));
    assertEquals("permission_required", InputPolicy.access("LIMITED", true, false, true));
    assertEquals("allowed", InputPolicy.access("LIMITED", true, true, false));
    assertEquals("CTRL+SHIFT", InputPolicy.modifiers("shift+ctrl+CTRL"));
    assertEquals(Set.of(341, 340), InputPolicy.codes("CTRL+SHIFT"));
    assertThrows(IllegalArgumentException.class, () -> InputPolicy.modifiers("META"));
  }

  @Test
  void fullChordConflictsDifferFromBareHotbarAndProtectedControls() {
    assertFalse(InputPolicy.conflict("key.hotbar.1", "CTRL", ""));
    assertTrue(InputPolicy.conflict("mod.other", "CTRL", "CTRL"));
    assertEquals("CTRL", InputPolicy.knownModifier("key.limitless_roles.slot6"));
    assertTrue(InputPolicy.protectedKey("key.use"));
    assertTrue(InputPolicy.protectedKey("key.logistics_companion.stop"));
    assertFalse(InputPolicy.protectedKey("key.sukuna.cast"));
  }
}
