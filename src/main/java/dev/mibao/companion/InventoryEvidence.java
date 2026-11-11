package dev.mibao.companion;

import java.util.*;
import net.minecraft.world.item.ItemStack;

/** Full menu-0 receipts survive screen changes; no assumption that opening E triggers sync. */
public final class InventoryEvidence {
  public static long fullRevision, vitalsRevision, receivedAt;
  public static int food = -1;
  public static List<ItemStack> slots = List.of();
  public static ItemStack cursor = ItemStack.EMPTY;

  public static void reset() {
    fullRevision = 0;
    vitalsRevision = 0;
    receivedAt = 0;
    food = -1;
    slots = List.of();
    cursor = ItemStack.EMPTY;
  }

  public static void content(int menu, List<ItemStack> values, ItemStack carried) {
    if (menu == 0) {
      slots = values.stream().map(ItemStack::copy).toList();
      cursor = carried.copy();
      fullRevision++;
      receivedAt = System.currentTimeMillis();
    }
  }

  public static void health(int serverFood) {
    food = serverFood;
    vitalsRevision++;
  }
}
