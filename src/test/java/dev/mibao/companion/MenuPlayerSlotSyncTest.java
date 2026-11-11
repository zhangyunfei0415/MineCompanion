package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class MenuPlayerSlotSyncTest {
  @Test
  void projectedServerPlayerReceiptCompletesCustomMenuEvidenceWithoutNewFullPacket() {
    var sync = new MenuSyncEvidence<String>(Objects::equals);
    var menu = new Object();
    sync.bind(menu, 7);
    sync.content(menu, 7, 1, List.of("rice1", "rice1", "bowl16"), "empty", 10);
    // Current menu prediction: rice moved from player slot 1 to machine slot 0.
    sync.slot(menu, 7, 2, 0, "rice1", 20);
    assertFalse(sync.ready(menu, 7, List.of("rice1", "empty", "bowl16"), "empty", 200, 150));
    // Receipt from the shared player inventory is projected to slot 1 by MenuContext.
    sync.slot(menu, 7, sync.state(), 1, "empty", 30);
    assertTrue(sync.ready(menu, 7, List.of("rice1", "empty", "bowl16"), "empty", 200, 150));
    // Subsequent production cannot be assumed until its own receipts arrive.
    assertFalse(sync.ready(menu, 7, List.of("meal1", "empty", "bowl15"), "empty", 200, 150));
    sync.slot(menu, 7, 3, 0, "meal1", 210);
    sync.slot(menu, 7, 3, 2, "bowl15", 210);
    assertTrue(sync.ready(menu, 7, List.of("meal1", "empty", "bowl15"), "empty", 360, 150));
  }
}
