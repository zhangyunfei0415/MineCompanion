package dev.mibao.companion;

import java.util.*;

/** Internal game controls only; no OS keyboard, chat typing or unbounded hold. */
public final class ControlPolicy {
  public static final Set<String> MOTION =
      Set.of("forward", "back", "left", "right", "jump", "sneak", "sprint");
  public static final Set<String> FOOD =
      Set.of(
          "minecraft:bread",
          "minecraft:apple",
          "minecraft:carrot",
          "minecraft:baked_potato",
          "minecraft:cooked_beef",
          "minecraft:cooked_porkchop",
          "minecraft:cooked_chicken",
          "minecraft:cooked_mutton",
          "minecraft:cooked_rabbit",
          "minecraft:cooked_cod",
          "minecraft:cooked_salmon",
          "minecraft:dried_kelp",
          "minecraft:cookie",
          "minecraft:melon_slice",
          "minecraft:beetroot");

  public static int duration(int ticks) {
    return InputPolicy.ticks(ticks);
  }

  public static boolean needsInputPermission(String tool) {
    return Set.of(
            "swim_out",
            "vehicle_control",
            "unstick",
            "look_at",
            "use_hand",
            "fish",
            "illuminate_area",
            "press_control",
            "press_key",
            "gui_button",
            "gui_text",
            "gui_scroll",
            "gui_point")
        .contains(tool);
  }

  public static boolean approved(String name, String allowlist) {
    return name != null
        && !name.startsWith("key.logistics_companion.")
        && allowlist != null
        && Arrays.stream(allowlist.split("[,，\\s]+")).anyMatch(name::equals);
  }

  public static boolean ate(
      int beforeCount,
      int actualCount,
      int serverCount,
      int beforeFood,
      int actualFood,
      int serverFood,
      boolean freshInventory,
      boolean freshVitals) {
    return freshInventory
        && freshVitals
        && actualCount == beforeCount - 1
        && serverCount == actualCount
        && actualFood > beforeFood
        && actualFood == serverFood;
  }
}
