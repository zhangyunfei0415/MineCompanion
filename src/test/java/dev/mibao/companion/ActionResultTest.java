package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ActionResultTest {
  @Test void explicitCodeSurvivesWordsInTheDetail() {
    var result = new ActionResult(ActionResult.Status.unverified, ActionResult.Code.USE_UNCONFIRMED,
        "背包满、菜单改变等文字只用于显示", new JsonObject());
    assertEquals("USE_UNCONFIRMED", ReasonCodes.describe(result.json()).get("reason_code").getAsString());
    assertTrue(Rules.actionFailed(result.json()));
  }

  @Test void verifiedEvidenceIsCopiedAndNeverRequestsReplay() {
    JsonObject facts = new JsonObject();
    facts.addProperty("count_after", 3);
    var parsed = JsonParser.parseString(new ActionResult(ActionResult.Status.verified,
        ActionResult.Code.NONE, "数量已核对", facts).json()).getAsJsonObject();
    facts.addProperty("count_after", 8);
    assertEquals(3, parsed.getAsJsonObject("evidence").get("count_after").getAsInt());
    assertFalse(parsed.get("retryable").getAsBoolean());
    assertFalse(Rules.actionFailed(parsed.toString()));
  }
}
