package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Passive server receipts for client player actions; baseline alone never proves a change. */
public final class PlayerEvidence {
  private static final Map<Integer, ItemStack> inventory = new HashMap<>();
  private static final Set<Integer> receivedSlots = new HashSet<>();

  public static boolean slotConfirmed(int slot) {
    return receivedSlots.contains(slot);
  }

  private static final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
  public static long revision;
  private static ItemStack cursor;

  public static ItemStack inventorySlot(int slot) {
    return inventory.getOrDefault(slot, ItemStack.EMPTY);
  }

  public static boolean cursorConfirmed() {
    return cursor != null
        && ItemStack.matches(cursor, Minecraft.getInstance().player.containerMenu.getCarried());
  }

  public static void cursor(ItemStack value) {
    cursor = value.copy();
    revision++;
  }

  public static void reset() {
    inventory.clear();
    receivedSlots.clear();
    blocks.clear();
    revision = 0;
    cursor = null;
  }

  public static void begin() {
    reset();
    var p = Minecraft.getInstance().player;
    for (int i = 0; i < 36; i++) inventory.put(i, p.getInventory().getItem(i).copy());
  }

  public static void slot(int menuId, int slot, ItemStack stack) {
    var p = Minecraft.getInstance().player;
    if (p == null) return;
    if (menuId == -1 && slot == -1) {
      cursor(stack);
      return;
    }
    if (menuId == -2 && slot >= 0 && slot < 36) {
      inventory.put(slot, stack.copy());
      receivedSlots.add(slot);
      revision++;
      return;
    }
    var menu = menuId == 0 ? p.inventoryMenu : p.containerMenu;
    if (menu.containerId == menuId && slot >= 0 && slot < menu.slots.size()) {
      var s = menu.slots.get(slot);
      int i = s.getContainerSlot();
      if (s.container == p.getInventory() && i < 36) {
        inventory.put(i, stack.copy());
        receivedSlots.add(i);
        revision++;
      }
    }
  }

  public static void content(int menuId, List<ItemStack> stacks) {
    for (int i = 0; i < stacks.size(); i++) slot(menuId, i, stacks.get(i));
  }

  public static void block(BlockPos pos, BlockState state) {
    if (blocks.size() >= 512) blocks.remove(blocks.keySet().iterator().next());
    blocks.put(pos.immutable(), state);
  }

  public static void forget(BlockPos pos) {
    blocks.remove(pos);
  }

  public static BlockState block(BlockPos pos) {
    return blocks.get(pos);
  }

  public static Map<String, Integer> actual(String filter) {
    Map<String, Integer> out = new HashMap<>();
    var p = Minecraft.getInstance().player;
    for (int i = 0; i < 36; i++) add(out, p.getInventory().getItem(i), filter);
    return out;
  }

  public static Map<String, Integer> confirmed(String filter) {
    Map<String, Integer> out = new HashMap<>();
    inventory.values().forEach(s -> add(out, s, filter));
    return out;
  }

  private static void add(Map<String, Integer> out, ItemStack stack, String filter) {
    if (Actions.match(stack, filter))
      out.merge(WorldSense.fingerprint(stack), stack.getCount(), Integer::sum);
  }

  public static int total(Map<String, Integer> map) {
    return map.values().stream().mapToInt(Integer::intValue).sum();
  }
}
