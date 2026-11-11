package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModelContextTest {
  private static JsonObject message(String role, String text) {
    var o = new JsonObject();
    o.addProperty("role", role);
    o.addProperty("content", text);
    return o;
  }

  private static JsonObject assistant(String... ids) {
    var o = message("assistant", "");
    var calls = new JsonArray();
    for (String id : ids) {
      var c = new JsonObject();
      c.addProperty("id", id);
      calls.add(c);
    }
    o.add("tool_calls", calls);
    return o;
  }

  private static JsonObject result(String id, String text) {
    var o = message("tool", text);
    o.addProperty("tool_call_id", id);
    return o;
  }

  @Test
  void removesOldObservationsWithoutMutatingSavedHistory() {
    var history =
        new ArrayList<>(
            List.of(
                message("system", "rules"),
                message("user", "目标：补铁"),
                message("user", "当前真实游戏状态：old"),
                assistant("a"),
                result("a", "fresh")));
    String original = history.toString();
    var out = ModelContext.build(history, 80000);
    assertEquals(4, out.size());
    assertEquals(original, history.toString());
    assertTrue(out.toString().contains("目标：补铁"));
    assertFalse(out.toString().contains("状态：old"));
  }

  @Test
  void trimsCompletedMultiCallGroupsAsAUnitAndKeepsLatestPair() {
    var history =
        List.of(
            message("system", "rules"),
            message("user", "目标"),
            assistant("a", "b"),
            result("a", "x".repeat(3000)),
            result("b", "done"),
            assistant("c"),
            result("c", "latest"));
    var out = ModelContext.build(history, 500);
    assertFalse(out.toString().contains("\"tool_call_id\":\"a\""));
    assertFalse(out.toString().contains("\"tool_call_id\":\"b\""));
    assertTrue(out.toString().contains("\"tool_call_id\":\"c\""));
    assertTrue(out.toString().contains("目标"));
  }

  @Test
  void keepsUnresolvedLatestCallInsteadOfInventingAResult() {
    var out = ModelContext.build(List.of(message("user", "目标"), assistant("pending")), 1);
    assertEquals(2, out.size());
    assertTrue(out.toString().contains("pending"));
  }

  @Test
  void oldToolExcerptsAreMarkedAndLatestFourStayExact() {
    var history = new ArrayList<JsonObject>();
    for (int i = 0; i < 6; i++) {
      history.add(assistant("c" + i));
      history.add(result("c" + i, "x".repeat(2000)));
    }
    var out = ModelContext.build(history, 100000);
    assertTrue(out.toString().contains("历史回执已节选"));
    for (int i = 2; i < 6; i++)
      assertEquals(
          "x".repeat(2000), out.get(i * 2 + 1).getAsJsonObject().get("content").getAsString());
  }

  @Test
  void userCorrectionDoesNotBlockRemovalOfEarlierCompleteGroup() {
    var out =
        ModelContext.build(
            List.of(
                message("user", "先采木"),
                assistant("a"),
                result("a", "x".repeat(2000)),
                message("user", "改为采石"),
                assistant("b"),
                result("b", "ready")),
            500);
    assertTrue(out.toString().contains("改为采石"));
    assertFalse(out.toString().contains("tool_call_id\":\"a"));
  }
}
