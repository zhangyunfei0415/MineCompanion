package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import dev.mibao.companion.GeneratorPolicy.FluidVerdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratorPolicyVerdictTest {

  @Test
  void dryBulkMiningPermittedWhileHorizontalFluidRequiresTargetedGenerator() {
    assertEquals(
        FluidVerdict.OK,
        GeneratorPolicy.verdict(
            "minecraft:coal_ore", false, false, false, false, false, true, List.of()));
    assertTrue(
        GeneratorPolicy.allowFluidNeighbor(
            "minecraft:coal_ore", false, false, false, false, true));

    assertEquals(
        FluidVerdict.BULK_FLUID,
        GeneratorPolicy.verdict(
            "minecraft:cobblestone", false, true, false, true, false, true, List.of()));
    assertFalse(
        GeneratorPolicy.allowFluidNeighbor(
            "minecraft:cobblestone", false, true, false, true, true));
  }

  @Test
  void horizontalFireOverheadHazardAndWetPlayerAreAlwaysRejected() {
    assertEquals(
        FluidVerdict.HORIZONTAL_FIRE,
        GeneratorPolicy.verdict(
            "minecraft:cobblestone", true, true, false, false, true, true, List.of()));
    assertEquals(
        FluidVerdict.ABOVE_HAZARD,
        GeneratorPolicy.verdict(
            "minecraft:cobblestone", true, true, true, false, false, true, List.of()));
    assertEquals(
        FluidVerdict.PLAYER_UNSAFE,
        GeneratorPolicy.verdict(
            "minecraft:cobblestone", true, true, false, false, false, false, List.of()));
  }

  @Test
  void targetedGeneratorsAndCustomWhitelistWorkUnderHorizontalFluid() {
    assertEquals(
        FluidVerdict.OK,
        GeneratorPolicy.verdict(
            "minecraft:stone", true, false, false, true, false, true, List.of()));
    assertEquals(
        FluidVerdict.OK,
        GeneratorPolicy.verdict(
            "mod:custom_stone", true, false, false, true, false, true, List.of("mod:custom_stone")));
    assertEquals(
        FluidVerdict.NOT_GENERATOR,
        GeneratorPolicy.verdict(
            "minecraft:iron_ore", true, false, false, true, false, true, List.of()));
    assertEquals(
        FluidVerdict.OK,
        GeneratorPolicy.verdict(
            "minecraft:iron_ore", true, true, false, true, false, true, List.of()));
  }
}
