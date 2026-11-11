package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Recent block writes, so inspect_block can wait out the client/server settle window. */
public final class RecentWrites {
  public static final int STALE_WINDOW = 100;
  public static final int RETENTION = 600;
  public static final int LIMIT = 256;

  public record Entry(long tick, String expected) {}

  private static final LinkedHashMap<String, Entry> WRITES = new LinkedHashMap<>();

  private RecentWrites() {}

  private static String key(String dimension, int x, int y, int z) {
    return dimension + ":" + x + "," + y + "," + z;
  }

  public static void record(String dimension, int x, int y, int z, long tick, String expected) {
    if (dimension == null || dimension.isBlank()) return;
    String k = key(dimension, x, y, z);
    WRITES.remove(k);
    WRITES.put(k, new Entry(tick, expected == null ? "" : expected));
    prune(tick);
  }

  public static Entry entry(String dimension, int x, int y, int z) {
    return WRITES.get(key(dimension, x, y, z));
  }

  /** Ticks since the last write, or -1 when no write is known. */
  public static long age(String dimension, int x, int y, int z, long now) {
    Entry e = entry(dimension, x, y, z);
    if (e == null) return -1;
    return Math.max(0, now - e.tick());
  }

  public static boolean staleHint(String dimension, int x, int y, int z, long now) {
    long age = age(dimension, x, y, z, now);
    return age >= 0 && age <= STALE_WINDOW;
  }

  /**
   * Ticks to wait before reading so a just-written coordinate has settled; 0 when no wait is
   * needed.
   */
  public static int waitTicks(String dimension, int x, int y, int z, long now, int settle) {
    if (settle <= 0) return 0;
    long age = age(dimension, x, y, z, now);
    if (age < 0) return 0;
    return (int) Math.min(settle, Math.max(0, settle - age));
  }

  public static JsonObject describe(String dimension, int x, int y, int z, long now) {
    JsonObject out = new JsonObject();
    out.addProperty("stale_hint", staleHint(dimension, x, y, z, now));
    Entry e = entry(dimension, x, y, z);
    if (e != null) {
      JsonObject write = new JsonObject();
      write.addProperty("tick", e.tick());
      write.addProperty("age_ticks", Math.max(0, now - e.tick()));
      write.addProperty("expected", e.expected());
      out.add("recent_write", write);
    }
    return out;
  }

  public static void clear() {
    WRITES.clear();
  }

  public static int size() {
    return WRITES.size();
  }

  private static void prune(long now) {
    WRITES.values().removeIf(e -> now - e.tick() > RETENTION);
    if (WRITES.size() <= LIMIT) return;
    var it = WRITES.entrySet().iterator();
    while (WRITES.size() > LIMIT && it.hasNext()) {
      it.next();
      it.remove();
    }
  }

  static Map<String, Entry> snapshot() {
    return Map.copyOf(WRITES);
  }
}
