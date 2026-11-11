package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** Loaded-world observation amortized over ticks; no world access from I/O threads. */
public final class Perception {
  private record Seen(BlockPos pos, String id, String category, double distance) {}

  private static final List<Seen> found = new ArrayList<>();
  private static JsonArray nearby = new JsonArray();
  private static BlockPos origin, cachedOrigin;
  private static String dimension = "", cachedMap = "";
  private static int cursor = -1, samples, cachedSamples, cachedTotal;
  private static long completed, started, wantedUntil, cachedDuration;
  private static double completedMillis;
  private static double millis, lastSlice;
  private static int radius = 12, cachedRadius, requestedRadius, total;
  private static boolean force;
  private static long generation;
  private static ScanFreshness freshness = new ScanFreshness();
  private static String requestedBlock = "", scanBlock = "", cachedBlock = "";
  private static final PriorityQueue<Seen> targeted =
      new PriorityQueue<>(Comparator.comparingDouble(Seen::distance).reversed());
  private static List<BlockPos> cachedTargets = List.of();

  public static List<BlockPos> matching(String id) {
    return id.equals(cachedBlock) ? cachedTargets : List.of();
  }

  public static long refreshForBlock(int radius, String id) {
    long ticket = refresh(radius);
    requestedBlock = id;
    return ticket;
  }

  public static long generation() {
    return generation;
  }

  public static long refresh(int requested) {
    var mc = Minecraft.getInstance();
    String dim = mc.level.dimension().location().toString();
    if (!dim.equals(dimension)) {
      reset();
      dimension = dim;
    }
    request();
    requestedRadius = Rules.bounded(requested, 4, 24);
    force = true;
    cursor = -1;
    return generation + 1;
  }

  public static double lastMillis() {
    return completedMillis;
  }

  public static long age() {
    return completed == 0 ? -1 : System.currentTimeMillis() - completed;
  }

  public static void reset() {
    freshness = new ScanFreshness();
    nearby = new JsonArray();
    found.clear();
    origin = null;
    cachedOrigin = null;
    dimension = "";
    cachedMap = "";
    cursor = -1;
    completed = 0;
    wantedUntil = 0;
    completedMillis = 0;
    force = false;
    requestedRadius = 0;
    generation = 0;
    requestedBlock = "";
    scanBlock = "";
    cachedBlock = "";
    cachedTargets = List.of();
    targeted.clear();
  }

  public static void blockChanged(BlockPos p) {
    if (cachedOrigin != null
        && p.distSqr(cachedOrigin) <= (cachedRadius + 2.0) * (cachedRadius + 2.0) + 36)
      freshness.changed();
  }

  public static void request() {
    wantedUntil = System.currentTimeMillis() + 10000;
  }

  public static void tick() {
    var mc = Minecraft.getInstance();
    if (mc.level == null || mc.player == null) return;
    long now = System.currentTimeMillis();
    if (!Agent.enabled && now > wantedUntil) return;
    String dim = mc.level.dimension().location().toString();
    if (!dim.equals(dimension)) {
      long wanted = wantedUntil;
      reset();
      dimension = dim;
      wantedUntil = wanted;
    }
    BlockPos here = mc.player.blockPosition();
    if (cursor < 0) {
      if (!freshness.refresh(age(), cachedOrigin == null || cachedOrigin.distSqr(here) >= 9, force))
        return;
      freshness.begin();
      radius =
          requestedRadius > 0
              ? requestedRadius
              : Rules.bounded(Store.config.perceptionRadius, 4, 24);
      requestedRadius = 0;
      force = false;
      int width = radius * 2 + 1;
      total = width * width * 11;
      origin = here;
      cursor = 0;
      samples = 0;
      millis = 0;
      started = now;
      found.clear();
      targeted.clear();
      scanBlock = requestedBlock;
      requestedBlock = "";
    }
    if (origin.distSqr(here) > 64) {
      cursor = -1;
      return;
    }
    long begin = System.nanoTime();
    int count = 0;
    int width = radius * 2 + 1;
    while (cursor < total && count++ < 256) {
      int i = cursor++;
      BlockPos p =
          origin.offset(i % width - radius, i / (width * width) - 4, i / width % width - radius);
      if (mc.level.hasChunkAt(p)) {
        BlockState s = mc.level.getBlockState(p);
        samples++;
        WorldSense.record(p, s);
        if (!scanBlock.isEmpty()
            && BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString().equals(scanBlock)) {
          Seen candidate = new Seen(p, scanBlock, "resource", p.distSqr(origin));
          if (targeted.size() < 64) targeted.add(candidate);
          else if (candidate.distance() < targeted.peek().distance()) {
            targeted.poll();
            targeted.add(candidate);
          }
        }
        if (!s.isAir()) {
          String category = WorldSense.category(s);
          if (!category.isEmpty())
            found.add(
                new Seen(
                    p,
                    BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString(),
                    category,
                    p.distSqr(origin)));
        }
      }
      if (count % 32 == 0 && System.nanoTime() - begin > 1_000_000) break;
    }
    if (cursor >= total) {
      found.sort(Comparator.comparingDouble(Seen::distance));
      JsonArray next = new JsonArray();
      for (int i = 0; i < Math.min(64, found.size()); i++) {
        var v = found.get(i);
        JsonObject o = new JsonObject();
        o.addProperty("id", v.id);
        o.addProperty("category", v.category);
        o.addProperty("x", v.pos.getX());
        o.addProperty("y", v.pos.getY());
        o.addProperty("z", v.pos.getZ());
        next.add(o);
      }
      nearby = next;
      cachedOrigin = origin;
      cachedMap = map(origin);
      completed = now;
      cachedSamples = samples;
      cachedTotal = total;
      cachedDuration = completed - started;
      cachedRadius = radius;
      cursor = -1;
      generation++;
      freshness.complete();
      if (!scanBlock.isEmpty()) {
        cachedBlock = scanBlock;
        cachedTargets =
            targeted.stream()
                .sorted(Comparator.comparingDouble(Seen::distance))
                .map(Seen::pos)
                .toList();
      }
    }
    lastSlice = (System.nanoTime() - begin) / 1_000_000.0;
    millis += lastSlice;
    if (cursor < 0) completedMillis = millis;
  }

  public static void append(JsonObject out) {
    request();
    out.add("nearby_interesting_blocks", nearby.deepCopy());
    JsonObject meta = new JsonObject();
    meta.addProperty("age_ms", age());
    meta.addProperty("changed_since_snapshot", freshness.dirty());
    meta.addProperty("scanning", cursor >= 0);
    meta.addProperty("complete", completed > 0);
    meta.addProperty("sampled_loaded_cells", cachedSamples);
    meta.addProperty("scan_cpu_ms", completedMillis);
    meta.addProperty("last_tick_cpu_ms", lastSlice);
    meta.addProperty("unknown_is_not_air", true);
    meta.addProperty("total_cells", completed > 0 ? cachedTotal : 0);
    meta.addProperty("unloaded_unknown_cells", completed > 0 ? cachedTotal - cachedSamples : 0);
    meta.addProperty("active_scanned_cells", Math.max(0, cursor));
    meta.addProperty("active_total_cells", cursor >= 0 ? total : 0);
    meta.addProperty("horizontal_radius", completed > 0 ? cachedRadius : 0);
    meta.addProperty("active_scan_radius", cursor >= 0 ? radius : 0);
    meta.addProperty("vertical_below", 4);
    meta.addProperty("vertical_above", 6);
    meta.addProperty("generation", generation);
    if (cachedOrigin != null) {
      meta.addProperty("center", cachedOrigin.toShortString());
      meta.addProperty("dimension", dimension);
      meta.addProperty("scan_duration_ms", cachedDuration);
    }
    out.add("perception_cache", meta);
    if (!cachedMap.isEmpty()) {
      out.addProperty("local_walk_map", cachedMap);
      out.addProperty(
          "map_legend",
          "radius=4, 9x9, N at top, E right, center @, cell 1 block; .=flat safe, ^=up 1, ,=down 1,"
              + " #=blocked/unsafe at sampled y (not proof of enclosure), !=hazard, ?=unloaded. At"
              + " cached center/y, not a complete 3D world; outside radius is unknown; do not plan"
              + " through unknown.");
    }
  }

  private static String map(BlockPos center) {
    var level = Minecraft.getInstance().level;
    StringBuilder result = new StringBuilder();
    for (int z = -4; z <= 4; z++) {
      for (int x = -4; x <= 4; x++) {
        BlockPos p = center.offset(x, 0, z);
        char c = '?';
        if (level.hasChunkAt(p)) {
          BlockState feet = level.getBlockState(p), floor = level.getBlockState(p.below());
          if (feet.is(Blocks.LAVA)
              || floor.is(Blocks.LAVA)
              || feet.is(Blocks.FIRE)
              || floor.is(Blocks.MAGMA_BLOCK)
              || floor.is(Blocks.CAMPFIRE)
              || floor.is(Blocks.CACTUS)) c = '!';
          else
            c =
                Navigator.stand(p)
                    ? '.'
                    : Navigator.stand(p.above()) ? '^' : Navigator.stand(p.below()) ? ',' : '#';
        }
        result.append(x == 0 && z == 0 ? '@' : c);
      }
      result.append('\n');
    }
    return result.toString();
  }
}
