package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TaskCardTest {
  @Test
  void progressUsesConfirmedDestinationNotPlannedClicksOrCarriedItems() {
    var c = new TaskCard();
    c.target = 128;
    c.delivered = 64;
    c.carrying = 64;
    assertEquals(0, c.percent());
    c.current = 32;
    assertEquals(25, c.percent());
    c.current = 256;
    assertEquals(100, c.percent());
    var copy = c.copy();
    copy.current = 0;
    assertEquals(256, c.current);
  }
}
