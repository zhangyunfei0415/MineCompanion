package dev.mibao.companion;

import java.util.*;

/** One source of truth for effective permissions, modifiers and bounded input. */
public final class InputPolicy {
  public static int ticks(int value) {
    if (value < 1 || value > 200)
      throw new IllegalArgumentException("[INVALID_DURATION] 输入时长须为1..200游戏刻");
    return value;
  }

  public static String modifiers(String requested) {
    Set<String> values = new TreeSet<>();
    for (String s : requested.toUpperCase(Locale.ROOT).split("[+,\\s]+")) {
      if (s.isBlank() || s.equals("NONE")) continue;
      if (!Set.of("CTRL", "SHIFT", "ALT").contains(s))
        throw new IllegalArgumentException("[UNSUPPORTED_MODIFIER] 修饰键为CTRL/SHIFT/ALT");
      values.add(s);
    }
    return String.join("+", values);
  }

  public static Set<Integer> codes(String modifiers) {
    Set<Integer> out = new HashSet<>();
    for (String s : modifiers.split("\\+"))
      switch (s) {
        case "CTRL" -> out.add(341);
        case "SHIFT" -> out.add(340);
        case "ALT" -> out.add(342);
      }
    return out;
  }

  public static String access(
      String permission, boolean limitedInput, boolean whitelisted, boolean approvedOnce) {
    if (permission.equals("FULL")) return "allowed";
    if (permission.equals("ASK")) return approvedOnce ? "allowed" : "approval_required";
    return limitedInput && whitelisted ? "allowed" : "permission_required";
  }

  public static boolean protectedKey(String name) {
    return name.startsWith("key.logistics_companion.")
        || name.matches(
            "key\\.(attack|use|drop|chat|command|inventory|swapOffhand|hotbar.*|forward|back|left|right|jump|sneak|sprint|screenshot|fullscreen|advancements|playerlist|pickItem|saveToolbarActivator|loadToolbarActivator)");
  }

  public static String knownModifier(String name) {
    return name.matches("key\\.limitless_roles\\.slot[1-6]") ? "CTRL" : "";
  }

  public static boolean conflict(String otherName, String selectedModifier, String otherModifier) {
    if (!selectedModifier.equals(otherModifier)) return false;
    return !(selectedModifier.contains("CTRL") && otherName.startsWith("key.hotbar."));
  }
}
