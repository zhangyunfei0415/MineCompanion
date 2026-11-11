package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class ProviderWireTest {
  private JsonObject json(String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }

  private JsonObject message(String role, String text) {
    var m = new JsonObject();
    m.addProperty("role", role);
    m.addProperty("content", text);
    return m;
  }

  private JsonObject body() {
    var c = new JsonObject();
    c.addProperty("model", "test-model");
    c.addProperty("max_tokens", 1000);
    c.add("tools", ToolSchemas.all());
    var messages = new JsonArray();
    messages.add(message("system", "rules"));
    messages.add(message("user", "查铁锭"));
    c.add("messages", messages);
    return c;
  }

  private JsonObject result(String id) {
    var m = message("tool", "库存32");
    m.addProperty("tool_call_id", id);
    return m;
  }

  @Test
  void responsesKeepsEncryptedReasoningAndOriginalCallsThroughToolRoundtrip() {
    var raw =
        json(
            "{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"id\":\"rs1\",\"encrypted_content\":\"opaque\",\"summary\":[]},{\"type\":\"function_call\",\"id\":\"fc1\",\"call_id\":\"call1\",\"name\":\"observe\",\"arguments\":\"{}\"}]}");
    var msg = ProviderWire.response("RESPONSES", raw);
    assertEquals(
        "call1", msg.getAsJsonArray("tool_calls").get(0).getAsJsonObject().get("id").getAsString());
    var c = body();
    c.getAsJsonArray("messages").add(msg);
    c.getAsJsonArray("messages").add(result("call1"));
    var request = ProviderWire.request("RESPONSES", c);
    assertFalse(request.get("store").getAsBoolean());
    assertFalse(request.has("messages"));
    assertTrue(request.get("input").toString().contains("encrypted_content"));
    assertTrue(request.get("input").toString().contains("function_call_output"));
    assertFalse(
        request.getAsJsonArray("tools").get(0).getAsJsonObject().get("strict").getAsBoolean());
    assertEquals(raw.getAsJsonArray("output").get(0), request.getAsJsonArray("input").get(2));
  }

  @Test
  void anthropicGroupsParallelToolResultsImmediatelyAfterSignedAssistant() {
    var raw =
        json(
            "{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"opaque\",\"signature\":\"signed\"},{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"observe\",\"input\":{}},{\"type\":\"tool_use\",\"id\":\"b\",\"name\":\"recipe_status\",\"input\":{}}]}");
    var c = body();
    c.getAsJsonArray("messages").add(ProviderWire.response("ANTHROPIC", raw));
    c.getAsJsonArray("messages").add(result("a"));
    c.getAsJsonArray("messages").add(result("b"));
    c.getAsJsonArray("messages").add(message("user", "fresh observation"));
    var r = ProviderWire.request("ANTHROPIC", c);
    var ms = r.getAsJsonArray("messages");
    assertEquals(3, ms.size());
    assertEquals(raw.get("content"), ms.get(1).getAsJsonObject().get("content"));
    var blocks = ms.get(2).getAsJsonObject().getAsJsonArray("content");
    assertEquals("a", blocks.get(0).getAsJsonObject().get("tool_use_id").getAsString());
    assertEquals("b", blocks.get(1).getAsJsonObject().get("tool_use_id").getAsString());
    assertEquals("text", blocks.get(2).getAsJsonObject().get("type").getAsString());
    assertTrue(r.toString().contains("input_schema"));
  }

  @Test
  void geminiPreservesThoughtSignatureAndMatchesFunctionsWithoutInventingNativeIds() {
    var raw =
        json(
            "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"role\":\"model\",\"parts\":[{\"thought\":true,\"text\":\"not"
                + " public\"},{\"functionCall\":{\"name\":\"observe\",\"args\":{}},\"thoughtSignature\":\"signed-by-provider\"}]}}]}");
    var msg = ProviderWire.response("GEMINI", raw);
    assertEquals("", msg.get("content").getAsString());
    String id = msg.getAsJsonArray("tool_calls").get(0).getAsJsonObject().get("id").getAsString();
    var c = body();
    c.getAsJsonArray("messages").add(msg);
    c.getAsJsonArray("messages").add(result(id));
    var r = ProviderWire.request("GEMINI", c);
    var contents = r.getAsJsonArray("contents");
    assertEquals(
        raw.getAsJsonArray("candidates").get(0).getAsJsonObject().get("content"), contents.get(1));
    var response =
        contents
            .get(2)
            .getAsJsonObject()
            .getAsJsonArray("parts")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("functionResponse");
    assertEquals("observe", response.get("name").getAsString());
    assertFalse(response.has("id"));
  }

  @Test
  void nativeGeminiCallIdsRoundtripAndPublicTextExcludesThoughts() {
    var raw =
        json(
            "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"id\":\"native1\",\"name\":\"observe\",\"args\":{}}}]}}]}");
    var c = body();
    c.getAsJsonArray("messages").add(ProviderWire.response("GEMINI", raw));
    c.getAsJsonArray("messages").add(result("native1"));
    assertTrue(ProviderWire.request("GEMINI", c).toString().contains("\"id\":\"native1\""));
  }

  @Test
  void truncatedAndUnknownOrDuplicateCallsNeverBecomeExecutable() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProviderWire.response("RESPONSES", json("{\"status\":\"incomplete\",\"output\":[]}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProviderWire.response(
                "ANTHROPIC", json("{\"stop_reason\":\"max_tokens\",\"content\":[]}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProviderWire.response(
                "GEMINI", json("{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProviderWire.response(
                "RESPONSES",
                json(
                    "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"call_id\":\"x\",\"name\":\"exec_shell\",\"arguments\":\"{}\"}]}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProviderWire.response(
                "ANTHROPIC",
                json(
                    "{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\",\"id\":\"x\",\"name\":\"observe\",\"input\":{}},{\"type\":\"tool_use\",\"id\":\"x\",\"name\":\"observe\",\"input\":{}}]}")));
  }

  @Test
  void nativeImagesConvertAndOneTextFallbackKeepsToolsAndObservation() {
    for (String p : ProviderWire.PROTOCOLS) {
      var c = body();
      var image =
          json(
              "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"inventory32\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,aGVsbG8=\"}}]}");
      c.getAsJsonArray("messages").add(image);
      var r = ProviderWire.request(p, c);
      assertTrue(r.toString().contains("aGVsbG8="));
      var noImage = ProviderWire.textOnly(r);
      assertFalse(noImage.toString().contains("aGVsbG8="));
      assertTrue(noImage.toString().contains("inventory32"));
      assertEquals(r.get("tools"), noImage.get("tools"));
    }
  }

  @Test
  void endpointsAndAuthAreDistinctAndKeepSecretsOutOfUrls() {
    assertEquals(
        "https://api.openai.com/v1/responses",
        ProviderWire.endpoint("https://api.openai.com/v1", "m", "RESPONSES").toString());
    assertEquals(
        "https://api.anthropic.com/v1/messages",
        ProviderWire.endpoint("https://api.anthropic.com", "m", "ANTHROPIC").toString());
    assertEquals(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent",
        ProviderWire.endpoint("https://generativelanguage.googleapis.com", "gemini-test", "GEMINI")
            .toString());
    assertEquals("secret", ProviderWire.headers("GEMINI", "secret").get("x-goog-api-key"));
    assertFalse(ProviderWire.headers("ANTHROPIC", "secret").containsKey("Authorization"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderWire.endpoint("http://remote.example", "m", "RESPONSES"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderWire.endpoint("https://host/v1/messages", "m", "RESPONSES"));
  }
}
