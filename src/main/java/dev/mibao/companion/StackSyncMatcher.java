package dev.mibao.companion;

import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;

/**
 * Sync-stable ItemStack comparator that tolerates volatile RF/FE/fluid/gas/progress NBT and
 * Forge lazy capability differences during whole-inventory / whole-menu snapshot verification.
 */
public final class StackSyncMatcher {
  private StackSyncMatcher() {}

  public static boolean syncStable(ItemStack a, ItemStack b) {
    if (a == null || b == null) return (a == null || a.isEmpty()) && (b == null || b.isEmpty());
    if (a.isEmpty() && b.isEmpty()) return true;
    if (a.isEmpty() || b.isEmpty()) return false;
    if (a.getItem() != b.getItem() || a.getCount() != b.getCount()) return false;
    if (ItemStack.matches(a, b)) return true;
    CompoundTag ta = stripVolatile(a.getTag(), "");
    CompoundTag tb = stripVolatile(b.getTag(), "");
    return Objects.equals(ta, tb);
  }

  public static boolean isVolatileOnlyMismatch(ItemStack a, ItemStack b) {
    return a != null && b != null && !a.isEmpty() && !b.isEmpty()
        && !ItemStack.matches(a, b)
        && syncStable(a, b);
  }

  public static String itemId(ItemStack stack) {
    if (stack == null || stack.isEmpty()) return "empty";
    return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
  }

  public static String describeDiff(ItemStack server, ItemStack client) {
    boolean se = server == null || server.isEmpty();
    boolean ce = client == null || client.isEmpty();
    if (se && ce) return "matched";
    if (se) return "empty!=" + itemId(client) + "x" + client.getCount();
    if (ce) return itemId(server) + "x" + server.getCount() + "!=empty";
    if (isVolatileOnlyMismatch(server, client)) return itemId(client) + "[volatile_nbt]";
    if (server.getItem() != client.getItem())
      return itemId(server) + "!=" + itemId(client);
    if (server.getCount() != client.getCount())
      return itemId(server) + "(" + server.getCount() + "!=" + client.getCount() + ")";
    return itemId(client) + "[nbt_diff]";
  }

  private static CompoundTag stripVolatile(CompoundTag src, String parentKey) {
    if (src == null || src.isEmpty()) return null;
    CompoundTag out = new CompoundTag();
    for (String key : src.getAllKeys()) {
      if (VolatileTagPolicy.isVolatileKey(parentKey, key)) continue;
      Tag value = src.get(key);
      if (value instanceof CompoundTag child) {
        CompoundTag cleanChild = stripVolatile(child, key);
        if (cleanChild != null && !cleanChild.isEmpty()) out.put(key, cleanChild);
        else if (!("BlockEntityTag".equals(key)
            || "mekData".equals(key)
            || "EntityTag".equals(key)
            || "ForgeCaps".equals(key))) {
          out.put(key, new CompoundTag());
        }
      } else if (value != null) {
        out.put(key, value.copy());
      }
    }
    return out.isEmpty() ? null : out;
  }
}
