package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class AePatternProofTest {
  private JsonObject draft() {
    return JsonParser.parseString(
            "{\"mode\":\"CRAFTING\",\"slots\":[{\"role\":\"CRAFTING_GRID\",\"active\":true,\"item\":{\"count\":1,\"fingerprint\":\"log#plain\"}},{\"role\":\"CRAFTING_RESULT\",\"active\":true,\"item\":{\"count\":4,\"fingerprint\":\"planks#plain\"}}]}")
        .getAsJsonObject();
  }

  private JsonObject decoded() {
    return JsonParser.parseString(
            "{\"decodable\":true,\"inputs\":[{\"fingerprint\":\"log#plain\",\"amount\":1}],\"outputs\":[{\"fingerprint\":\"planks#plain\",\"amount\":4}]}")
        .getAsJsonObject();
  }

  @Test
  void exactInputsAndYieldAreRequired() {
    assertTrue(AePatternProof.matches(draft(), decoded()));
    var wrong = decoded();
    wrong.getAsJsonArray("outputs").get(0).getAsJsonObject().addProperty("amount", 1);
    assertFalse(AePatternProof.matches(draft(), wrong));
  }

  @Test
  void sameIdWithDifferentNbtIsNotTheSameMaterial() {
    var wrong = decoded();
    wrong.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("fingerprint", "log#nbtB");
    assertFalse(AePatternProof.matches(draft(), wrong));
  }

  @Test
  void unknownFluidKeyAndUnparseablePatternNeverPass() {
    var wrong = decoded();
    wrong.getAsJsonArray("inputs").get(0).getAsJsonObject().remove("fingerprint");
    assertFalse(AePatternProof.matches(draft(), wrong));
    wrong = decoded();
    wrong.addProperty("decodable", false);
    assertFalse(AePatternProof.matches(draft(), wrong));
  }

  @Test
  void processingAmountsAreNotReducedToOne() {
    var draft = draft();
    draft.addProperty("mode", "PROCESSING");
    var input = draft.getAsJsonArray("slots").get(0).getAsJsonObject();
    input.addProperty("role", "PROCESSING_INPUTS");
    input.getAsJsonObject("item").addProperty("count", 8);
    draft
        .getAsJsonArray("slots")
        .get(1)
        .getAsJsonObject()
        .addProperty("role", "PROCESSING_OUTPUTS");
    assertFalse(AePatternProof.matches(draft, decoded()));
    var pattern = decoded();
    pattern.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("amount", 8);
    assertTrue(AePatternProof.matches(draft, pattern));
  }
}
