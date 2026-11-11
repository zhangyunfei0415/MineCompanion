package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HarnessReadbackTest {
  @Test
  void stopRevokesWritesButPreservesScopedReadback() {
    var s = new HarnessSession();
    String lease = s.connect("worldA|overworld", 0);
    s.begin("one", "fetch:32", 1, "fetch");
    s.interrupt("stop");
    assertEquals("interrupted", s.receipt("one", "worldA|overworld").get("state").getAsString());
    assertThrows(IllegalArgumentException.class, () -> s.check(lease, "worldA|overworld", 1));
    assertTrue(s.receipt("one", "worldB|overworld").get("historical_context").getAsBoolean());
    assertFalse(s.receipt("one", "worldB|overworld").get("replay_allowed").getAsBoolean());
  }

  @Test
  void readbackDoesNotExtendExpiredLease() {
    var s = new HarnessSession();
    s.connect("A", 0);
    s.begin("one", "observe", 1, "observe");
    assertNotNull(s.receipt("one", "A"));
    assertTrue(s.expired(HarnessSession.TTL));
  }

  @Test
  void capabilitiesDistinguishNativeProtocolsAndWriteAuthority() {
    var c = HarnessCapabilities.describe(80);
    assertTrue(c.get("receipt_after_stop").getAsBoolean());
    assertFalse(c.get("status_and_receipt_require_takeover").getAsBoolean());
    assertTrue(c.get("write_actions_require_takeover").getAsBoolean());
    assertTrue(c.get("native_responses_api").getAsBoolean());
    assertTrue(c.get("native_anthropic_messages").getAsBoolean());
    assertTrue(c.get("native_gemini_generate_content").getAsBoolean());
    assertEquals(4, c.getAsJsonArray("supported_model_protocols").size());
    assertEquals(80, c.get("game_tool_count").getAsInt());
  }
}
