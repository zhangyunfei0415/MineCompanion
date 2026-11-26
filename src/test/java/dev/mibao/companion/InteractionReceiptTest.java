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
    // Inventory delta alone is non-causal (note only), preventing false confirmation near farms/drops.
    assertFalse(InteractionReceipt.confirmed(evidence));
    String summary = InteractionReceipt.summarize(evidence);
    assertTrue(summary.contains("未观察到目标方块/机器变化或目标处新掉落"), summary);
    assertTrue(summary.contains("附注（无因果归因，不作成功依据）：背包 exdeorum:stone_pebble+2"), summary);
  }

  @Test
  void reportsGroundDropsWithoutClaimingTheyWerePickedUp() {
    var linkedEvidence = new JsonObject();
    var linkedDrops = new JsonArray();
    var linkedRow = new JsonObject();
    linkedRow.addProperty("id", "exdeorum:andesite_pebble");
    linkedRow.addProperty("count", 1);
    linkedRow.addProperty("linked", true);
    linkedDrops.add(linkedRow);
    linkedEvidence.add("dropped", linkedDrops);
    assertTrue(InteractionReceipt.confirmed(linkedEvidence));
    String text = InteractionReceipt.summarize(linkedEvidence);
    assertTrue(text.contains("目标处掉落 exdeorum:andesite_pebble×1 在地上"), text);
    assertTrue(text.contains("尚未入包"), text);
    assertFalse(text.contains("已确认：拾取"), text);

    // Unlinked drops further away remain unconfirmed notes only.
    var unlinked = new JsonObject();
    var unlinkedDrops = new JsonArray();
    var unlinkedRow = new JsonObject();
    unlinkedRow.addProperty("id", "exdeorum:andesite_pebble");
    unlinkedRow.addProperty("count", 1);
    unlinkedDrops.add(unlinkedRow);
    unlinked.add("dropped", unlinkedDrops);
    assertFalse(InteractionReceipt.confirmed(unlinked));
    assertTrue(InteractionReceipt.summarize(unlinked).contains("附近掉落 exdeorum:andesite_pebble×1"));
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
    assertEquals("未观察到目标方块/机器变化或目标处新掉落", InteractionReceipt.summarize(new JsonObject()));
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

  @Test
  void separatesAdjacentPlacementFromTargetBlockConfirmation() {
    var evidence = new JsonObject();
    evidence.addProperty("block_position", "-1, -60, 13");
    evidence.addProperty("block_before", "storagedrawers:compacting_drawers_2");
    evidence.addProperty("block_changed", false);
    evidence.addProperty("machine_changed", false);
    evidence.addProperty("adjacent_block_changed", true);
    evidence.addProperty("adjacent_position", "-1, -60, 12");
    evidence.addProperty("adjacent_block_before", "minecraft:air");
    evidence.addProperty("adjacent_block_after", "minecraft:bamboo_block");
    evidence.addProperty("ticks", 40);

    assertFalse(InteractionReceipt.confirmed(evidence));
    String summary = InteractionReceipt.summarize(evidence);
    assertTrue(summary.contains("未观察到目标方块/机器变化"), summary);
    assertTrue(summary.contains("相邻格 -1, -60, 12 变为 minecraft:bamboo_block"), summary);
    String readout = InteractionReceipt.readout(evidence);
    assertTrue(readout.contains("邻格(-1, -60, 12)→minecraft:bamboo_block"), readout);

    var chestErr = ReasonCodes.describe("失败：[PROTECTED_BLOCK_ENTITY] 目标含方块实体");
    assertEquals("PROTECTED_BLOCK_ENTITY", chestErr.get("reason_code").getAsString());
    assertTrue(chestErr.getAsJsonArray("suggested_alternatives").size() > 0);
  }
}
