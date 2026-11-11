package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class PlanningPolicyTest {
  @Test
  void threeBudgetsRespectExplicitBaseAndBounds() {
    assertEquals(8, PlanningPolicy.rounds("LOW", 24));
    assertEquals(24, PlanningPolicy.rounds("MEDIUM", 24));
    assertEquals(48, PlanningPolicy.rounds("HIGH", 24));
    assertEquals(1, PlanningPolicy.rounds("LOW", 1));
    assertEquals(96, PlanningPolicy.rounds("HIGH", 96));
    assertEquals(24, PlanningPolicy.rounds("invalid", 24));
  }

  @Test
  void effortCycleAndHintsAreDistinct() {
    assertEquals("MEDIUM", PlanningPolicy.next("LOW"));
    assertEquals("HIGH", PlanningPolicy.next("MEDIUM"));
    assertEquals("LOW", PlanningPolicy.next("HIGH"));
    assertNotEquals(PlanningPolicy.instruction("LOW"), PlanningPolicy.instruction("HIGH"));
  }

  JsonObject body() {
    return JsonParser.parseString(
            "{\"model\":\"test\",\"max_tokens\":2400,\"messages\":[],\"tools\":[]}")
        .getAsJsonObject();
  }

  @Test
  void nativeOffLeavesCompatibleRequestsUntouched() {
    for (String protocol : ProviderWire.PROTOCOLS) {
      var b = body();
      var old = b.deepCopy();
      PlanningPolicy.applyNative(b, protocol, "HIGH", false);
      assertEquals(old, b);
    }
  }

  @Test
  void responsesUsesNestedEffortAndKeepsToolsAndBudget() {
    var b = body();
    PlanningPolicy.applyNative(b, "RESPONSES", "HIGH", true);
    assertEquals("high", b.getAsJsonObject("reasoning").get("effort").getAsString());
    assertFalse(b.has("reasoning_effort"));
    assertTrue(b.has("tools"));
    assertEquals(2400, b.get("max_tokens").getAsInt());
  }

  @Test
  void nativeChatUsesCompletionBudgetWithoutConflictingLegacyField() {
    var b = body();
    PlanningPolicy.applyNative(b, "CHAT", "LOW", true);
    assertEquals("low", b.get("reasoning_effort").getAsString());
    assertFalse(b.has("max_tokens"));
    assertEquals(2400, b.get("max_completion_tokens").getAsInt());
  }

  @Test
  void otherProtocolsNeverReceiveInventedNativeEffort() {
    for (String protocol : new String[] {"ANTHROPIC", "GEMINI"}) {
      var b = body();
      var old = b.deepCopy();
      PlanningPolicy.applyNative(b, protocol, "HIGH", true);
      assertEquals(old, b);
      assertFalse(PlanningPolicy.nativeSupportedProtocol(protocol));
    }
  }
}
