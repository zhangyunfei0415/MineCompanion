package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SignMemoryTest {
  @Test
  void signLabelSearchRemainsWorldScopedAndIsNotAnInventory() {
    var memory = new WorldMemory();
    memory.observe(
        "overworld", 1, 2, 3, "minecraft:oak_wall_sign", "sign", "告示牌", "block_entity", 10);
    var sign = memory.get(WorldMemory.key("overworld", 1, 2, 3));
    sign.signText = "厨房材料箱";
    assertEquals(1, memory.query("overworld", 0, 0, 0, "厨房材料", "", 10, 0).size());
    assertTrue(memory.query("nether", 0, 0, 0, "厨房材料", "", 10, 0).isEmpty());
    assertNull(sign.inventory);
    assertEquals("", sign.label);
  }

  @Test
  void removedSignIsNotReturnedAsCurrentLabel() {
    var memory = new WorldMemory();
    memory.observe("world", 1, 2, 3, "minecraft:oak_wall_sign", "sign", "告示牌", "block_entity", 10);
    memory.get(WorldMemory.key("world", 1, 2, 3)).signText = "仓库";
    memory.observe("world", 1, 2, 3, "minecraft:air", "air", "空气", "", 20);
    assertTrue(memory.query("world", 0, 0, 0, "仓库", "", 10, 0).isEmpty());
  }
}
