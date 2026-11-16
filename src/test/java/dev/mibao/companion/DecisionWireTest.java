package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DecisionWireTest {
  private JsonObject json(String s) {
    return JsonParser.parseString(s).getAsJsonObject();
  }

  @Test
  void localAndCloudRemainIndependent() {
    var c = new Store.Config();
    c.decisionMode = "LOCAL";
    c.decisionLocalModel = "local-model";
    assertEquals("local-model", DecisionWire.profile(c).model());
    c.decisionMode = "CLOUD";
    assertEquals("gemini-decision-1", DecisionWire.profile(c).model());
  }

  @Test
  void localRejectsRemoteAndDisabledRejectsRequests() {
    var c = new Store.Config();
    assertThrows(IllegalArgumentException.class, () -> DecisionWire.profile(c));
    c.decisionMode = "LOCAL";
    c.decisionLocalEndpoint = "https://example.com/decide";
    c.decisionLocalModel = "x";
    assertThrows(IllegalArgumentException.class, () -> DecisionWire.profile(c));
  }

  @Test
  void nativeProtocolsHaveDifferentStateFields() {
    var candidates =
        JsonParser.parseString("[{\"id\":\"a\",\"description\":\"collect\"},{\"id\":\"b\"}]")
            .getAsJsonArray();
    var google = new DecisionWire.Profile("GOOGLE", "", "g", "");
    var jev = new DecisionWire.Profile("JEV", "", "j", "");
    assertTrue(DecisionWire.request(google, json("{}"), candidates).has("input"));
    assertTrue(DecisionWire.request(jev, json("{}"), candidates).has("state"));
    candidates.add(candidates.get(0));
    assertThrows(
        IllegalArgumentException.class, () -> DecisionWire.request(jev, json("{}"), candidates));
  }

  @Test
  void untrustedChoicesCannotCreateActions() {
    var p = new DecisionWire.Profile("JEV", "", "", "");
    var response = json("{\"answers\":{\"next_action\":{\"choice\":\"unknown\"}}}");
    assertThrows(
        IllegalArgumentException.class, () -> DecisionWire.answer(p, response, Set.of("a")));
  }

  @Test
  void structuredTimeoutSurvivesDiagnosticEnrichment() {
    var reason =
        ReasonCodes.describe(
            "{\"reason_code\":\"TIME_BUDGET\",\"status\":\"partial\",\"detail\":\"背包还有空位\"}");
    assertEquals("TIME_BUDGET", reason.get("reason_code").getAsString());
  }

  @Test
  void openaiContentIsParsedWithoutClaimingConfidence() {
    var p = new DecisionWire.Profile("OPENAI", "", "", "");
    var response =
        json("{\"choices\":[{\"message\":{\"content\":\"{\\\"choice\\\":\\\"a\\\"}\"}}]}");
    var result = DecisionWire.answer(p, response, Set.of("a"));
    assertEquals("a", result.get("choice").getAsString());
    assertFalse(result.has("confidence"));
  }

  @Test
  void fullControlAndExplicitDetourHaveClearPrecedence() {
    var c = new Store.Config();
    c.permission = "FULL";
    assertTrue(NavigationPermissions.canBreak(c));
    assertTrue(NavigationPermissions.canPlace(c));
    c.navigationDetourOnly = true;
    assertFalse(NavigationPermissions.canBreak(c));
    assertFalse(NavigationPermissions.canPlace(c));
    c.navigationDetourOnly = false;
    c.permission = "LIMITED";
    c.navigationBreak = true;
    assertFalse(NavigationPermissions.canBreak(c));
    c.allowMine = true;
    assertTrue(NavigationPermissions.canBreak(c));
  }
}
