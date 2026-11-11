package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class UsageStatsTest {
  private JsonObject j(String s) {
    return JsonParser.parseString(s).getAsJsonObject();
  }

  @Test
  void responsesCacheAndReasoningAreSubsetsNotAddedAgain() {
    var c =
        UsageStats.parse(
            "RESPONSES",
            j(
                "{\"usage\":{\"input_tokens\":100,\"output_tokens\":40,\"input_tokens_details\":{\"cached_tokens\":80},\"output_tokens_details\":{\"reasoning_tokens\":30}}}"));
    assertEquals(100L, c.input());
    assertEquals(40L, c.output());
    assertEquals(80L, c.cached());
    assertEquals(30L, c.reasoning());
  }

  @Test
  void anthropicUncachedAndCacheReadsWritesHaveDifferentAccounting() {
    var c =
        UsageStats.parse(
            "ANTHROPIC",
            j(
                "{\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"cache_read_input_tokens\":80,\"cache_creation_input_tokens\":10}}"));
    assertEquals(100L, c.input());
    assertEquals(80L, c.cached());
    assertEquals(10L, c.cacheWrite());
    assertNull(c.reasoning());
  }

  @Test
  void geminiOutputIncludesThinkingButCachesStayWithinPrompt() {
    var c =
        UsageStats.parse(
            "GEMINI",
            j(
                "{\"usageMetadata\":{\"promptTokenCount\":100,\"candidatesTokenCount\":20,\"thoughtsTokenCount\":30,\"cachedContentTokenCount\":80}}"));
    assertEquals(100L, c.input());
    assertEquals(50L, c.output());
    assertEquals(30L, c.reasoning());
  }

  @Test
  void missingUsageIsUnknownAndProviderModelsAreCountedSeparately() {
    var map = new LinkedHashMap<String, UsageStats.Total>();
    UsageStats.add(map, "CHAT", "a", "host", 401, 10, UsageStats.parse("CHAT", new JsonObject()));
    assertTrue(UsageStats.human(map).contains("输入 未知"));
    UsageStats.add(
        map, "RESPONSES", "a", "host", 200, 30, new UsageStats.Counts(20L, 10L, null, null, null));
    assertEquals(2, map.size());
    assertEquals(1, map.values().iterator().next().failed);
  }

  @Test
  void chatSupportsDeepseekCacheFieldAndNullableUsage() {
    assertEquals(
        12L,
        UsageStats.parse(
                "CHAT",
                j(
                    "{\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":2,\"prompt_cache_hit_tokens\":12}}"))
            .cached());
    assertNull(UsageStats.parse("CHAT", j("{\"usage\":null}")).input());
  }
}
