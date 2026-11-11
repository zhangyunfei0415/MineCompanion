package dev.mibao.companion;

/** Vanilla player inventory indices differ from menu indices; never apply this to machines. */
public final class InventoryLayout {
  public static int menuSlot(int inventory) {
    if (inventory >= 0 && inventory < 9) return 36 + inventory;
    if (inventory >= 9 && inventory < 36) return inventory;
    if (inventory >= 36 && inventory < 40) return 44 - inventory;
    if (inventory == 40) return 45;
    throw new IllegalArgumentException("背包索引须为0..40");
  }

  public static String role(int menu) {
    if (menu == 0) return "craft_result";
    if (menu >= 1 && menu <= 4) return "craft_input";
    return switch (menu) {
      case 5 -> "head";
      case 6 -> "chest";
      case 7 -> "legs";
      case 8 -> "feet";
      case 45 -> "offhand";
      default ->
          menu >= 36 && menu <= 44
              ? "hotbar"
              : menu >= 9 && menu <= 35 ? "main_inventory" : "unknown";
    };
  }

  public static int equipment(String name) {
    return switch (name) {
      case "head" -> 5;
      case "chest" -> 6;
      case "legs" -> 7;
      case "feet" -> 8;
      case "offhand" -> 45;
      default -> throw new IllegalArgumentException("目标为hotbar/offhand/head/chest/legs/feet");
    };
  }
}
