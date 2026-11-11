package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class InteractionReceiptTest {
  @Test
  void confirmsPickedUpItemsFromInventoryDelta() {
    var evidence = new JsonObject();
    var inventory = new JsonArray();
    var row = new JsonObject();
    row.addProperty("id", "exdeorum:stone_pebble");
    row.addProperty("delta", 2);
    inventory.add(row);
    evidence.add("inventory", inventory);
    assertTrue(InteractionReceipt.confirmed(evidence));
    assertEquals("已确认：拾取 exdeorum:stone_pebble×2", InteractionReceipt.summarize(evidence));
  }

  @Test
  void reportsGroundDropsWithoutClaimingTheyWerePickedUp() {
    var evidence = new JsonObject();
    var dropped = new JsonArray();
    var row = new JsonObject();
    row.addProperty("id", "exdeorum:andesite_pebble");
    row.addProperty("count", 1);
    dropped.add(row);
    evidence.add("dropped", dropped);
    String text = InteractionReceipt.summarize(evidence);
    assertTrue(text.contains("掉落 exdeorum:andesite_pebble×1 在地上"), text);
    assertTrue(text.contains("尚未入包"), text);
    assertFalse(text.contains("已确认：拾取"), text);
  }

  @Test
  void confirmsBlockAndMachineChanges() {
    var evidence = new JsonObject();
    evidence.addProperty("block_changed", true);
    evidence.addProperty("block_after", "minecraft:farmland");
    evidence.addProperty("machine_changed", true);
    String text = InteractionReceipt.summarize(evidence);
    assertTrue(text.startsWith("已确认："), text);
    assertTrue(text.contains("方块变为 minecraft:farmland"), text);
    assertTrue(text.contains("机器状态变化"), text);
  }

  @Test
  void emptyEvidenceIsExplicitlyUnconfirmed() {
    assertEquals("未观察到背包/掉落物/方块/机器变化", InteractionReceipt.summarize(new JsonObject()));
    assertFalse(InteractionReceipt.confirmed(new JsonObject()));
    assertFalse(InteractionReceipt.confirmed(null));
  }

  @Test
  void readoutDescribesBeforeAfterSamplesForUnconfirmedReceipts() {
    var evidence = new JsonObject();
    evidence.addProperty("block_before", "minecraft:moss_block");
    evidence.addProperty("block_after", "minecraft:moss_block");
    evidence.addProperty("ticks", 40);
    var inventory = new JsonArray();
    var row = new JsonObject();
    row.addProperty("id", "minecraft:bone_meal");
    row.addProperty("delta", -1);
    inventory.add(row);
    evidence.add("inventory", inventory);
    var dropped = new JsonArray();
    var drop = new JsonObject();
    drop.addProperty("id", "minecraft:wheat_seeds");
    drop.addProperty("count", 2);
    dropped.add(drop);
    evidence.add("dropped", dropped);

    String text = InteractionReceipt.readout(evidence);
    assertTrue(text.startsWith("读数="), text);
    assertTrue(text.contains("方块 minecraft:moss_block→minecraft:moss_block"), text);
    assertTrue(text.contains("背包 minecraft:bone_meal-1"), text);
    assertTrue(text.contains("落地 minecraft:wheat_seeds×2"), text);
    assertTrue(text.contains("观测 40 tick"), text);
  }

  @Test
  void readoutStatesWhenNothingWasObserved() {
    var evidence = new JsonObject();
    evidence.addProperty("ticks", 40);
    assertEquals("读数=无变化（观测 40 tick）", InteractionReceipt.readout(evidence));
  }
}
