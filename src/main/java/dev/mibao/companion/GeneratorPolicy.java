package dev.mibao.companion;

import java.util.*;

/**
 * Pure safety policy for cobblestone/basalt generators and background auto-clicker/hold actions.
 * Zero {@code net.minecraft.*} dependencies for offline JUnit verification.
 */
public final class GeneratorPolicy {
  public static final Set<String> GENERATOR_BLOCKS =
      Set.of(
          "minecraft:cobblestone",
          "minecraft:stone",
          "minecraft:cobbled_deepslate",
          "minecraft:deepslate",
          "minecraft:basalt",
          "minecraft:smooth_basalt",
          "minecraft:granite",
          "minecraft:diorite",
          "minecraft:andesite",
          "minecraft:obsidian",
          "create:limestone",
          "create:scoria",
          "create:veridium",
          "create:asurine",
          "create:crimsite",
          "create:ochrum");

  public enum AutoMode {
    MINE_HOLD("刷石机/定点连挖"),
    ATTACK_CLICK("刷怪塔/满蓄力连点"),
    USE_HOLD("曲柄/长按右键"),
    USE_CLICK("筛网/定时连点右键");

    public final String label;

    AutoMode(String label) {
      this.label = label;
    }

    public static AutoMode parse(String raw) {
      if (raw == null || raw.isBlank()) return MINE_HOLD;
      return switch (raw.strip().toUpperCase(Locale.ROOT)) {
        case "ATTACK_CLICK", "ATTACK", "MOB", "打怪" -> ATTACK_CLICK;
        case "USE_HOLD", "CRANK", "HOLD_USE", "曲柄" -> USE_HOLD;
        case "USE_CLICK", "SIEVE", "BONEMEAL", "筛网" -> USE_CLICK;
        default -> MINE_HOLD;
      };
    }
  }

  private GeneratorPolicy() {}

  public static boolean allowFluidNeighbor(
      String blockId,
      boolean targeted,
      boolean explicitAllowFluidNeighbor,
      boolean fluidOrFireAbove,
      boolean hasHorizontalFluid,
      boolean playerDryAndSafe) {
    if (!targeted || fluidOrFireAbove || !playerDryAndSafe) return false;
    if (!hasHorizontalFluid) return true;
    if (explicitAllowFluidNeighbor) return true;
    return blockId != null && GENERATOR_BLOCKS.contains(blockId);
  }

  public static int clampIntervalTicks(int ticks) {
    return Math.max(0, Math.min(200, ticks));
  }

  public static int clampMaxSeconds(int seconds) {
    return Math.max(5, Math.min(600, seconds));
  }
}
