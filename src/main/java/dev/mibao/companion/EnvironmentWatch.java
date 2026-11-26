package dev.mibao.companion;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * Passive day/night, weather, and phantom background monitor gated strictly by
 * dimensionType().hasSkyLight() so Backrooms, Nether, and End stay silent.
 */
public final class EnvironmentWatch {
  private static int daysSinceSleep;
  private static long lastDayIndex = -1;
  private static long lastPollMs;
  private static EnvironmentWatchPolicy.Snapshot cached;

  private EnvironmentWatch() {}

  public static void reset() {
    daysSinceSleep = 0;
    lastDayIndex = -1;
    lastPollMs = 0L;
    cached = null;
  }

  public static void tick() {
    if (!Store.config.environmentWatch) {
      cached = null;
      return;
    }
    var mc = Minecraft.getInstance();
    var level = mc.level;
    var player = mc.player;
    if (level == null || player == null) {
      cached = null;
      return;
    }
    long now = System.currentTimeMillis();
    if (now - lastPollMs < 1000L && cached != null) return;
    lastPollMs = now;

    boolean hasSky = level.dimensionType().hasSkyLight();
    if (player.isSleeping()) {
      daysSinceSleep = 0;
    }
    long dayTime = level.getDayTime();
    if (hasSky) {
      long dayIdx = Math.floorDiv(dayTime, 24000L);
      if (lastDayIndex >= 0 && dayIdx > lastDayIndex) {
        daysSinceSleep = Math.min(30, daysSinceSleep + (int) (dayIdx - lastDayIndex));
      }
      lastDayIndex = dayIdx;
    }
    cached =
        EnvironmentWatchPolicy.evaluate(
            hasSky,
            dayTime % 24000L,
            level.isRaining(),
            level.isThundering(),
            daysSinceSleep,
            SoundSense.lastPhantomCueMs(),
            now);
  }

  public static JsonObject observe() {
    if (!Store.config.environmentWatch) return new JsonObject();
    if (cached == null) tick();
    return cached == null ? new JsonObject() : cached.toJson();
  }
}
