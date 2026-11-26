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

  public enum FluidVerdict {
    OK,
    PLAYER_UNSAFE,
    ABOVE_HAZARD,
    HORIZONTAL_FIRE,
    BULK_FLUID,
    NOT_GENERATOR
  }

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

  public static FluidVerdict verdict(
      String blockId,
      boolean targeted,
      boolean explicitAllowFluidNeighbor,
      boolean fluidOrFireAbove,
      boolean hasHorizontalFluid,
      boolean hasHorizontalFire,
      boolean playerDryAndSafe,
      Collection<String> extraGeneratorBlocks) {
    if (!playerDryAndSafe) return FluidVerdict.PLAYER_UNSAFE;
    if (fluidOrFireAbove) return FluidVerdict.ABOVE_HAZARD;
    if (hasHorizontalFire) return FluidVerdict.HORIZONTAL_FIRE;
    if (!hasHorizontalFluid) return FluidVerdict.OK;
    if (!targeted) return FluidVerdict.BULK_FLUID;
    if (explicitAllowFluidNeighbor) return FluidVerdict.OK;
    if (blockId == null || blockId.isBlank()) return FluidVerdict.NOT_GENERATOR;
    if (GENERATOR_BLOCKS.contains(blockId)
        || WhitelistPolicy.matchesPrefixOrExact(blockId, extraGeneratorBlocks)) {
      return FluidVerdict.OK;
    }
    return FluidVerdict.NOT_GENERATOR;
  }

  public static boolean allowFluidNeighbor(
      String blockId,
      boolean targeted,
      boolean explicitAllowFluidNeighbor,
      boolean fluidOrFireAbove,
      boolean hasHorizontalFluid,
      boolean playerDryAndSafe) {
    return allowFluidNeighbor(
        blockId,
        targeted,
        explicitAllowFluidNeighbor,
        fluidOrFireAbove,
        hasHorizontalFluid,
        playerDryAndSafe,
        List.of());
  }

  public static boolean allowFluidNeighbor(
      String blockId,
      boolean targeted,
      boolean explicitAllowFluidNeighbor,
      boolean fluidOrFireAbove,
      boolean hasHorizontalFluid,
      boolean playerDryAndSafe,
      Collection<String> extraGeneratorBlocks) {
    return verdict(
            blockId,
            targeted,
            explicitAllowFluidNeighbor,
            fluidOrFireAbove,
            hasHorizontalFluid,
            false,
            playerDryAndSafe,
            extraGeneratorBlocks)
        == FluidVerdict.OK;
  }

  public static int clampIntervalTicks(int ticks) {
    return Math.max(0, Math.min(200, ticks));
  }

  public static int clampMaxSeconds(int seconds) {
    return Math.max(5, Math.min(600, seconds));
  }
}
