package dev.mibao.companion;

import net.minecraft.client.Minecraft;

/**
 * Prepare one empty hand before opening a machine. All swaps use the existing full-snapshot proof.
 */
public final class HandPreparation {
  private final boolean allowHeld;

  public HandPreparation() {
    allowHeld = false;
  }

  public HandPreparation(net.minecraft.core.BlockPos pos) {
    allowHeld = allowsHeld(pos);
  }

  public static boolean allowsHeld(net.minecraft.core.BlockPos pos) {
    var mc = Minecraft.getInstance();
    String id =
        net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(mc.level.getBlockState(pos).getBlock())
            .toString();
    return Rules.standardStorage(id)
        || id.equals("ae2:cable_bus")
        || java.util.Set.of(
                "minecraft:crafting_table",
                "minecraft:furnace",
                "minecraft:smoker",
                "minecraft:blast_furnace")
            .contains(id);
  }

  private InventoryMove move;
  public String error = "";

  public int tick() {
    var mc = Minecraft.getInstance();
    var p = mc.player;
    if (move != null) {
      int r = move.tick();
      if (r < 0) error = move.error;
      else if (r > 0) p.getInventory().selected = move.readySlot;
      return r;
    }
    if (p.containerMenu != p.inventoryMenu) {
      error = "准备空手前请关闭其他容器";
      return -1;
    }
    if (p.getMainHandItem().isEmpty()) return 1;
    for (int i = 0; i < 9; i++)
      if (p.getInventory().getItem(i).isEmpty()) {
        p.getInventory().selected = i;
        return 1;
      }
    if (allowHeld) return 1;
    move = InventoryMove.emptyHand();
    return 0;
  }

  public void stop() {
    if (move != null) move.stop();
  }
}
