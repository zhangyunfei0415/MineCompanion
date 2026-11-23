package dev.mibao.companion;

import java.util.List;
import java.util.Locale;

/**
 * Pure structural classification for standard mod storage containers (e.g. Refurbished Furniture
 * drawers/cabinets, Quark chests, Backrooms supply lockers). Zero {@code net.minecraft.*} imports.
 */
public final class StorageMenuPolicy {
  private static final List<String> BLOCKED_MENU_KEYWORDS =
      List.of(
          "furnace",
          "smelt",
          "craft",
          "workbench",
          "machine",
          "factory",
          "processor",
          "anvil",
          "enchant",
          "merchant",
          "trade",
          "brew",
          "beacon",
          "loom",
          "grindstone",
          "stonecutter",
          "smithing",
          "cartography",
          "quest",
          "shop",
          "terminal",
          "pattern",
          "upgrade",
          "generator",
          "reactor",
          "crusher",
          "macerator",
          "alloy",
          "compressor",
          "extruder",
          "assembler",
          "enrichment",
          "infuser",
          "chemical");

  private StorageMenuPolicy() {}

  public static boolean isSafeGenericStorageMenu(
      String menuClassName,
      boolean isChestMenuSubclass,
      int containerSlots,
      int playerSlots,
      boolean hasResultOrSpecialSlot,
      boolean hasInteractiveWidgets) {
    if (hasResultOrSpecialSlot || hasInteractiveWidgets) return false;
    if (playerSlots != 36 || containerSlots < 9 || containerSlots > 108) return false;
    String lower = menuClassName == null ? "" : menuClassName.toLowerCase(Locale.ROOT);
    int lastDot = lower.lastIndexOf('.');
    String simple = lastDot >= 0 ? lower.substring(lastDot + 1) : lower;
    for (String bad : BLOCKED_MENU_KEYWORDS) {
      if (simple.contains(bad)) return false;
    }
    return isChestMenuSubclass || (containerSlots % 9 == 0);
  }
}
