package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class GiveOutcomeTest {
  @Test
  void parsesSelfTargetedGiveWithDefaults() {
    assertEquals(
        new GiveOutcome.Expected("minecraft:stone", 1),
        GiveOutcome.parse("give @s stone", "Alice"));
    assertEquals(
        new GiveOutcome.Expected("mekanism:crusher", 3),
        GiveOutcome.parse("/give @p mekanism:crusher 3", "Alice"));
    assertEquals(
        new GiveOutcome.Expected("minecraft:diamond", 5),
        GiveOutcome.parse("give Alice diamond 5", "Alice"));
  }

  @Test
  void stripsBracketComponentsAndNormalizesIds() {
    assertEquals(
        new GiveOutcome.Expected("minecraft:stone", 2),
        GiveOutcome.parse("give @s stone[foo=bar] 2", "Alice"));
    assertEquals(
        new GiveOutcome.Expected("mekanism:crusher", 1),
        GiveOutcome.parse("give @s mekanism:crusher", "Alice"));
  }

  @Test
  void rejectsCommandsThisClientCannotVerify() {
    assertNull(GiveOutcome.parse("give @e stone", "Alice"));
    assertNull(GiveOutcome.parse("give Bob stone", "Alice"));
    assertNull(GiveOutcome.parse("give @s stone 0", "Alice"));
    assertNull(GiveOutcome.parse("give @s stone 65", "Alice"));
    assertNull(GiveOutcome.parse("give @s stone many", "Alice"));
    assertNull(GiveOutcome.parse("time set day", "Alice"));
    assertNull(GiveOutcome.parse(null, "Alice"));
  }

  @Test
  void gradesGiveOkWhenTheInventoryRegainsTheExpectedCount() {
    var expected = GiveOutcome.parse("give @s stone 8", "Alice");
    String receipt = GiveOutcome.receipt(expected, evidence(8, 0), 10);
    assertTrue(receipt.startsWith("GIVE_OK(+8)"), receipt);
    assertTrue(receipt.contains("已确认"), receipt);
    assertTrue(receipt.contains("背包 +8"), receipt);
  }

  @Test
  void gradesGroundDropWithoutClaimingTheInventoryGained() {
    var expected = GiveOutcome.parse("give @s stone 8", "Alice");
    String receipt = GiveOutcome.receipt(expected, evidence(0, 8), 0);
    assertTrue(receipt.startsWith("GIVE_DROPPED_ON_GROUND"), receipt);
    assertTrue(receipt.contains("在地上"), receipt);
    assertFalse(receipt.contains("背包 +8"), receipt);
  }

  @Test
  void gradesNoSpacePartialAndUnconfirmedSeparately() {
    var expected = GiveOutcome.parse("give @s stone 8", "Alice");
    assertTrue(GiveOutcome.receipt(expected, evidence(0, 0), 0).startsWith("GIVE_FAILED_NO_SPACE"));
    assertTrue(GiveOutcome.receipt(expected, evidence(0, 0), 20).startsWith("GIVE_UNCONFIRMED"));
    String partial = GiveOutcome.receipt(expected, evidence(3, 0), 20);
    assertTrue(partial.startsWith("GIVE_OK(+3)"), partial);
    assertTrue(partial.contains("部分生效"), partial);
    assertEquals("", GiveOutcome.receipt(null, evidence(0, 0), 0));
  }

  private static JsonObject evidence(int pickedUp, int dropped) {
    var evidence = new JsonObject();
    var inventory = new JsonArray();
    if (pickedUp > 0) {
      var row = new JsonObject();
      row.addProperty("id", "minecraft:stone");
      row.addProperty("delta", pickedUp);
      inventory.add(row);
    }
    evidence.add("inventory", inventory);
    var ground = new JsonArray();
    if (dropped > 0) {
      var row = new JsonObject();
      row.addProperty("id", "minecraft:stone");
      row.addProperty("count", dropped);
      ground.add(row);
    }
    evidence.add("dropped", ground);
    return evidence;
  }
}
