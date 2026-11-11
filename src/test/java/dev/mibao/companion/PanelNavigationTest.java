package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PanelNavigationTest {
  @Test
  void everyExistingPageHasOneGroupedHome() {
    for (int i = 0; i <= 15; i++) {
      boolean settings = java.util.Set.of(4, 5, 6, 7, 11, 14).contains(i);
      int parent = PanelNavigation.section(i, settings);
      assertTrue(PanelNavigation.sections(settings).stream().anyMatch(s -> s.tab() == parent));
    }
  }

  @Test
  void operationalPagesBelongToLedgerTaskAndBothRootsStayShort() {
    for (int i : new int[] {9, 10, 12, 13}) assertEquals(0, PanelNavigation.section(i, false));
    assertTrue(PanelNavigation.sections(false).size() <= 7);
    assertTrue(PanelNavigation.sections(true).size() <= 7);
    assertEquals(12, PanelNavigation.section(12, true));
  }
}
