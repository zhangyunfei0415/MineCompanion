package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class WhitelistPolicyTest {
  @Test
  void acceptsValidModPrefixesAndExactIds() {
    List<String> list = new ArrayList<>();
    assertTrue(WhitelistPolicy.mutate(list, "left_tap", "add", "DrawerMod:compacting_drawer"));
    assertEquals(List.of("drawermod:compacting_drawer"), list);
    assertFalse(WhitelistPolicy.mutate(list, "left_tap", "add", "drawermod:compacting_drawer"));
    assertTrue(WhitelistPolicy.matchesPrefixOrExact("drawermod:compacting_drawer", list));

    assertTrue(WhitelistPolicy.mutate(list, "left_tap", "add", "customdrawers:"));
    assertTrue(WhitelistPolicy.matchesPrefixOrExact("customdrawers:oak_full_4", list));
    assertTrue(WhitelistPolicy.mutate(list, "left_tap", "remove", "customdrawers:"));
    assertFalse(WhitelistPolicy.matchesPrefixOrExact("customdrawers:oak_full_4", list));
  }

  @Test
  void blocksWildcardsDangerousVanillaTargetsAndLethalSoundIgnore() {
    List<String> list = new ArrayList<>();
    assertThrows(
        IllegalArgumentException.class,
        () -> WhitelistPolicy.mutate(list, "left_tap", "add", "*"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WhitelistPolicy.mutate(list, "left_tap", "add", "minecraft:*"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WhitelistPolicy.mutate(list, "left_tap", "add", "minecraft:"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WhitelistPolicy.mutate(list, "left_tap", "add", "minecraft:tnt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WhitelistPolicy.mutate(list, "generator_blocks", "add", "minecraft:chest"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WhitelistPolicy.mutate(list, "sound_ignore", "add", "minecraft:entity.creeper.primed"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WhitelistPolicy.mutate(list, "unknown", "add", "mod:block"));
  }
}
