package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class MenuSyncEvidenceTest {
  private final MenuSyncEvidence<String> sync = new MenuSyncEvidence<>(Objects::equals);
  private final Object owner = new Object();

  @Test
  void affectedSlotAndCursorCanBeConfirmedWhileOtherSlotsAnimate() {
    sync.bind(owner, 1);
    sync.content(owner, 1, 1, List.of("empty", "animated1"), "iron32", 100);
    assertTrue(sync.targetReady(owner, 1, 0, "empty", "iron32"));
    assertFalse(sync.ready(owner, 1, List.of("empty", "animated2"), "iron32", 101, 0));
    assertFalse(sync.targetReady(owner, 1, 0, "empty", "gun#A"));
    assertFalse(sync.targetReady(new Object(), 1, 0, "empty", "iron32"));
    sync.slot(owner, 1, 2, 0, "iron32", 102);
    sync.cursor(owner, "empty", 102);
    assertTrue(sync.targetReady(owner, 1, 0, "iron32", "empty"));
  }

  @Test
  void emptyPredictedMenuIsUnknownUntilFullPacketArrives() {
    sync.bind(owner, 1);
    assertFalse(sync.ready(owner, 1, List.of("empty"), "empty", 9999, 150));
    sync.slot(owner, 1, 0, 0, "iron32", 10);
    assertFalse(sync.received());
    sync.content(owner, 1, 0, List.of("empty"), "empty", 100);
    assertTrue(sync.ready(owner, 1, List.of("empty"), "empty", 250, 150));
  }

  @Test
  void freshFullContentMustMatchAllSlotsAndCursor() {
    sync.bind(owner, 1);
    sync.content(owner, 1, 7, List.of("iron64", "empty"), "empty", 100);
    assertFalse(sync.ready(owner, 1, List.of("iron32", "iron32"), "empty", 300, 150));
    assertFalse(sync.ready(owner, 1, List.of("iron64", "empty"), "gunA", 300, 150));
    assertFalse(sync.ready(owner, 1, List.of("iron64"), "empty", 300, 150));
    assertTrue(sync.ready(owner, 1, List.of("iron64", "empty"), "empty", 300, 150));
  }

  @Test
  void menuIdReuseCannotReusePreviousSessionEvidence() {
    sync.bind(owner, 1);
    sync.content(owner, 1, 1, List.of("iron32"), "empty", 100);
    Object reopened = new Object();
    sync.bind(reopened, 1);
    assertFalse(sync.received());
    sync.content(owner, 1, 2, List.of("iron32"), "empty", 200);
    assertFalse(sync.received());
    sync.content(reopened, 1, 0, List.of("iron16"), "empty", 300);
    assertTrue(sync.ready(reopened, 1, List.of("iron16"), "empty", 450, 150));
  }

  @Test
  void wrongContainerPacketsCannotConfirmThisMenu() {
    sync.bind(owner, 3);
    sync.content(owner, 0, 4, List.of("iron32"), "empty", 100);
    assertFalse(sync.received());
    sync.content(owner, 3, 4, List.of("iron32"), "empty", 200);
    sync.slot(owner, 2, 5, 0, "gunB", 250);
    assertTrue(sync.ready(owner, 3, List.of("iron32"), "empty", 400, 150));
  }

  @Test
  void slotAndCursorUpdatesRestartQuietWindowAndRetainVariants() {
    sync.bind(owner, 3);
    sync.content(owner, 3, 4, List.of("gun#A"), "empty", 100);
    long first = sync.revision();
    sync.slot(owner, 3, 5, 0, "gun#B", 200);
    sync.cursor(owner, "iron1", 250);
    assertTrue(sync.revision() > first);
    assertEquals(5, sync.state());
    assertEquals(250, sync.receivedAt());
    assertFalse(sync.ready(owner, 3, List.of("gun#B"), "iron1", 399, 150));
    assertTrue(sync.ready(owner, 3, List.of("gun#B"), "iron1", 400, 150));
    assertFalse(sync.ready(owner, 3, List.of("gun#A"), "iron1", 400, 150));
  }

  @Test
  void rebindingSameOwnerDoesNotLoseEvidenceButClearDoes() {
    sync.bind(owner, 3);
    sync.content(owner, 3, 0, List.of(), "empty", 100);
    sync.bind(owner, 3);
    assertTrue(sync.ready(owner, 3, List.of(), "empty", 250, 150));
    sync.clear();
    assertFalse(sync.ready(owner, 3, List.of(), "empty", 500, 150));
  }
}
