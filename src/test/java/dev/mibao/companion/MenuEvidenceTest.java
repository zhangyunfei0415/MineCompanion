package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class MenuEvidenceTest {
  @Test
  void unrelatedLiveUpdatesDoNotInvalidateAnUnchangedTarget() {
    var guard = new MenuEvidence<String>(Objects::equals);
    Object menu = new Object();
    long view = guard.capture(menu, 7, 10, Map.of(0, "iron32", 1, "animated"), "heldA");
    assertTrue(guard.validTarget(menu, 7, view, 0, "iron32", "heldA"));
    assertFalse(guard.valid(menu, 7, 11, view, 0, Map.of(0, "iron32", 1, "animated2"), "heldA"));
    assertFalse(guard.validTarget(menu, 7, view, 0, "iron16", "heldA"));
    assertFalse(guard.validTarget(menu, 7, view, 0, "iron32", "heldB"));
    guard.clear();
    assertFalse(guard.validTarget(menu, 7, view, 0, "iron32", "heldA"));
  }

  @Test
  void changedSlotsRejectClicksEvenWithoutServerRevisionChange() {
    var guard = new MenuEvidence<String>(Objects::equals);
    Object menu = new Object();
    long view = guard.capture(menu, 3, 0, Map.of(0, "iron:64", 1, "empty"), "empty");
    assertTrue(guard.valid(menu, 3, 0, view, 0, Map.of(0, "iron:64", 1, "empty"), "empty"));
    assertFalse(guard.valid(menu, 3, 0, view, 0, Map.of(0, "gold:64", 1, "empty"), "empty"));
  }

  @Test
  void cursorChangesAndUnobservedSlotsAreRejected() {
    var guard = new MenuEvidence<String>(Objects::equals);
    Object menu = new Object();
    long view = guard.capture(menu, 3, 0, Map.of(0, "iron"), "empty");
    assertFalse(guard.valid(menu, 3, 0, view, 0, Map.of(0, "iron"), "gold"));
    assertFalse(guard.valid(menu, 3, 0, view, 8, Map.of(0, "iron", 8, "gold"), "empty"));
  }

  @Test
  void aReusedContainerIdCannotReuseAnOldMenuObjectOrObservation() {
    var guard = new MenuEvidence<String>(Objects::equals);
    Object first = new Object();
    long old = guard.capture(first, 3, 0, Map.of(0, "iron"), "empty");
    Object second = new Object();
    assertFalse(guard.valid(second, 3, 0, old, 0, Map.of(0, "iron"), "empty"));
    long current = guard.capture(first, 3, 0, Map.of(0, "iron"), "empty");
    assertNotEquals(old, current);
    assertFalse(guard.valid(first, 3, 0, old, 0, Map.of(0, "iron"), "empty"));
    guard.clear();
    assertFalse(guard.valid(first, 3, 0, current, 0, Map.of(0, "iron"), "empty"));
  }
}
