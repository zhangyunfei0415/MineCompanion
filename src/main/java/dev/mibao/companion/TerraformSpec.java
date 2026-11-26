package dev.mibao.companion;

import com.google.gson.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Strictly validated immutable specification for unified 3D terraforming (flatten, fill, and
 * large-scale irrigated farm reclamation). Zero {@code net.minecraft.*} dependencies.
 */
public sealed interface TerraformSpec
    permits TerraformSpec.Flatten, TerraformSpec.Fill, TerraformSpec.FarmGrid {

  int MAX_XZ = 64;
  int MAX_Y = 32;
  int MAX_FOOTPRINT = 4096;
  int MAX_VOLUME = 32768;
  int MAX_OPS = 10000;

  Set<String> DEFAULT_NATURAL_CUT_BLOCKS =
      Set.of(
          "minecraft:stone",
          "minecraft:dirt",
          "minecraft:grass_block",
          "minecraft:coarse_dirt",
          "minecraft:rooted_dirt",
          "minecraft:podzol",
          "minecraft:mycelium",
          "minecraft:gravel",
          "minecraft:sand",
          "minecraft:red_sand",
          "minecraft:sandstone",
          "minecraft:red_sandstone",
          "minecraft:granite",
          "minecraft:diorite",
          "minecraft:andesite",
          "minecraft:deepslate",
          "minecraft:cobbled_deepslate",
          "minecraft:tuff",
          "minecraft:calcite",
          "minecraft:clay",
          "minecraft:mud",
          "minecraft:packed_mud",
          "minecraft:cobblestone",
          "minecraft:mossy_cobblestone",
          "minecraft:moss_block",
          "minecraft:netherrack",
          "minecraft:terracotta",
          "minecraft:snow_block",
          "minecraft:snow",
          "minecraft:grass",
          "minecraft:tall_grass",
          "minecraft:fern",
          "minecraft:large_fern",
          "minecraft:dead_bush",
          "minecraft:dandelion",
          "minecraft:poppy",
          "minecraft:blue_orchid",
          "minecraft:allium",
          "minecraft:azure_bluet",
          "minecraft:red_tulip",
          "minecraft:orange_tulip",
          "minecraft:white_tulip",
          "minecraft:pink_tulip",
          "minecraft:oxeye_daisy",
          "minecraft:cornflower",
          "minecraft:lily_of_the_valley",
          "minecraft:sunflower",
          "minecraft:lilac",
          "minecraft:rose_bush",
          "minecraft:peony");

  enum Mode {
    FLATTEN,
    FILL,
    FARM_GRID
  }

  enum FlattenVariant {
    CUT_ONLY,
    CUT_AND_FILL
  }

  enum FillVariant {
    SOLID,
    HOLLOW,
    WALLS,
    REPLACE_AIR
  }

  enum WaterSource {
    INVENTORY,
    POOL_2X2
  }

  enum WaterShortagePolicy {
    PAUSE,
    PREPARE_DIRT
  }

  record Pos(int x, int y, int z) {
    public String toShortString() {
      return x + ", " + y + ", " + z;
    }
  }

  record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public Box {
      if (minX > maxX || minY > maxY || minZ > maxZ)
        throw new IllegalArgumentException(
            "[INVALID_BOUNDS] 边界要求 min <= max（当前 min=("
                + minX
                + ","
                + minY
                + ","
                + minZ
                + "), max=("
                + maxX
                + ","
                + maxY
                + ","
                + maxZ
                + ")）");
      long w = (long) maxX - minX + 1L;
      long h = (long) maxY - minY + 1L;
      long d = (long) maxZ - minZ + 1L;
      if (w > MAX_XZ || d > MAX_XZ)
        throw new IllegalArgumentException("[VOLUME_LIMIT] 水平边长不可超过 " + MAX_XZ + " 格");
      if (h > MAX_Y)
        throw new IllegalArgumentException("[VOLUME_LIMIT] 高度不可超过 " + MAX_Y + " 格");
      long fp = w * d;
      if (fp > MAX_FOOTPRINT)
        throw new IllegalArgumentException("[VOLUME_LIMIT] 水平面积不可超过 " + MAX_FOOTPRINT + " 格");
      if (fp * h > MAX_VOLUME)
        throw new IllegalArgumentException("[VOLUME_LIMIT] 总体积不可超过 " + MAX_VOLUME + " 格");
    }

    public int width() {
      return maxX - minX + 1;
    }

    public int height() {
      return maxY - minY + 1;
    }

    public int depth() {
      return maxZ - minZ + 1;
    }

    public int footprint() {
      return width() * depth();
    }

    public int volume() {
      return footprint() * height();
    }

    public boolean contains(int x, int y, int z) {
      return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public void validateWorldHeight(int minBuildY, int maxBuildYInclusive) {
      if (minY < minBuildY || maxY > maxBuildYInclusive) {
        throw new IllegalArgumentException(
            "[WORLD_BOUNDS] 区域高度 ["
                + minY
                + ".."
                + maxY
                + "] 超出维度有效建造范围 ["
                + minBuildY
                + ".."
                + maxBuildYInclusive
                + "]");
      }
    }

    public JsonObject toJson() {
      var o = new JsonObject();
      o.addProperty("min_x", minX);
      o.addProperty("min_y", minY);
      o.addProperty("min_z", minZ);
      o.addProperty("max_x", maxX);
      o.addProperty("max_y", maxY);
      o.addProperty("max_z", maxZ);
      o.addProperty("width", width());
      o.addProperty("height", height());
      o.addProperty("depth", depth());
      o.addProperty("volume", volume());
      return o;
    }
  }

  record RequiredPermissions(boolean mine, boolean place) {}

  Box box();

  Mode mode();

  Set<String> cutBlocks();

  RequiredPermissions requiredPermissions();

  String expectedPlanHash();

  JsonObject toJson();

  default String specHash() {
    JsonObject norm = toJson();
    norm.remove("expected_plan_hash");
    return sha256(norm.toString());
  }

  record Flatten(
      Box box,
      FlattenVariant variant,
      int targetY,
      String fillBlock,
      Set<String> cutBlocks,
      String expectedPlanHash)
      implements TerraformSpec {
    @Override
    public Mode mode() {
      return Mode.FLATTEN;
    }

    @Override
    public RequiredPermissions requiredPermissions() {
      return new RequiredPermissions(true, variant == FlattenVariant.CUT_AND_FILL);
    }

    @Override
    public JsonObject toJson() {
      var o = baseJson(box, "flatten", cutBlocks, expectedPlanHash);
      o.addProperty("variant", variant.name().toLowerCase(Locale.ROOT));
      o.addProperty("target_y", targetY);
      if (!fillBlock.isBlank()) o.addProperty("block", fillBlock);
      return o;
    }
  }

  record Fill(
      Box box,
      FillVariant variant,
      String block,
      boolean replaceNatural,
      Set<String> cutBlocks,
      String expectedPlanHash)
      implements TerraformSpec {
    @Override
    public Mode mode() {
      return Mode.FILL;
    }

    @Override
    public RequiredPermissions requiredPermissions() {
      boolean needsMine = variant != FillVariant.REPLACE_AIR && replaceNatural;
      return new RequiredPermissions(needsMine, true);
    }

    @Override
    public JsonObject toJson() {
      var o = baseJson(box, "fill", cutBlocks, expectedPlanHash);
      o.addProperty("variant", variant.name().toLowerCase(Locale.ROOT));
      o.addProperty("block", block);
      o.addProperty("replace_natural", replaceNatural);
      return o;
    }
  }

  record FarmGrid(
      Box box,
      int targetY,
      CropSpec crop,
      WaterSource waterSource,
      Pos poolMin,
      String coverSlab,
      WaterShortagePolicy onWaterShortage,
      Set<String> cutBlocks,
      String expectedPlanHash)
      implements TerraformSpec {
    @Override
    public Mode mode() {
      return Mode.FARM_GRID;
    }

    @Override
    public RequiredPermissions requiredPermissions() {
      return new RequiredPermissions(true, true);
    }

    @Override
    public JsonObject toJson() {
      var o = baseJson(box, "farm_grid", cutBlocks, expectedPlanHash);
      o.addProperty("target_y", targetY);
      if (crop != null) o.addProperty("crop", crop.block());
      o.addProperty("water_source", waterSource.name().toLowerCase(Locale.ROOT));
      if (poolMin != null) {
        o.addProperty("pool_x", poolMin.x());
        o.addProperty("pool_y", poolMin.y());
        o.addProperty("pool_z", poolMin.z());
      }
      if (!coverSlab.isBlank()) o.addProperty("cover_slab", coverSlab);
      o.addProperty("on_water_shortage", onWaterShortage.name().toLowerCase(Locale.ROOT));
      return o;
    }
  }

  private static JsonObject baseJson(
      Box box, String mode, Set<String> cutBlocks, String expectedPlanHash) {
    var o = new JsonObject();
    o.addProperty("min_x", box.minX());
    o.addProperty("min_y", box.minY());
    o.addProperty("min_z", box.minZ());
    o.addProperty("max_x", box.maxX());
    o.addProperty("max_y", box.maxY());
    o.addProperty("max_z", box.maxZ());
    o.addProperty("mode", mode);
    if (!cutBlocks.equals(DEFAULT_NATURAL_CUT_BLOCKS)) {
      var arr = new JsonArray();
      cutBlocks.stream().sorted().forEach(arr::add);
      o.add("cut_blocks", arr);
    }
    if (expectedPlanHash != null && !expectedPlanHash.isBlank()) {
      o.addProperty("expected_plan_hash", expectedPlanHash);
    }
    return o;
  }

  Set<String> COMMON_KEYS =
      Set.of(
          "min_x",
          "min_y",
          "min_z",
          "max_x",
          "max_y",
          "max_z",
          "mode",
          "cut_blocks",
          "expected_plan_hash");
  Set<String> FLATTEN_KEYS = Set.of("variant", "target_y", "block");
  Set<String> FILL_KEYS = Set.of("variant", "block", "replace_natural");
  Set<String> FARM_KEYS =
      Set.of(
          "target_y",
          "crop",
          "water_source",
          "pool_x",
          "pool_y",
          "pool_z",
          "cover_slab",
          "on_water_shortage");

  static TerraformSpec parse(JsonObject a, boolean allowExpectedHash) {
    if (a == null) throw new IllegalArgumentException("[ARGUMENT_INVALID] 参数不能为空");
    String modeRaw = reqStr(a, "mode").toLowerCase(Locale.ROOT);
    Mode mode =
        switch (modeRaw) {
          case "flatten" -> Mode.FLATTEN;
          case "fill" -> Mode.FILL;
          case "farm_grid" -> Mode.FARM_GRID;
          default ->
              throw new IllegalArgumentException(
                  "[ARGUMENT_INVALID] mode 必须为 flatten / fill / farm_grid");
        };
    Set<String> allowed = new HashSet<>(COMMON_KEYS);
    switch (mode) {
      case FLATTEN -> allowed.addAll(FLATTEN_KEYS);
      case FILL -> allowed.addAll(FILL_KEYS);
      case FARM_GRID -> allowed.addAll(FARM_KEYS);
    }
    for (String k : a.keySet()) {
      if (!allowed.contains(k)) {
        throw new IllegalArgumentException("[ARGUMENT_INVALID] 不支持或跨模式参数：" + k);
      }
    }
    if (!allowExpectedHash && a.has("expected_plan_hash")) {
      throw new IllegalArgumentException("[ARGUMENT_INVALID] terraform_preview 不接受 expected_plan_hash");
    }
    String expectedHash = optStr(a, "expected_plan_hash");
    Box box =
        new Box(
            reqInt(a, "min_x"),
            reqInt(a, "min_y"),
            reqInt(a, "min_z"),
            reqInt(a, "max_x"),
            reqInt(a, "max_y"),
            reqInt(a, "max_z"));
    Set<String> cutBlocks = parseCutBlocks(a);

    return switch (mode) {
      case FLATTEN -> {
        String varRaw = reqStr(a, "variant").toLowerCase(Locale.ROOT);
        FlattenVariant variant =
            switch (varRaw) {
              case "cut_only" -> FlattenVariant.CUT_ONLY;
              case "cut_and_fill" -> FlattenVariant.CUT_AND_FILL;
              default ->
                  throw new IllegalArgumentException(
                      "[ARGUMENT_INVALID] flatten.variant 必须为 cut_only / cut_and_fill");
            };
        int targetY = reqInt(a, "target_y");
        if (targetY < box.minY() || targetY > box.maxY()) {
          throw new IllegalArgumentException(
              "[INVALID_BOUNDS] target_y(" + targetY + ") 必须在 [min_y..max_y] 内");
        }
        String fillBlock = optStr(a, "block");
        if (variant == FlattenVariant.CUT_ONLY && !fillBlock.isBlank()) {
          throw new IllegalArgumentException("[ARGUMENT_INVALID] cut_only 模式不允许传 block");
        }
        if (variant == FlattenVariant.CUT_AND_FILL) {
          if (fillBlock.isBlank())
            throw new IllegalArgumentException("[ARGUMENT_REQUIRED] cut_and_fill 必须指定 block");
          validateRegistryId(fillBlock, "block");
        }
        yield new Flatten(box, variant, targetY, fillBlock, cutBlocks, expectedHash);
      }
      case FILL -> {
        String varRaw = reqStr(a, "variant").toLowerCase(Locale.ROOT);
        FillVariant variant =
            switch (varRaw) {
              case "solid" -> FillVariant.SOLID;
              case "hollow" -> FillVariant.HOLLOW;
              case "walls" -> FillVariant.WALLS;
              case "replace_air" -> FillVariant.REPLACE_AIR;
              default ->
                  throw new IllegalArgumentException(
                      "[ARGUMENT_INVALID] fill.variant 必须为 solid / hollow / walls / replace_air");
            };
        String block = reqStr(a, "block");
        validateRegistryId(block, "block");
        boolean replaceNatural = optBool(a, "replace_natural", false);
        if (variant == FillVariant.REPLACE_AIR && replaceNatural) {
          throw new IllegalArgumentException(
              "[ARGUMENT_INVALID] replace_air 模式不允许 replace_natural=true");
        }
        yield new Fill(box, variant, block, replaceNatural, cutBlocks, expectedHash);
      }
      case FARM_GRID -> {
        int targetY = reqInt(a, "target_y");
        if (targetY < box.minY() || targetY > box.maxY()) {
          throw new IllegalArgumentException(
              "[INVALID_BOUNDS] target_y(" + targetY + ") 必须在 [min_y..max_y] 内");
        }
        String cropRaw = optStr(a, "crop");
        CropSpec crop = cropRaw.isBlank() ? null : CropSpec.of(cropRaw);
        if (crop != null && box.maxY() < targetY + 1) {
          throw new IllegalArgumentException(
              "[INVALID_BOUNDS] 指定 crop 播种时 max_y 须至少为 target_y + 1");
        }
        String wsRaw = optStr(a, "water_source");
        WaterSource ws =
            switch (wsRaw.isBlank() ? "inventory" : wsRaw.toLowerCase(Locale.ROOT)) {
              case "inventory" -> WaterSource.INVENTORY;
              case "pool_2x2" -> WaterSource.POOL_2X2;
              default ->
                  throw new IllegalArgumentException(
                      "[ARGUMENT_INVALID] water_source 必须为 inventory / pool_2x2");
            };
        Pos poolMin = null;
        if (ws == WaterSource.POOL_2X2) {
          poolMin = new Pos(reqInt(a, "pool_x"), reqInt(a, "pool_y"), reqInt(a, "pool_z"));
        } else if (a.has("pool_x") || a.has("pool_y") || a.has("pool_z")) {
          throw new IllegalArgumentException(
              "[ARGUMENT_INVALID] 仅 pool_2x2 模式可传 pool_x/pool_y/pool_z");
        }
        String coverSlab = optStr(a, "cover_slab");
        if (!coverSlab.isBlank()) {
          validateRegistryId(coverSlab, "cover_slab");
          if (!coverSlab.endsWith("_slab")) {
            throw new IllegalArgumentException("[ARGUMENT_INVALID] cover_slab 须为台阶方块ID(*_slab)");
          }
        }
        String shortageRaw = optStr(a, "on_water_shortage");
        WaterShortagePolicy shortage =
            switch (shortageRaw.isBlank() ? "pause" : shortageRaw.toLowerCase(Locale.ROOT)) {
              case "pause" -> WaterShortagePolicy.PAUSE;
              case "prepare_dirt" -> WaterShortagePolicy.PREPARE_DIRT;
              default ->
                  throw new IllegalArgumentException(
                      "[ARGUMENT_INVALID] on_water_shortage 必须为 pause / prepare_dirt");
            };
        yield new FarmGrid(
            box, targetY, crop, ws, poolMin, coverSlab, shortage, cutBlocks, expectedHash);
      }
    };
  }

  private static Set<String> parseCutBlocks(JsonObject a) {
    if (!a.has("cut_blocks") || a.get("cut_blocks").isJsonNull()) {
      return DEFAULT_NATURAL_CUT_BLOCKS;
    }
    Set<String> out = new LinkedHashSet<>();
    JsonElement el = a.get("cut_blocks");
    if (el.isJsonArray()) {
      for (JsonElement item : el.getAsJsonArray()) {
        if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString())
          throw new IllegalArgumentException("[ARGUMENT_INVALID] cut_blocks 须为字符串数组");
        addNarrowedCutBlock(out, item.getAsString());
      }
    } else if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
      for (String part : el.getAsString().split(",")) {
        if (!part.isBlank()) addNarrowedCutBlock(out, part);
      }
    } else {
      throw new IllegalArgumentException("[ARGUMENT_INVALID] cut_blocks 须为字符串数组或逗号分隔字符串");
    }
    if (out.isEmpty()) throw new IllegalArgumentException("[ARGUMENT_INVALID] cut_blocks 不能为空");
    return Collections.unmodifiableSet(out);
  }

  private static void addNarrowedCutBlock(Set<String> out, String rawId) {
    String id = rawId.trim().toLowerCase(Locale.ROOT);
    validateRegistryId(id, "cut_blocks");
    if (!DEFAULT_NATURAL_CUT_BLOCKS.contains(id)) {
      throw new IllegalArgumentException(
          "[ARGUMENT_INVALID] cut_blocks 仅可收窄内置天然地形白名单，禁止扩展：" + id);
    }
    out.add(id);
  }

  private static void validateRegistryId(String id, String field) {
    int colon = id.indexOf(':');
    if (colon <= 0 || colon == id.length() - 1 || id.indexOf(':', colon + 1) >= 0) {
      throw new IllegalArgumentException("[ARGUMENT_INVALID] " + field + " 须为完整命名空间ID如 minecraft:stone");
    }
    for (int i = 0; i < id.length(); i++) {
      char c = id.charAt(i);
      boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= '0' && c <= '9')
              || c == '_'
              || c == '-'
              || c == '/'
              || c == '.'
              || c == ':';
      if (!ok) {
        throw new IllegalArgumentException("[ARGUMENT_INVALID] " + field + " 含非法字符：" + id);
      }
    }
  }

  private static int reqInt(JsonObject a, String key) {
    if (!a.has(key) || a.get(key).isJsonNull())
      throw new IllegalArgumentException("[ARGUMENT_REQUIRED] 缺少整数参数：" + key);
    JsonElement el = a.get(key);
    if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("[ARGUMENT_INVALID] " + key + " 必须是整数");
    try {
      BigDecimal bd = el.getAsBigDecimal();
      return bd.intValueExact();
    } catch (Exception e) {
      throw new IllegalArgumentException("[ARGUMENT_INVALID] " + key + " 必须是有效32位整数");
    }
  }

  private static String reqStr(JsonObject a, String key) {
    String v = optStr(a, key);
    if (v.isBlank()) throw new IllegalArgumentException("[ARGUMENT_REQUIRED] 缺少参数：" + key);
    return v;
  }

  private static String optStr(JsonObject a, String key) {
    if (!a.has(key) || a.get(key).isJsonNull()) return "";
    JsonElement el = a.get(key);
    if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("[ARGUMENT_INVALID] " + key + " 必须是字符串");
    return el.getAsString().trim();
  }

  private static boolean optBool(JsonObject a, String key, boolean fallback) {
    if (!a.has(key) || a.get(key).isJsonNull()) return fallback;
    JsonElement el = a.get(key);
    if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean())
      throw new IllegalArgumentException("[ARGUMENT_INVALID] " + key + " 必须是布尔值");
    return el.getAsBoolean();
  }

  private static String sha256(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) sb.append(Character.forDigit((b >>> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
