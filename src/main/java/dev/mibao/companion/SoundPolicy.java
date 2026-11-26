package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/**
 * Pure-Java classifier, bearing calculator, and deduplicating event buffer for sound perception.
 * Zero {@code net.minecraft.*} imports.
 */
public final class SoundPolicy {
  public enum Tier {
    LETHAL,
    WARN,
    INFO,
    IGNORE
  }

  public static final long TTL_MS = 10_000L;
  public static final int MAX_EMIT = 5;
  public static final double SELF_SUPPRESS_DIST = 1.5;

  public static final Set<String> LETHAL_SOUNDS =
      Set.of(
          "minecraft:entity.creeper.primed",
          "minecraft:entity.tnt.primed",
          "minecraft:entity.generic.explode");

  // Exact Forge 1.20.1 SoundEvents IDs (never use entity.skeleton.* wildcard: no bow draw sound exists).
  public static final Set<String> WARN_SOUNDS =
      Set.of(
          "minecraft:entity.ghast.warn",
          "minecraft:entity.ghast.shoot",
          "minecraft:entity.ghast.scream",
          "minecraft:entity.blaze.shoot",
          "minecraft:entity.skeleton.shoot",
          "minecraft:block.lava.pop",
          "minecraft:block.lava.ambient",
          "minecraft:entity.enderman.stare",
          "minecraft:entity.enderman.scream",
          "minecraft:entity.warden.roar",
          "minecraft:entity.warden.sonic_charge",
          "minecraft:entity.warden.sonic_boom",
          "minecraft:entity.warden.angry",
          "minecraft:entity.warden.heartbeat",
          "minecraft:entity.warden.emerge",
          "minecraft:entity.warden.nearby_closest",
          "minecraft:entity.warden.nearby_closer",
          "minecraft:entity.lightning_bolt.thunder",
          "minecraft:entity.lightning_bolt.impact",
          "minecraft:entity.phantom.swoop");

  public static final Set<String> INFO_SOUNDS =
      Set.of(
          "minecraft:entity.zombie.ambient",
          "minecraft:entity.spider.ambient",
          "minecraft:entity.phantom.flap",
          "minecraft:weather.rain",
          "minecraft:weather.rain.above");

  public static final Set<String> IGNORE_SOUNDS =
      Set.of(
          "minecraft:entity.minecart.riding",
          "minecraft:entity.minecart.inside",
          "minecraft:entity.minecart.inside.underwater",
          "minecraft:ambient.cave");

  private static final Set<String> MUTED_SOURCES = Set.of("UI", "MUSIC", "RECORDS", "MASTER");

  private SoundPolicy() {}

  public static boolean isSelfBlockAction(String soundId, double distance) {
    if (soundId == null || distance > SELF_SUPPRESS_DIST) return false;
    String id = soundId.toLowerCase(Locale.ROOT);
    if (!id.contains(":block.")) return false;
    return id.endsWith(".break")
        || id.endsWith(".place")
        || id.endsWith(".step")
        || id.endsWith(".hit")
        || id.endsWith(".fall");
  }

  public static Tier classify(
      String soundId,
      String sourceName,
      double distance,
      boolean directionless,
      Collection<String> customWarn,
      Collection<String> customInfo,
      Collection<String> customIgnore) {
    if (soundId == null || soundId.isBlank()) return Tier.IGNORE;
    String id = soundId.strip().toLowerCase(Locale.ROOT);
    String src = sourceName == null ? "" : sourceName.strip().toUpperCase(Locale.ROOT);
    if (src.equals("UI") || src.equals("MUSIC") || src.equals("RECORDS")) return Tier.IGNORE;
    if (LETHAL_SOUNDS.contains(id)) return Tier.LETHAL;
    if (IGNORE_SOUNDS.contains(id) || WhitelistPolicy.matchesPrefixOrExact(id, customIgnore))
      return Tier.IGNORE;
    if (isSelfBlockAction(id, distance)) return Tier.IGNORE;
    if (WhitelistPolicy.matchesPrefixOrExact(id, customWarn) || WARN_SOUNDS.contains(id))
      return Tier.WARN;
    if (WhitelistPolicy.matchesPrefixOrExact(id, customInfo) || INFO_SOUNDS.contains(id))
      return Tier.INFO;
    return Tier.IGNORE;
  }

  public static boolean isDirectionlessSource(String sourceName) {
    if (sourceName == null) return false;
    String src = sourceName.strip().toUpperCase(Locale.ROOT);
    return src.equals("UI") || src.equals("MUSIC") || src.equals("RECORDS");
  }

  public static String relativeBearing(
      double listenerX,
      double listenerZ,
      float yawDeg,
      double soundX,
      double soundZ,
      boolean directionless) {
    if (directionless) return "none";
    double dx = soundX - listenerX;
    double dz = soundZ - listenerZ;
    double len = Math.hypot(dx, dz);
    if (len < 0.35) return "here";
    double ux = dx / len;
    double uz = dz / len;
    double rad = Math.toRadians(yawDeg);
    double fx = -Math.sin(rad);
    double fz = Math.cos(rad);
    double rx = -fz;
    double rz = fx;
    double dotF = ux * fx + uz * fz;
    double dotR = ux * rx + uz * rz;
    double deg = Math.toDegrees(Math.atan2(dotR, dotF));
    if (deg >= -22.5 && deg < 22.5) return "front";
    if (deg >= 22.5 && deg < 67.5) return "front_right";
    if (deg >= 67.5 && deg < 112.5) return "right";
    if (deg >= 112.5 && deg < 157.5) return "back_right";
    if (deg >= -67.5 && deg < -22.5) return "front_left";
    if (deg >= -112.5 && deg < -67.5) return "left";
    if (deg >= -157.5 && deg < -112.5) return "back_left";
    return "back";
  }

  public static String distanceBucket(double distance, boolean directionless) {
    if (directionless) return "none";
    if (distance <= 4.0) return "very_close";
    if (distance <= 10.0) return "close";
    if (distance <= 20.0) return "medium";
    return "far";
  }

  public static final class SoundEntry {
    public final String id;
    public final Tier tier;
    public final String bearing;
    public double minDistance;
    public double lastX, lastY, lastZ;
    public long firstSeenMs;
    public long lastSeenMs;
    public int count;

    public SoundEntry(
        String id,
        Tier tier,
        String bearing,
        double distance,
        double x,
        double y,
        double z,
        long nowMs) {
      this.id = id;
      this.tier = tier;
      this.bearing = bearing;
      this.minDistance = distance;
      this.lastX = x;
      this.lastY = y;
      this.lastZ = z;
      this.firstSeenMs = nowMs;
      this.lastSeenMs = nowMs;
      this.count = 1;
    }
  }

  /** Deduplicating bounded ring buffer for recent non-ignored sounds. */
  public static final class Buffer {
    private static final int INTERNAL_CAP = 32;
    private final Map<String, SoundEntry> entries = new LinkedHashMap<>();

    public synchronized void clear() {
      entries.clear();
    }

    public synchronized void record(
        String id,
        Tier tier,
        String bearing,
        double distance,
        double x,
        double y,
        double z,
        long nowMs) {
      if (tier == Tier.IGNORE || id == null || id.isBlank()) return;
      pruneLocked(nowMs);
      String key = id + "|" + tier.name() + "|" + bearing;
      SoundEntry existing = entries.get(key);
      if (existing != null) {
        existing.count++;
        existing.lastSeenMs = nowMs;
        if (distance < existing.minDistance) {
          existing.minDistance = distance;
          existing.lastX = x;
          existing.lastY = y;
          existing.lastZ = z;
        }
        entries.remove(key);
        entries.put(key, existing);
        return;
      }
      if (entries.size() >= INTERNAL_CAP) {
        String oldestKey = entries.keySet().iterator().next();
        entries.remove(oldestKey);
      }
      entries.put(key, new SoundEntry(id, tier, bearing, distance, x, y, z, nowMs));
    }

    public synchronized List<SoundEntry> recent(long nowMs, int limit) {
      pruneLocked(nowMs);
      int cap = Math.max(1, limit);
      List<SoundEntry> list = new ArrayList<>(entries.values());
      list.sort(
          Comparator.<SoundEntry>comparingInt(e -> severityRank(e.tier))
              .thenComparingLong(e -> -e.lastSeenMs));
      return list.size() > cap ? new ArrayList<>(list.subList(0, cap)) : list;
    }

    public synchronized JsonArray toJsonArray(long nowMs, int limit) {
      JsonArray arr = new JsonArray();
      for (SoundEntry e : recent(nowMs, limit)) {
        boolean dirless = "none".equals(e.bearing);
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id);
        o.addProperty("tier", e.tier.name());
        o.addProperty("bearing", e.bearing);
        o.addProperty("dist", distanceBucket(e.minDistance, dirless));
        if (!dirless) o.addProperty("dist_blocks", Math.round(e.minDistance * 10.0) / 10.0);
        o.addProperty("count", e.count);
        o.addProperty("age_ms", Math.max(0L, nowMs - e.lastSeenMs));
        arr.add(o);
      }
      return arr;
    }

    private void pruneLocked(long nowMs) {
      entries.entrySet().removeIf(e -> nowMs - e.getValue().lastSeenMs > TTL_MS);
    }

    private static int severityRank(Tier tier) {
      return switch (tier) {
        case LETHAL -> 0;
        case WARN -> 1;
        case INFO -> 2;
        case IGNORE -> 3;
      };
    }
  }
}
