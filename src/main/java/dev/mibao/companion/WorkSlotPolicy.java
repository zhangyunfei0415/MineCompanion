package dev.mibao.companion;

import java.util.*;

/** Only ordinary vanilla supplies may be displaced automatically; never discard a stack. */
public final class WorkSlotPolicy {
  public record Slot(String id, boolean empty, boolean tagged) {}

  private static final Set<String> MATERIALS =
      Set.of(
          "dirt",
          "cobblestone",
          "stone",
          "coal",
          "charcoal",
          "stick",
          "rotten_flesh",
          "torch",
          "sand",
          "gravel",
          "iron_ingot",
          "copper_ingot",
          "gold_ingot",
          "raw_iron",
          "raw_copper",
          "raw_gold",
          "wheat_seeds",
          "beetroot_seeds",
          "carrot",
          "potato",
          "bone_meal",
          "wheat",
          "beetroot",
          "bone",
          "chest");

  public static int choose(List<Slot> slots, int selected) {
    for (int i = 0; i < Math.min(9, slots.size()); i++) if (slots.get(i).empty) return i;
    for (int i = 8; i >= 0; i--) {
      if (i >= slots.size() || i == selected) continue;
      var s = slots.get(i);
      if (s.tagged || !s.id.startsWith("minecraft:")) continue;
      String id = s.id.substring(10);
      if (MATERIALS.contains(id)
          || id.endsWith("_planks")
          || id.endsWith("_log")
          || id.endsWith("_sapling")) return i;
    }
    return -1;
  }
}
