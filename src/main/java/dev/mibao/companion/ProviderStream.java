package dev.mibao.companion;

import com.google.gson.*;
import java.net.URI;
import java.util.*;
import java.util.function.Consumer;

/** Independently implemented native wire assembly. Only public text is sent to the UI. */
public final class ProviderStream {
  public record Preview(String text, String stage) {}

  private final String protocol;
  private final Consumer<Preview> preview;
  private final StringBuilder text = new StringBuilder();
  private final TreeMap<Integer, JsonObject> items = new TreeMap<>();
  private JsonObject envelope = new JsonObject(), usage = new JsonObject();
  private boolean terminal, finished;
  private String finish = "";
  private long published;
  private String stage = "";

  public ProviderStream(String protocol, Consumer<Preview> preview) {
    this.protocol = ProviderWire.protocol(protocol);
    this.preview = preview;
  }

  public static JsonObject request(String protocol, JsonObject body) {
    var b = body.deepCopy();
    if (!protocol.equals("GEMINI")) b.addProperty("stream", true);
    if (protocol.equals("CHAT")) {
      var options = new JsonObject();
      options.addProperty("include_usage", true);
      b.add("stream_options", options);
    }
    return b;
  }

  public static URI endpoint(String protocol, URI uri) {
    if (!protocol.equals("GEMINI")) return uri;
    try {
      String q = uri.getQuery();
      q = q == null || q.isBlank() ? "alt=sse" : q.replaceAll("(^|&)alt=[^&]*", "") + "&alt=sse";
      return new URI(
          uri.getScheme(),
          null,
          uri.getHost(),
          uri.getPort(),
          uri.getPath().replace(":generateContent", ":streamGenerateContent"),
          q,
          null);
    } catch (Exception e) {
      throw new IllegalArgumentException("Gemini流式地址错误");
    }
  }

  private static String s(JsonObject o, String k) {
    return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
  }

  private static JsonObject o(JsonObject o, String k) {
    return o != null && o.has(k) && o.get(k).isJsonObject()
        ? o.getAsJsonObject(k)
        : new JsonObject();
  }

  private static int index(JsonObject o, String key) {
    return o.has(key) ? o.get(key).getAsInt() : 0;
  }

  private static void append(JsonObject o, String key, String delta) {
    o.addProperty(key, s(o, key) + delta);
  }

  private void note(String delta) {
    if (!delta.isEmpty()) {
      text.append(delta);
      publish("正在生成工作说明");
    }
  }

  private void publish(String next) {
    long now = System.nanoTime();
    if (!stage.equals(next) || now - published >= 50_000_000) {
      stage = next;
      published = now;
      preview.accept(new Preview(text.toString(), next));
    }
  }

  private void tools() {
    publish("正在组装工具参数 · 尚未执行");
  }

  public void accept(SseReader.Event event) {
    if (event.data().equals("[DONE]")) {
      if (protocol.equals("CHAT")) {
        terminal = true;
      }
      return;
    }
    JsonObject j = JsonParser.parseString(event.data()).getAsJsonObject();
    if ((j.has("error") && !j.get("error").isJsonNull()) || s(j, "type").equals("error"))
      throw new IllegalArgumentException("提供商流式错误；未执行本轮工具");
    if (terminal && !(protocol.equals("CHAT") && j.has("usage")))
      throw new IllegalArgumentException("终止事件后出现额外数据");
    switch (protocol) {
      case "CHAT" -> chat(j);
      case "RESPONSES" -> responses(j);
      case "ANTHROPIC" -> anthropic(j);
      case "GEMINI" -> gemini(j);
      default -> throw new IllegalStateException();
    }
    if (text.length() > 100_000 || items.size() > 4096)
      throw new IllegalArgumentException("流式输出超过预算");
  }

  private void chat(JsonObject j) {
    if (j.has("usage") && j.get("usage").isJsonObject())
      usage = j.getAsJsonObject("usage").deepCopy();
    var choices = j.getAsJsonArray("choices");
    if (choices == null) return;
    for (var c : choices) {
      var choice = c.getAsJsonObject();
      if (index(choice, "index") != 0) continue;
      var d = o(choice, "delta");
      note(s(d, "content"));
      if (d.has("tool_calls")) {
        for (var el : d.getAsJsonArray("tool_calls")) {
          var p = el.getAsJsonObject();
          int i = index(p, "index");
          if (i < 0 || i > 15) throw new IllegalArgumentException("工具索引超限");
          var call =
              items.computeIfAbsent(
                  i,
                  k -> {
                    var v = new JsonObject();
                    v.addProperty("type", "function");
                    v.add("function", new JsonObject());
                    return v;
                  });
          append(call, "id", s(p, "id"));
          var f = call.getAsJsonObject("function");
          var delta = o(p, "function");
          append(f, "name", s(delta, "name"));
          append(f, "arguments", s(delta, "arguments"));
          if (s(f, "arguments").length() > 20000) throw new IllegalArgumentException("工具参数过长");
        }
        tools();
      }
      if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
        finish = s(choice, "finish_reason");
        finished = true;
      }
    }
  }

  private void responses(JsonObject j) {
    String type = s(j, "type");
    switch (type) {
      case "response.output_text.delta" -> note(s(j, "delta"));
      case "response.function_call_arguments.delta", "response.output_item.added" -> {
        if (type.endsWith("added") && !s(o(j, "item"), "type").equals("function_call")) return;
        tools();
      }
      case "response.completed" -> {
        envelope = o(j, "response").deepCopy();
        terminal = true;
      }
      case "response.failed", "response.incomplete", "response.error" ->
          throw new IllegalArgumentException("Responses未完整完成；未执行本轮工具");
      default -> {} // encrypted/private reasoning and unknown extensions never become visible text
    }
  }

  private void anthropic(JsonObject j) {
    switch (s(j, "type")) {
      case "message_start" -> {
        envelope = o(j, "message").deepCopy();
        usage = o(envelope, "usage").deepCopy();
      }
      case "content_block_start" -> {
        var block = o(j, "content_block").deepCopy();
        items.put(index(j, "index"), block);
        if (s(block, "type").equals("text")) note(s(block, "text"));
        if (s(block, "type").equals("tool_use")) tools();
      }
      case "content_block_delta" -> {
        var block = items.get(index(j, "index"));
        if (block == null) throw new IllegalArgumentException("Messages块缺少起点");
        var d = o(j, "delta");
        switch (s(d, "type")) {
          case "text_delta" -> {
            append(block, "text", s(d, "text"));
            note(s(d, "text"));
          }
          case "input_json_delta" -> {
            append(block, "_partial", s(d, "partial_json"));
            tools();
          }
          case "thinking_delta" -> append(block, "thinking", s(d, "thinking"));
          case "signature_delta" -> append(block, "signature", s(d, "signature"));
          default -> {}
        }
      }
      case "message_delta" -> {
        var d = o(j, "delta");
        d.entrySet().forEach(e -> envelope.add(e.getKey(), e.getValue().deepCopy()));
        o(j, "usage").entrySet().forEach(e -> usage.add(e.getKey(), e.getValue().deepCopy()));
      }
      case "message_stop" -> terminal = true;
      default -> {}
    }
  }

  private void gemini(JsonObject j) {
    if (j.has("usageMetadata")) usage = j.getAsJsonObject("usageMetadata").deepCopy();
    var a = j.getAsJsonArray("candidates");
    if (a == null) return;
    for (var el : a) {
      var c = el.getAsJsonObject();
      if (index(c, "index") != 0) continue;
      var content = o(c, "content");
      if (content.has("parts"))
        for (var p : content.getAsJsonArray("parts")) {
          var part = p.getAsJsonObject().deepCopy();
          var last = items.isEmpty() ? null : items.lastEntry().getValue();
          if (last != null
              && last.has("text")
              && part.has("text")
              && !last.has("thoughtSignature")
              && !part.has("thoughtSignature")
              && s(last, "thought").equals(s(part, "thought")))
            append(last, "text", s(part, "text"));
          else items.put(items.size(), part);
          if (part.has("text") && (!part.has("thought") || !part.get("thought").getAsBoolean()))
            note(s(part, "text"));
          if (part.has("functionCall")) tools();
        }
      if (c.has("finishReason")) {
        finish = s(c, "finishReason");
        finished = true;
      }
    }
  }

  public JsonObject finish() {
    if (protocol.equals("CHAT")) {
      if (!terminal || !finished || !Set.of("stop", "tool_calls").contains(finish))
        throw new IllegalArgumentException("Chat流未正常结束；未执行本轮工具");
      var msg = new JsonObject();
      msg.addProperty("role", "assistant");
      msg.addProperty("content", text.toString());
      if (!items.isEmpty()) {
        var calls = new JsonArray();
        items.values().forEach(calls::add);
        msg.add("tool_calls", calls);
      }
      var choice = new JsonObject();
      choice.add("message", msg);
      var choices = new JsonArray();
      choices.add(choice);
      envelope.add("choices", choices);
      envelope.add("usage", usage);
    } else if (protocol.equals("ANTHROPIC")) {
      if (!terminal) throw new IllegalArgumentException("Messages流中断；未执行本轮工具");
      var blocks = new JsonArray();
      for (var b : items.values()) {
        if (b.has("_partial")) {
          b.add("input", JsonParser.parseString(s(b, "_partial")));
          b.remove("_partial");
        }
        blocks.add(b);
      }
      envelope.add("content", blocks);
      envelope.add("usage", usage);
    } else if (protocol.equals("GEMINI")) {
      if (!finished || !finish.equals("STOP"))
        throw new IllegalArgumentException("Gemini流未正常结束；未执行本轮工具");
      var parts = new JsonArray();
      items.values().forEach(parts::add);
      var content = new JsonObject();
      content.addProperty("role", "model");
      content.add("parts", parts);
      var candidate = new JsonObject();
      candidate.add("content", content);
      candidate.addProperty("finishReason", finish);
      var candidates = new JsonArray();
      candidates.add(candidate);
      envelope.add("candidates", candidates);
      envelope.add("usageMetadata", usage);
    } else if (!terminal) throw new IllegalArgumentException("Responses流中断；未执行本轮工具");
    ProviderWire.response(protocol, envelope);
    return envelope;
  }
}
