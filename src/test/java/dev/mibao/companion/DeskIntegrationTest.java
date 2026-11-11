package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DeskIntegrationTest {
  @Test
  void everyMigratedEditorHasOneVisibleOwner() {
    for (int i = 1; i <= 22; i++) {
      var r = DeskRoute.of(i, false);
      assertTrue(r.page() >= 0 && r.page() < 5);
      assertFalse(r.title().isBlank());
    }
    assertThrows(IllegalArgumentException.class, () -> DeskRoute.of(99, false));
  }

  @Test
  void controlsSwitchToSettingsWithoutCreatingAnotherNavigationTree() {
    var inventory = DeskRoute.of(12, false);
    var keys = DeskRoute.of(12, true);
    assertFalse(inventory.settings());
    assertEquals(0, inventory.page());
    assertTrue(keys.settings());
    assertEquals(2, keys.page());
  }

  @Test
  void identityAndRecipesNoLongerOpenTheModelEditor() {
    assertEquals(3, DeskRoute.of(16, true).page());
    assertEquals(0, DeskRoute.of(4, true).page());
    assertEquals(1, DeskRoute.of(8, false).page());
    assertEquals(4, DeskRoute.of(15, false).page());
  }

  ConnectionForm valid() {
    return new ConnectionForm(
        " https://example.com/v1 ", " test-model ", " test-secret ", "CHAT", 2400, 24, 90, 25579);
  }

  @Test
  void modelDraftValidatesAndNormalizesBeforeACommit() {
    var f = valid();
    assertEquals("https://example.com/v1", f.endpoint());
    assertEquals("test-model", f.model());
    assertEquals("test-secret", f.key());
    assertFalse(f.toString().contains("test-secret"));
  }

  @Test
  void invalidDraftDoesNotYieldAnApplicableValue() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConnectionForm("", "model", "", "CHAT", 2400, 24, 90, 25579));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConnectionForm(
                "https://example.com/v1/responses", "model", "", "CHAT", 2400, 24, 90, 25579));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConnectionForm("http://example.com", "model", "", "CHAT", 2400, 24, 90, 25579));
  }

  @Test
  void modelBudgetAndPortBoundsAreRejectedRatherThanSilentlyClamped() {
    for (int n : new int[] {0, 97})
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ConnectionForm(
                  "https://example.com/v1", "model", "", "CHAT", 2400, n, 90, 25579));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConnectionForm("https://example.com/v1", "model", "", "CHAT", 2400, 24, 90, 80));
  }

  @Test
  void secretIsNotIncludedInValidationErrors() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ConnectionForm(
                    "https://example.com/%bad%secret",
                    "model", "test-secret", "RESPONSES", 2400, 24, 90, 25579));
    assertFalse(ex.getMessage().contains("secret"));
  }
}
