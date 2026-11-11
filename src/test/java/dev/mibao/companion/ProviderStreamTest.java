package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProviderStreamTest {
  private static void event(ProviderStream s, String json) {
    s.accept(new SseReader.Event("message", json));
  }

  private static ProviderStream stream(String protocol) {
    return new ProviderStream(protocol, p -> {});
  }

  private static JsonObject json(String s) {
    return JsonParser.parseString(s).getAsJsonObject();
  }

  @Test
  void utf8CrLfCommentsBomAndMultilineSurviveSingleByteReads() throws Exception {
    byte[] bytes =
        ("\ufeff:keepalive\r\nevent: text\r\ndata: 第一行\r\ndata: 第二行\r\n\r\ndata: 未完整帧")
            .getBytes(StandardCharsets.UTF_8);
    var input =
        new FilterInputStream(new ByteArrayInputStream(bytes)) {
          public int read(byte[] b, int o, int l) throws IOException {
            return super.read(b, o, Math.min(l, 1));
          }
        };
    var events = new ArrayList<SseReader.Event>();
    SseReader.read(input, events::add);
    assertEquals(List.of(new SseReader.Event("text", "第一行\n第二行")), events);
  }

  @Test
  void chatJoinsToolFragmentsAndCountsUsageOnce() {
    var previews = new ArrayList<ProviderStream.Preview>();
    var s = new ProviderStream("CHAT", previews::add);
    event(
        s,
        "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"先检查箱子\",\"reasoning_content\":\"PRIVATE\",\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"function\":{\"name\":\"fetch\",\"arguments\":\"{\\\"count\\\":\"}}]}}]}");
    event(
        s,
        "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"32}\"}}]},\"finish_reason\":\"tool_calls\"}]}");
    event(s, "{\"choices\":[],\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":12}}");
    event(s, "[DONE]");
    var envelope = s.finish();
    var msg = ProviderWire.response("CHAT", envelope);
    assertEquals(
        "{\"count\":32}",
        msg.getAsJsonArray("tool_calls")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("function")
            .get("arguments")
            .getAsString());
    assertEquals(50L, UsageStats.parse("CHAT", envelope).input());
    assertTrue(previews.stream().noneMatch(p -> p.text().contains("PRIVATE")));
    assertTrue(previews.stream().anyMatch(p -> p.stage().contains("尚未执行")));
  }

  @Test
  void chatTruncatedOrLengthLimitedStreamNeverSubmitsTools() {
    for (String reason : List.of("tool_calls", "length")) {
      var s = stream("CHAT");
      event(
          s,
          "{\"choices\":[{\"delta\":{\"content\":\"半段\"},\"finish_reason\":\"" + reason + "\"}]}");
      if (reason.equals("length")) event(s, "[DONE]");
      assertThrows(IllegalArgumentException.class, s::finish);
    }
  }

  @Test
  void malformedArgumentsInvalidateWholeBatch() {
    var s = stream("CHAT");
    event(
        s,
        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"x\",\"function\":{\"name\":\"fetch\",\"arguments\":\"{\"}}]},\"finish_reason\":\"tool_calls\"}]}");
    event(s, "[DONE]");
    assertThrows(IllegalArgumentException.class, s::finish);
  }

  @Test
  void responsesNeedsCompletedObjectAndPreservesNativeReasoningWithoutShowingIt() {
    var visible = new ArrayList<ProviderStream.Preview>();
    var s = new ProviderStream("RESPONSES", visible::add);
    event(s, "{\"type\":\"response.output_text.delta\",\"delta\":\"检查材料\"}");
    event(s, "{\"type\":\"response.reasoning_text.delta\",\"delta\":\"PRIVATE\"}");
    assertThrows(IllegalArgumentException.class, s::finish);
    event(
        s,
        "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"encrypted_content\":\"opaque\"},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"检查材料\"}]}],\"usage\":{\"input_tokens\":8,\"output_tokens\":4}}}");
    var msg = ProviderWire.response("RESPONSES", s.finish());
    assertTrue(msg.has("_native_output"));
    assertFalse(visible.toString().contains("PRIVATE"));
    assertEquals("检查材料", msg.get("content").getAsString());
  }

  @Test
  void anthropicToolJsonSignaturesAndUsageMerge() {
    var s = stream("ANTHROPIC");
    event(
        s,
        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}");
    event(
        s,
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}");
    event(
        s,
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"private\"}}");
    event(
        s,
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig\"}}");
    event(
        s,
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool_a\",\"name\":\"fetch\",\"input\":{}}}");
    event(
        s,
        "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"count\\\":32}\"}}");
    event(
        s,
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":9}}");
    event(s, "{\"type\":\"message_stop\"}");
    var response = s.finish();
    assertEquals(
        new UsageStats.Counts(10L, 9L, null, null, null), UsageStats.parse("ANTHROPIC", response));
    var msg = ProviderWire.response("ANTHROPIC", response);
    assertEquals(
        "sig",
        msg.getAsJsonArray("_native_blocks")
            .get(0)
            .getAsJsonObject()
            .get("signature")
            .getAsString());
    assertEquals(1, msg.getAsJsonArray("tool_calls").size());
  }

  @Test
  void geminiManyChunksMergeAndSignatureSurvives() {
    var s = stream("GEMINI");
    for (int i = 0; i < 150; i++)
      event(s, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"米\"}]}}]}");
    event(
        s,
        "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"fetch\",\"args\":{\"count\":32}},\"thoughtSignature\":\"sig\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":20,\"candidatesTokenCount\":8}}");
    var msg = ProviderWire.response("GEMINI", s.finish());
    assertEquals(150, msg.get("content").getAsString().length());
    assertEquals(2, msg.getAsJsonObject("_native_content").getAsJsonArray("parts").size());
    assertTrue(msg.toString().contains("thoughtSignature"));
  }

  @Test
  void errorsAndBlockedFinishNeverLookCompleted() {
    for (String protocol : ProviderWire.PROTOCOLS) {
      var s = stream(protocol);
      assertThrows(
          IllegalArgumentException.class,
          () -> event(s, "{\"error\":{\"message\":\"overloaded\"}}"));
      assertThrows(IllegalArgumentException.class, s::finish);
    }
  }

  @Test
  void nativeStreamingUsesCorrectRouteAndBody() {
    for (String protocol : ProviderWire.PROTOCOLS) {
      var body = ProviderStream.request(protocol, json("{\"model\":\"fixture\"}"));
      assertEquals(!protocol.equals("GEMINI"), body.has("stream"));
    }
    var uri =
        ProviderStream.endpoint(
            "GEMINI", URI.create("https://example.com/v1beta/models/demo:generateContent?x=1"));
    assertEquals("/v1beta/models/demo:streamGenerateContent", uri.getPath());
    assertEquals("x=1&alt=sse", uri.getQuery());
  }

  @Test
  void configuredKeyIsRedactedWithoutLoadingMinecraft() {
    assertEquals("密钥=[隐藏密钥]", TaskTimeline.clean("密钥=fixture-secret", "fixture-secret"));
  }

  @Test
  void mailboxCoalescesWithoutTokenQueue() {
    var mailbox = new StreamMailbox();
    for (int i = 0; i < 10000; i++) mailbox.publish(new ProviderStream.Preview("" + i, "text"));
    assertEquals("9999", mailbox.drain().text());
    assertNull(mailbox.drain());
  }
}
