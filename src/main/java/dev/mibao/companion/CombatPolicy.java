package dev.mibao.companion;

import java.util.Set;

/** Deliberately small player-combat policy; explicit target type, no PvP or bosses. */
public final class CombatPolicy {
  public static final Set<String> HUNTABLE =
      Set.of(
          "minecraft:zombie",
          "minecraft:skeleton",
          "minecraft:spider",
          "minecraft:husk",
          "minecraft:stray",
          "minecraft:drowned",
          "minecraft:cave_spider");
  public static final Set<String> RESOURCES =
      Set.of(
          "minecraft:cow",
          "minecraft:mooshroom",
          "minecraft:pig",
          "minecraft:chicken",
          "minecraft:sheep",
          "minecraft:rabbit",
          "minecraft:squid",
          "minecraft:glow_squid",
          "minecraft:cod",
          "minecraft:salmon",
          "minecraft:tropical_fish",
          "minecraft:pufferfish");

  public static boolean accepted(String type, boolean resources, boolean players) {
    return type.equals("hostile")
        || HUNTABLE.contains(type)
        || resources && RESOURCES.contains(type)
        || players && type.equals("minecraft:player");
  }

  public static boolean deathConfirmed(long attempted, long death) {
    return attempted > 0 && death > attempted;
  }

  public static boolean recent(long now, long receipt) {
    return receipt > 0 && now >= receipt && now - receipt <= 10_000_000_000L;
  }
}
