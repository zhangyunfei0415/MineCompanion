package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/**
 * Provider-reported counts; absent fields remain unknown, cache/reasoning subsets are not billed
 * twice.
 */
public final class UsageStats {
  public record Counts(Long input, Long output, Long cached, Long cacheWrite, Long reasoning) {}

  public static class Total {
    public String protocol = "", model = "", host = "";
    public long requests,
        failed,
        usageReported,
        input,
        output,
        cached,
        cacheWrite,
        reasoning,
        latencyMs;
    public long inputReported, outputReported, cachedReported, reasoningReported;
  }

  private static Long number(JsonObject o, String key) {
    try {
      if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
      long n = o.get(key).getAsLong();
      return n >= 0 ? n : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static JsonObject object(JsonObject o, String key) {
    return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
  }

  private static Long sum(Long a, Long b) {
    return a == null && b == null
        ? null
        : Optional.ofNullable(a).orElse(0L) + Optional.ofNullable(b).orElse(0L);
  }

  public static Counts parse(String protocol, JsonObject response) {
    var u = object(response, protocol.equals("GEMINI") ? "usageMetadata" : "usage");
    if (protocol.equals("CHAT")) {
      Long cached = number(object(u, "prompt_tokens_details"), "cached_tokens");
      if (cached == null) cached = number(u, "prompt_cache_hit_tokens");
      return new Counts(
          number(u, "prompt_tokens"),
          number(u, "completion_tokens"),
          cached,
          null,
          number(object(u, "completion_tokens_details"), "reasoning_tokens"));
    }
    if (protocol.equals("RESPONSES"))
      return new Counts(
          number(u, "input_tokens"),
          number(u, "output_tokens"),
          number(object(u, "input_tokens_details"), "cached_tokens"),
          null,
          number(object(u, "output_tokens_details"), "reasoning_tokens"));
    if (protocol.equals("ANTHROPIC")) {
      Long write = number(u, "cache_creation_input_tokens"),
          read = number(u, "cache_read_input_tokens");
      return new Counts(
          sum(number(u, "input_tokens"), sum(write, read)),
          number(u, "output_tokens"),
          read,
          write,
          null);
    }
    Long thought = number(u, "thoughtsTokenCount");
    return new Counts(
        number(u, "promptTokenCount"),
        sum(number(u, "candidatesTokenCount"), thought),
        number(u, "cachedContentTokenCount"),
        null,
        thought);
  }

  public static void add(
      Map<String, Total> totals,
      String protocol,
      String model,
      String host,
      int status,
      long latency,
      Counts c) {
    String key = protocol + "|" + host + "|" + Rules.clean(model, 120);
    if (!totals.containsKey(key) && totals.size() >= 64)
      totals.remove(totals.keySet().iterator().next());
    var t = totals.computeIfAbsent(key, k -> new Total());
    t.protocol = protocol;
    t.host = host;
    t.model = Rules.clean(model, 120);
    t.requests++;
    if (status < 200 || status >= 300) t.failed++;
    t.latencyMs += Math.max(0, latency);
    if (c.input != null || c.output != null) t.usageReported++;
    if (c.input != null) {
      t.input += c.input;
      t.inputReported++;
    }
    if (c.output != null) {
      t.output += c.output;
      t.outputReported++;
    }
    if (c.cached != null) {
      t.cached += c.cached;
      t.cachedReported++;
    }
    if (c.cacheWrite != null) t.cacheWrite += c.cacheWrite;
    if (c.reasoning != null) {
      t.reasoning += c.reasoning;
      t.reasoningReported++;
    }
  }

  public static String human(Map<String, Total> totals) {
    var b =
        new StringBuilder(
            "模型用量 · 当前世界\n仅统计提供商实际返回的数据，不是账户余额或账单。\n缓存与推理是明细，勿重复加到总token；缺失字段显示未知。\n\n");
    for (var t : totals.values())
      b.append(t.protocol)
          .append(" · ")
          .append(t.model)
          .append(" · ")
          .append(t.host)
          .append("\n请求 ")
          .append(t.requests)
          .append(" / HTTP失败 ")
          .append(t.failed)
          .append(" / 有用量回执 ")
          .append(t.usageReported)
          .append("\n输入 ")
          .append(t.inputReported == 0 ? "未知" : t.input)
          .append(" · 输出 ")
          .append(t.outputReported == 0 ? "未知" : t.output)
          .append("\n缓存读取 ")
          .append(t.cachedReported == 0 ? "未知" : t.cached)
          .append(" · 推理 ")
          .append(t.reasoningReported == 0 ? "未知" : t.reasoning)
          .append("\n累计请求耗时 ")
          .append(t.latencyMs)
          .append(" ms\n\n");
    return b.append("Harness外部推理用量不在本模组API统计内。未设置价格，因此不估算费用。").toString();
  }

  private UsageStats() {}
}
