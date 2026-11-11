package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecentWritesTest {
  @BeforeEach
  void reset() {
    RecentWrites.clear();
  }

  @Test
  void unknownCoordinateHasNoAgeOrStaleHint() {
    assertEquals(-1, RecentWrites.age("minecraft:overworld", 0, 64, 0, 500));
    assertFalse(RecentWrites.staleHint("minecraft:overworld", 0, 64, 0, 500));
    assertEquals(0, RecentWrites.waitTicks("minecraft:overworld", 0, 64, 0, 500, 2));
  }

  @Test
  void justWrittenCoordinateIsStaleAndWaitsForSettle() {
    RecentWrites.record("minecraft:overworld", 1, 64, 2, 1000, "minecraft:stone");
    assertEquals(0, RecentWrites.age("minecraft:overworld", 1, 64, 2, 1000));
    assertTrue(RecentWrites.staleHint("minecraft:overworld", 1, 64, 2, 1000));
    assertEquals(2, RecentWrites.waitTicks("minecraft:overworld", 1, 64, 2, 1000, 2));
    assertEquals(1, RecentWrites.waitTicks("minecraft:overworld", 1, 64, 2, 1001, 2));
    assertEquals(0, RecentWrites.waitTicks("minecraft:overworld", 1, 64, 2, 1002, 2));
    assertEquals(0, RecentWrites.waitTicks("minecraft:overworld", 1, 64, 2, 1000, 0));
  }

  @Test
  void staleHintExpiresAfterWindow() {
    RecentWrites.record("minecraft:overworld", 1, 64, 2, 1000, "minecraft:dirt");
    assertTrue(RecentWrites.staleHint("minecraft:overworld", 1, 64, 2, 1100));
    assertFalse(RecentWrites.staleHint("minecraft:overworld", 1, 64, 2, 1101));
    assertEquals(-1, RecentWrites.age("minecraft:overworld", 1, 64, 3, 1000));
  }

  @Test
  void describeReportsExpectedStateAndAge() {
    RecentWrites.record("minecraft:overworld", 5, 70, -3, 2000, "minecraft:oak_planks");
    JsonObject json = RecentWrites.describe("minecraft:overworld", 5, 70, -3, 2010);
    assertTrue(json.get("stale_hint").getAsBoolean());
    JsonObject write = json.getAsJsonObject("recent_write");
    assertEquals(2000, write.get("tick").getAsLong());
    assertEquals(10, write.get("age_ticks").getAsLong());
    assertEquals("minecraft:oak_planks", write.get("expected").getAsString());
  }

  @Test
  void dimensionsAreIsolatedAndLatestWriteWins() {
    RecentWrites.record("minecraft:overworld", 0, 64, 0, 100, "minecraft:stone");
    RecentWrites.record("minecraft:the_nether", 0, 64, 0, 100, "minecraft:netherrack");
    RecentWrites.record("minecraft:overworld", 0, 64, 0, 120, "minecraft:dirt");
    assertEquals(2, RecentWrites.size());
    assertEquals(
        "minecraft:netherrack", RecentWrites.entry("minecraft:the_nether", 0, 64, 0).expected());
    assertEquals(120, RecentWrites.entry("minecraft:overworld", 0, 64, 0).tick());
  }

  @Test
  void oldWritesArePrunedOnNewRecord() {
    RecentWrites.record("minecraft:overworld", 0, 64, 0, 0, "minecraft:stone");
    RecentWrites.record(
        "minecraft:overworld", 1, 64, 0, RecentWrites.RETENTION + 1, "minecraft:stone");
    assertEquals(1, RecentWrites.size());
    assertNull(RecentWrites.entry("minecraft:overworld", 0, 64, 0));
  }
}
