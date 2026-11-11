package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class ApiWireTest {
  private JsonObject response(String message) {
    return JsonParser.parseString("{'choices':[{'message':" + message + "}],'usage':null}")
        .getAsJsonObject();
  }

  @Test
  void baseV1AndFullEndpointsAreNotDuplicated() {
    assertEquals(
        "https://api.deepseek.com/chat/completions",
        ApiWire.endpoint("https://api.deepseek.com/").toString());
    assertEquals(
        "https://host.example/v1/chat/completions",
        ApiWire.endpoint("https://host.example/v1/").toString());
    assertEquals(
        "https://host.example/v1/chat/completions",
        ApiWire.endpoint("https://host.example/v1/chat/completions/").toString());
  }

  @Test
  void localCompatibilityAndQueryArePreserved() {
    assertEquals(
        "http://127.0.0.1:1234/v1/chat/completions?version=1",
        ApiWire.endpoint("http://127.0.0.1:1234/v1?version=1").toString());
  }

  @Test
  void nativeProtocolsAndUnsafeSchemesHaveExplicitBoundaries() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ApiWire.endpoint("https://host.example/v1/responses"));
    assertThrows(
        IllegalArgumentException.class, () -> ApiWire.endpoint("https://host.example/v1/messages"));
    assertThrows(IllegalArgumentException.class, () -> ApiWire.endpoint("http://host.example/v1"));
    assertThrows(IllegalArgumentException.class, () -> ApiWire.endpoint("ftp://localhost/v1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ApiWire.endpoint("https://user:secret@host.example/v1"));
  }

  @Test
  void textBlocksAndNullUsageDoNotBreakReplies() {
    var r = response("{'role':'assistant','content':[{'type':'text','text':'完成说明'}]}");
    assertEquals("完成说明\n", ApiWire.message(r).get("content").getAsString());
  }

  @Test
  void objectArgumentsAreNormalizedBeforeAnyAction() {
    var r =
        response(
            "{'role':'assistant','content':null,'tool_calls':[{'id':'a','type':'function','function':{'name':'observe','arguments':{}}}]}");
    assertEquals(
        "{}",
        ApiWire.message(r)
            .getAsJsonArray("tool_calls")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("function")
            .get("arguments")
            .getAsString());
    assertTrue(
        r.getAsJsonArray("choices")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("message")
            .getAsJsonArray("tool_calls")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("function")
            .get("arguments")
            .isJsonObject());
  }

  @Test
  void duplicateIdsAndMalformedArgumentsRejectEntireBatch() {
    var r =
        response(
            "{'tool_calls':[{'id':'a','type':'function','function':{'name':'observe','arguments':{}}},{'id':'a','type':'function','function':{'name':'observe','arguments':{}}}]}");
    assertThrows(IllegalArgumentException.class, () -> ApiWire.message(r));
    var bad =
        response(
            "{'tool_calls':[{'id':'a','type':'function','function':{'name':'observe','arguments':[1]}}]}");
    assertThrows(IllegalArgumentException.class, () -> ApiWire.message(bad));
  }

  @Test
  void missingResultsAndReasoningAloneCannotClaimCompletion() {
    assertThrows(IllegalArgumentException.class, () -> ApiWire.message(new JsonObject()));
    assertThrows(
        IllegalArgumentException.class,
        () -> ApiWire.message(response("{'content':null,'reasoning_content':'still thinking'}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApiWire.message(
                JsonParser.parseString("{'error':{'code':'unsupported'}}").getAsJsonObject()));
  }

  @Test
  void ordinaryToolStringsAndProviderReasoningSurvive() {
    var r =
        response(
            "{'role':'assistant','reasoning_content':'provider-required','tool_calls':[{'id':'a','type':'function','function':{'name':'observe','arguments':'{}'}}]}");
    assertEquals("provider-required", ApiWire.message(r).get("reasoning_content").getAsString());
  }
}
