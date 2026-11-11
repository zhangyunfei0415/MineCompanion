package dev.mibao.companion;

import com.google.gson.*;
import java.net.URI;
import java.util.*;

/**
 * Four explicit wire protocols, one validated game-tool contract. Native signatures stay in memory.
 */
public final class ProviderWire {
  public static final List<String> PROTOCOLS = List.of("CHAT", "RESPONSES", "ANTHROPIC", "GEMINI");

  public static String protocol(String value) {
    if (value == null || value.isBlank()) return "CHAT";
    if (!PROTOCOLS.contains(value))
      throw new IllegalArgumentException("协议须CHAT/RESPONSES/ANTHROPIC/GEMINI");
    return value;
  }

  public static URI endpoint(String address, String model, String protocol) {
    protocol = protocol(protocol);
    if (protocol.equals("CHAT")) return ApiWire.endpoint(address);
    URI uri = URI.create(address.trim());
    ApiWire.validate(uri);
    String path = Optional.ofNullable(uri.getPath()).orElse("").replaceAll("/+$", "");
    if (path.endsWith("/chat/completions"))
      throw new IllegalArgumentException("当前选择原生协议，请填写对应基础地址，不能使用/chat/completions");
    if (protocol.equals("GEMINI")) {
      if (path.endsWith("/responses") || path.endsWith("/messages"))
        throw new IllegalArgumentException("Gemini地址与所选协议不符");
      String name = model.startsWith("models/") ? model.substring(7) : model;
      if (!name.matches("[a-zA-Z0-9._-]{1,160}"))
        throw new IllegalArgumentException("Gemini模型名格式错误");
      if (!path.endsWith(":generateContent")) {
        if (path.isEmpty()) path = "/v1beta";
        if (!path.endsWith("/models")) path += "/models";
        path += "/" + name + ":generateContent";
      }
    } else {
      String suffix = protocol.equals("RESPONSES") ? "/responses" : "/messages";
      String other = protocol.equals("RESPONSES") ? "/messages" : "/responses";
      if (path.endsWith(other) || path.endsWith(":generateContent"))
        throw new IllegalArgumentException("API地址与所选协议不符");
      if (path.isEmpty()) path = "/v1";
      if (!path.endsWith(suffix)) path += suffix;
    }
    try {
      return new URI(
          uri.getScheme(), null, uri.getHost(), uri.getPort(), path, uri.getQuery(), null);
    } catch (Exception e) {
      throw new IllegalArgumentException("API地址格式错误");
    }
  }

  public static Map<String, String> headers(String protocol, String key) {
    return switch (protocol(protocol)) {
      case "ANTHROPIC" -> Map.of("x-api-key", key, "anthropic-version", "2023-06-01");
      case "GEMINI" -> Map.of("x-goog-api-key", key);
      default -> Map.of("Authorization", "Bearer " + key);
    };
  }

  public static JsonObject request(String protocol, JsonObject canonical) {
    return switch (protocol(protocol)) {
      case "RESPONSES" -> responsesRequest(canonical);
      case "ANTHROPIC" -> anthropicRequest(canonical);
      case "GEMINI" -> geminiRequest(canonical);
      default -> canonical.deepCopy();
    };
  }

  private static JsonObject base(JsonObject c) {
    var o = new JsonObject();
    o.add("model", c.get("model").deepCopy());
    return o;
  }

  private static String str(JsonObject o, String k) {
    var e = o.get(k);
    return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
  }

  private static JsonObject obj(String type) {
    var o = new JsonObject();
    o.addProperty("type", type);
    return o;
  }

  private static JsonObject text(String type, String value) {
    var o = obj(type);
    o.addProperty("text", value);
    return o;
  }

  private static JsonArray parts(JsonElement content, String protocol) {
    var out = new JsonArray();
    if (content == null || content.isJsonNull()) return out;
    if (content.isJsonPrimitive()) {
      if (!content.getAsString().isBlank())
        out.add(
            protocol.equals("GEMINI")
                ? geminiText(content.getAsString())
                : text(
                    protocol.equals("RESPONSES") ? "input_text" : "text", content.getAsString()));
      return out;
    }
    for (var el : content.getAsJsonArray()) {
      var p = el.getAsJsonObject();
      String type = str(p, "type");
      if (type.equals("text"))
        out.add(
            protocol.equals("GEMINI")
                ? geminiText(str(p, "text"))
                : text(protocol.equals("RESPONSES") ? "input_text" : "text", str(p, "text")));
      else if (type.equals("image_url")) {
        String url = p.getAsJsonObject("image_url").get("url").getAsString();
        if (protocol.equals("RESPONSES")) {
          var image = obj("input_image");
          image.addProperty("image_url", url);
          out.add(image);
        } else {
          if (!url.startsWith("data:image/") || !url.contains(";base64,"))
            throw new IllegalArgumentException("原生图像通道仅接收本地PNG/JPEG截图");
          int split = url.indexOf(";base64,");
          String mime = url.substring(5, split), data = url.substring(split + 8);
          var source = new JsonObject();
          if (protocol.equals("ANTHROPIC")) {
            source.addProperty("type", "base64");
            source.addProperty("media_type", mime);
            source.addProperty("data", data);
            var image = obj("image");
            image.add("source", source);
            out.add(image);
          } else {
            source.addProperty("mimeType", mime);
            source.addProperty("data", data);
            var image = new JsonObject();
            image.add("inlineData", source);
            out.add(image);
          }
        }
      }
    }
    return out;
  }

  private static JsonObject geminiText(String t) {
    var o = new JsonObject();
    o.addProperty("text", t);
    return o;
  }

  private static JsonObject responsesRequest(JsonObject c) {
    var o = base(c);
    o.addProperty("store", false);
    o.addProperty("stream", false);
    o.add("max_output_tokens", c.get("max_tokens").deepCopy());
    var include = new JsonArray();
    include.add("reasoning.encrypted_content");
    o.add("include", include);
    var input = new JsonArray();
    for (var el : c.getAsJsonArray("messages")) {
      var m = el.getAsJsonObject();
      String role = str(m, "role");
      if (role.equals("assistant") && m.has("_native_output")) {
        m.getAsJsonArray("_native_output").forEach(v -> input.add(v.deepCopy()));
        continue;
      }
      if (role.equals("tool")) {
        var r = obj("function_call_output");
        r.addProperty("call_id", str(m, "tool_call_id"));
        r.addProperty("output", str(m, "content"));
        input.add(r);
        continue;
      }
      var msg = new JsonObject();
      msg.addProperty("role", role);
      msg.add(
          "content",
          role.equals("assistant")
              ? m.get("content").deepCopy()
              : parts(m.get("content"), "RESPONSES"));
      input.add(msg);
    }
    o.add("input", input);
    var tools = new JsonArray();
    for (var el : c.getAsJsonArray("tools")) {
      var f = el.getAsJsonObject().getAsJsonObject("function").deepCopy();
      f.addProperty("type", "function");
      f.addProperty("strict", false);
      tools.add(f);
    }
    o.add("tools", tools);
    o.addProperty("tool_choice", "auto");
    return o;
  }

  private static void message(JsonArray messages, String role, JsonArray content, String field) {
    if (content.isEmpty()) return;
    if (!messages.isEmpty()) {
      var last = messages.get(messages.size() - 1).getAsJsonObject();
      if (role.equals(str(last, "role"))) {
        content.forEach(v -> last.getAsJsonArray(field).add(v.deepCopy()));
        return;
      }
    }
    var m = new JsonObject();
    m.addProperty("role", role);
    m.add(field, content);
    messages.add(m);
  }

  private static JsonObject anthropicRequest(JsonObject c) {
    var o = base(c);
    o.add("max_tokens", c.get("max_tokens").deepCopy());
    o.addProperty("stream", false);
    var messages = new JsonArray();
    var system = new StringBuilder();
    for (var el : c.getAsJsonArray("messages")) {
      var m = el.getAsJsonObject();
      String role = str(m, "role");
      if (role.equals("system")) {
        system.append(str(m, "content")).append('\n');
        continue;
      }
      if (role.equals("tool")) {
        var p = obj("tool_result");
        p.addProperty("tool_use_id", str(m, "tool_call_id"));
        p.addProperty("content", str(m, "content"));
        p.addProperty("is_error", Rules.actionFailed(str(m, "content")));
        var a = new JsonArray();
        a.add(p);
        message(messages, "user", a, "content");
      } else if (role.equals("assistant") && m.has("_native_blocks"))
        message(messages, "assistant", m.getAsJsonArray("_native_blocks").deepCopy(), "content");
      else message(messages, role, parts(m.get("content"), "ANTHROPIC"), "content");
    }
    o.addProperty("system", system.toString());
    o.add("messages", messages);
    var tools = new JsonArray();
    for (var el : c.getAsJsonArray("tools")) {
      var f = el.getAsJsonObject().getAsJsonObject("function");
      var tool = new JsonObject();
      tool.add("name", f.get("name").deepCopy());
      tool.add("description", f.get("description").deepCopy());
      tool.add("input_schema", f.get("parameters").deepCopy());
      tools.add(tool);
    }
    o.add("tools", tools);
    return o;
  }

  private static JsonObject geminiRequest(JsonObject c) {
    var o = new JsonObject();
    var contents = new JsonArray();
    var system = new JsonArray();
    var calls = new HashMap<String, JsonObject>();
    for (var el : c.getAsJsonArray("messages")) {
      var m = el.getAsJsonObject();
      String role = str(m, "role");
      if (role.equals("system")) {
        system.add(geminiText(str(m, "content")));
        continue;
      }
      if (role.equals("assistant") && m.has("_native_content")) {
        var nativeContent = m.getAsJsonObject("_native_content").deepCopy();
        contents.add(nativeContent);
        if (m.has("tool_calls")) {
          int i = 0;
          var nativeCalls = new ArrayList<JsonObject>();
          for (var p : nativeContent.getAsJsonArray("parts"))
            if (p.getAsJsonObject().has("functionCall"))
              nativeCalls.add(p.getAsJsonObject().getAsJsonObject("functionCall"));
          for (var call : m.getAsJsonArray("tool_calls"))
            calls.put(str(call.getAsJsonObject(), "id"), nativeCalls.get(i++));
        }
        continue;
      }
      if (role.equals("tool")) {
        var call = calls.get(str(m, "tool_call_id"));
        if (call == null) throw new IllegalArgumentException("Gemini工具结果没有对应调用");
        var response = new JsonObject();
        response.addProperty("name", str(call, "name"));
        if (call.has("id")) response.add("id", call.get("id").deepCopy());
        var result = new JsonObject();
        result.addProperty(
            Rules.actionFailed(str(m, "content")) ? "error" : "result", str(m, "content"));
        response.add("response", result);
        var part = new JsonObject();
        part.add("functionResponse", response);
        var a = new JsonArray();
        a.add(part);
        message(contents, "user", a, "parts");
      } else
        message(
            contents,
            role.equals("assistant") ? "model" : "user",
            parts(m.get("content"), "GEMINI"),
            "parts");
    }
    var sys = new JsonObject();
    sys.add("parts", system);
    o.add("systemInstruction", sys);
    o.add("contents", contents);
    var config = new JsonObject();
    config.add("maxOutputTokens", c.get("max_tokens").deepCopy());
    o.add("generationConfig", config);
    var declarations = new JsonArray();
    for (var el : c.getAsJsonArray("tools")) {
      var f = el.getAsJsonObject().getAsJsonObject("function");
      var d = new JsonObject();
      d.add("name", f.get("name").deepCopy());
      d.add("description", f.get("description").deepCopy());
      d.add("parametersJsonSchema", f.get("parameters").deepCopy());
      declarations.add(d);
    }
    var tool = new JsonObject();
    tool.add("functionDeclarations", declarations);
    var tools = new JsonArray();
    tools.add(tool);
    o.add("tools", tools);
    return o;
  }

  public static JsonObject response(String protocol, JsonObject response) {
    protocol = protocol(protocol);
    if (protocol.equals("CHAT")) return ApiWire.message(response);
    if (response.has("error") && !response.get("error").isJsonNull())
      throw new IllegalArgumentException("原生接口返回错误，未执行工具");
    var msg = new JsonObject();
    msg.addProperty("role", "assistant");
    var text = new StringBuilder();
    var calls = new JsonArray();
    if (protocol.equals("RESPONSES")) {
      if (!str(response, "status").equals("completed"))
        throw new IllegalArgumentException("Responses尚未完整完成，整批工具不执行");
      var output = response.getAsJsonArray("output");
      if (output == null) throw new IllegalArgumentException("Responses缺少output");
      for (var el : output) {
        var p = el.getAsJsonObject();
        switch (str(p, "type")) {
          case "function_call" ->
              calls.add(call(str(p, "call_id"), str(p, "name"), p.get("arguments")));
          case "message" -> {
            for (var part : p.getAsJsonArray("content")) {
              var t = part.getAsJsonObject();
              if (str(t, "type").equals("output_text")) text.append(str(t, "text"));
            }
          }
          case "reasoning" -> {}
          default -> throw new IllegalArgumentException("Responses返回未授权的内置工具/输出类型");
        }
      }
      msg.add("_native_output", output.deepCopy());
    } else if (protocol.equals("ANTHROPIC")) {
      if (!Set.of("end_turn", "tool_use", "stop_sequence").contains(str(response, "stop_reason")))
        throw new IllegalArgumentException("Messages未完整完成或暂停，整批工具不执行");
      var blocks = response.getAsJsonArray("content");
      for (var el : blocks) {
        var p = el.getAsJsonObject();
        switch (str(p, "type")) {
          case "text" -> text.append(str(p, "text"));
          case "tool_use" -> calls.add(call(str(p, "id"), str(p, "name"), p.get("input")));
          case "thinking", "redacted_thinking" -> {}
          default -> throw new IllegalArgumentException("Messages返回未授权的服务端工具块");
        }
      }
      msg.add("_native_blocks", blocks.deepCopy());
    } else {
      var candidates = response.getAsJsonArray("candidates");
      if (candidates == null || candidates.isEmpty())
        throw new IllegalArgumentException("Gemini没有候选回复，可能被阻止");
      var candidate = candidates.get(0).getAsJsonObject();
      if (!str(candidate, "finishReason").equals("STOP"))
        throw new IllegalArgumentException("Gemini未正常完成，整批工具不执行");
      var content = candidate.getAsJsonObject("content");
      int i = 0;
      for (var el : content.getAsJsonArray("parts")) {
        var p = el.getAsJsonObject();
        if (p.has("functionCall")) {
          var f = p.getAsJsonObject("functionCall");
          String id = str(f, "id");
          calls.add(
              call(
                  id.isBlank() ? "gemini_" + UUID.randomUUID() + "_" + (i++) : id,
                  str(f, "name"),
                  f.has("args") ? f.get("args") : new JsonObject()));
        } else if (p.has("text") && (!p.has("thought") || !p.get("thought").getAsBoolean()))
          text.append(str(p, "text"));
      }
      msg.add("_native_content", content.deepCopy());
    }
    msg.addProperty("content", text.toString());
    if (!calls.isEmpty()) msg.add("tool_calls", calls);
    var choice = new JsonObject();
    choice.add("message", msg);
    var choices = new JsonArray();
    choices.add(choice);
    var envelope = new JsonObject();
    envelope.add("choices", choices);
    return ApiWire.message(envelope);
  }

  private static JsonObject call(String id, String name, JsonElement arguments) {
    var c = obj("function");
    c.addProperty("id", id);
    var f = new JsonObject();
    f.addProperty("name", name);
    f.add("arguments", arguments == null ? new JsonObject() : arguments.deepCopy());
    c.add("function", f);
    return c;
  }

  public static JsonObject textOnly(JsonObject body) {
    return stripImages(body).getAsJsonObject();
  }

  private static JsonElement stripImages(JsonElement value) {
    if (value.isJsonArray()) {
      var a = new JsonArray();
      for (var el : value.getAsJsonArray()) {
        if (el.isJsonObject()) {
          var p = el.getAsJsonObject();
          if (Set.of("image", "image_url", "input_image").contains(str(p, "type"))
              || p.has("inlineData")) continue;
        }
        a.add(stripImages(el));
      }
      return a;
    }
    if (value.isJsonObject()) {
      var o = new JsonObject();
      for (var e : value.getAsJsonObject().entrySet()) o.add(e.getKey(), stripImages(e.getValue()));
      return o;
    }
    return value.deepCopy();
  }

  private ProviderWire() {}
}
