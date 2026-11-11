package dev.mibao.companion;

import java.util.*;
import java.util.function.Predicate;

/** Only verified increases may be delivered; pre-existing and unrelated variants stay carried. */
public final class GatheringCargo {
  private static final Map<String, String> DROPS =
      Map.ofEntries(
          Map.entry("stone", "cobblestone"),
          Map.entry("deepslate", "cobbled_deepslate"),
          Map.entry("coal_ore", "coal"),
          Map.entry("iron_ore", "raw_iron"),
          Map.entry("copper_ore", "raw_copper"),
          Map.entry("gold_ore", "raw_gold"),
          Map.entry("diamond_ore", "diamond"),
          Map.entry("emerald_ore", "emerald"),
          Map.entry("redstone_ore", "redstone"),
          Map.entry("lapis_ore", "lapis_lazuli"),
          Map.entry("nether_quartz_ore", "quartz"),
          Map.entry("nether_gold_ore", "gold_nugget"));

  public static String commonDrop(String block) {
    if (!block.startsWith("minecraft:")) return "";
    String id = block.substring(10);
    if (id.startsWith("deepslate_") && id.endsWith("_ore")) id = id.substring(10);
    if (DROPS.containsKey(id)) return "minecraft:" + DROPS.get(id);
    return Set.of(
                    "dirt",
                    "cobblestone",
                    "cobbled_deepslate",
                    "sand",
                    "gravel",
                    "netherrack",
                    "end_stone")
                .contains(id)
            || id.endsWith("_log")
            || id.endsWith("_stem")
        ? block
        : "";
  }

  public static boolean allowed(String filter, boolean wood, String fingerprint) {
    String id = fingerprint.split("#", 2)[0];
    return wood
        ? id.startsWith("minecraft:")
            && (id.endsWith("_log")
                || id.endsWith("_stem")
                || id.endsWith("_wood")
                || id.endsWith("_hyphae"))
        : !commonDrop(filter).isEmpty() && id.equals(commonDrop(filter));
  }

  public static Map<String, Integer> available(
      Map<String, Integer> baseline, Map<String, Integer> inventory, Predicate<String> allowed) {
    Map<String, Integer> out = new TreeMap<>();
    for (var e : inventory.entrySet()) {
      int n = e.getValue() - baseline.getOrDefault(e.getKey(), 0);
      if (n > 0 && allowed.test(e.getKey())) out.put(e.getKey(), n);
    }
    return out;
  }

  public static Map<String, Integer> includeDelivered(
      Map<String, Integer> inventory, Map<String, Integer> delivered) {
    Map<String, Integer> out = new HashMap<>(inventory);
    delivered.forEach(
        (fp, n) -> {
          if (n < 0) throw new IllegalArgumentException("交付数量不能为负");
          out.merge(fp, n, Integer::sum);
        });
    return out;
  }
}
