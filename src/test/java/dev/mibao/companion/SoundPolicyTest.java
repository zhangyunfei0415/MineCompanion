package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SoundPolicyTest {
  @Test
  void classifiesExact1201SoundsAndRejectsSkeletonFalsePositives() {
    assertEquals(
        SoundPolicy.Tier.LETHAL,
        SoundPolicy.classify(
            "minecraft:entity.creeper.primed", "HOSTILE", 3.2, false, List.of(), List.of(), List.of()));
    assertEquals(
        SoundPolicy.Tier.WARN,
        SoundPolicy.classify(
            "minecraft:entity.ghast.warn", "HOSTILE", 14.0, false, List.of(), List.of(), List.of()));
    assertEquals(
        SoundPolicy.Tier.WARN,
        SoundPolicy.classify(
            "minecraft:entity.skeleton.shoot", "HOSTILE", 8.0, false, List.of(), List.of(), List.of()));
    // Must never promote ordinary skeleton footsteps/ambient to WARN
    assertEquals(
        SoundPolicy.Tier.IGNORE,
        SoundPolicy.classify(
            "minecraft:entity.skeleton.ambient", "HOSTILE", 6.0, false, List.of(), List.of(), List.of()));
    assertEquals(
        SoundPolicy.Tier.INFO,
        SoundPolicy.classify(
            "minecraft:entity.zombie.ambient", "HOSTILE", 7.0, false, List.of(), List.of(), List.of()));
  }

  @Test
  void suppressesSelfBlockActionsAndUiNoise() {
    assertEquals(
        SoundPolicy.Tier.IGNORE,
        SoundPolicy.classify(
            "minecraft:block.stone.break", "BLOCKS", 1.1, false, List.of(), List.of(), List.of()));
    assertEquals(
        SoundPolicy.Tier.WARN,
        SoundPolicy.classify(
            "minecraft:block.lava.pop", "BLOCKS", 1.2, false, List.of(), List.of(), List.of()));
    assertEquals(
        SoundPolicy.Tier.IGNORE,
        SoundPolicy.classify(
            "minecraft:ambient.cave", "AMBIENT", 0.0, true, List.of(), List.of(), List.of()));
  }

  @Test
  void computesRelativeBearingAndDeduplicatesBufferToMaxFive() {
    // Facing South (yaw=0): +X is left, -X is right, -Z is back, +Z is front.
    assertEquals("front", SoundPolicy.relativeBearing(0, 0, 0f, 0, 5, false));
    assertEquals("right", SoundPolicy.relativeBearing(0, 0, 0f, -5, 0, false));
    assertEquals("left", SoundPolicy.relativeBearing(0, 0, 0f, 5, 0, false));
    assertEquals("back", SoundPolicy.relativeBearing(0, 0, 0f, 0, -5, false));
    assertEquals("none", SoundPolicy.relativeBearing(0, 0, 0f, 100, 100, true));

    var buf = new SoundPolicy.Buffer();
    for (int i = 0; i < 4; i++) {
      buf.record("minecraft:entity.zombie.ambient", SoundPolicy.Tier.INFO, "back", 8.0 - i, 0, 64, -5, 1000L + i * 100);
    }
    var arr = buf.toJsonArray(2000L, SoundPolicy.MAX_EMIT);
    assertEquals(1, arr.size());
    assertEquals(4, arr.get(0).getAsJsonObject().get("count").getAsInt());

    // Add 6 distinct INFO/WARN events; capped at MAX_EMIT (5) and prioritized by severity
    for (int i = 0; i < 6; i++) {
      buf.record("minecraft:entity.spider.ambient." + i, SoundPolicy.Tier.INFO, "front", 5.0, 0, 64, 5, 2500L + i);
    }
    buf.record("minecraft:entity.creeper.primed", SoundPolicy.Tier.LETHAL, "back", 3.0, 0, 64, -3, 2600L);
    var top = buf.toJsonArray(2700L, SoundPolicy.MAX_EMIT);
    assertEquals(5, top.size());
    assertEquals("LETHAL", top.get(0).getAsJsonObject().get("tier").getAsString());
  }
}
