package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OutcomeContractsTest {
  @Test
  void dispatchedAndRejectedNeverTurnIntoBusinessSuccess() {
    String dispatched = "{\"status\":\"dispatched\",\"business_success\":false}";
    String rejected = "{\"status\":\"rejected\",\"business_success\":false}";
    assertTrue(Rules.actionFailed(dispatched));
    assertTrue(Rules.actionFailed(rejected));
    assertEquals("failed", ActionOutcome.describe(rejected).get("outcome").getAsString());
    assertEquals(
        "reported_effect_unverified",
        ActionOutcome.describe(dispatched).get("outcome").getAsString());
  }

  @Test
  void observedMatchingWorldStateDoesNotProveThatThisCommandChangedIt() {
    assertTrue(Rules.actionFailed("{\"status\":\"state_matches\"}"));
    assertTrue(Rules.actionFailed("{\"status\":\"position_changed\"}"));
    assertFalse(Rules.actionFailed("{\"status\":\"verified\",\"business_success\":true}"));
  }
}
