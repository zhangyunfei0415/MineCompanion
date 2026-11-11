package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ManualUseLatchTest {
  @Test
  void holdingDoesNotToggleDoorRepeatedly() {
    var gate = new ManualUseLatch();
    gate.press();
    assertTrue(gate.allow());
    assertFalse(gate.allow());
    gate.press();
    assertFalse(gate.allow());
    gate.release();
    gate.press();
    assertTrue(gate.allow());
  }

  @Test
  void unknownOrKeyboardUsePassesThrough() {
    var gate = new ManualUseLatch();
    assertTrue(gate.allow());
    assertTrue(gate.allow());
    gate.press();
    gate.allow();
    gate.release();
    assertTrue(gate.allow());
  }
}
