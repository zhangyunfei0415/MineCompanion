package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;

/** Incremental loaded-client survey. Every sample belongs to this request's fixed area. */
final class RegionSurvey {
  final BlockPos origin;
  final int radius, vertical, threshold;
  final List<BlockPos> dark = new ArrayList<>();
  final Set<BlockPos> torchSites = new HashSet<>();
  final JsonArray matches = new JsonArray();
  private final String filter;
  private final ResourceLocation id;
  private final boolean lights;
  private int cursor, unknown, observed, surfaces, matching;
  private int outsideWorld;
  private boolean complete;
  private final Map<BlockPos, Integer> lightSamples = new LinkedHashMap<>();
  private final Set<BlockPos> staleSamples = new HashSet<>();
  private Iterator<BlockPos> refresh;

  RegionSurvey(JsonObject args, boolean lights) {
    var player = Minecraft.getInstance().player;
    origin =
        new BlockPos(
            Agent.number(args, "x", player.getBlockX()),
            Agent.number(args, "y", player.getBlockY()),
            Agent.number(args, "z", player.getBlockZ()));
    if (origin.distSqr(player.blockPosition()) > 64 * 64)
      throw new IllegalArgumentException("[SURVEY_BOUNDS] 查询中心须在64格内");
    radius = SkillCatalog.bounded(args, "radius", 12, 1, 24);
    vertical = SkillCatalog.bounded(args, "vertical_range", 4, 1, 32);
    threshold = SkillCatalog.bounded(args, "min_light", 8, 1, 15);
    this.lights = lights;
    filter = lights ? "" : SkillCatalog.required(args, "block");
    id = ResourceLocation.tryParse(filter.startsWith("#") ? filter.substring(1) : filter);
    if (!lights
        && (id == null || !filter.startsWith("#") && !BuiltInRegistries.BLOCK.containsKey(id)))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] block须为注册ID或#标签");
  }

  boolean advance() {
    var level = Minecraft.getInstance().level;
    long start = System.nanoTime();
    if (refresh != null) {
      for (int budget = 0; refresh.hasNext() && budget < 256; budget++) {
        if (budget > 0 && System.nanoTime() - start >= 1_000_000) break;
        BlockPos pos = refresh.next();
        if (!level.hasChunkAt(pos)) {
          staleSamples.add(pos);
          continue;
        }
        staleSamples.remove(pos);
        lightSamples.put(pos, level.getBrightness(LightLayer.BLOCK, pos));
        if (!Blocks.TORCH.defaultBlockState().canSurvive(level, pos)
            || !level.getBlockState(pos).canBeReplaced()) torchSites.remove(pos);
      }
      if (refresh.hasNext()) return false;
      refresh = null;
      rebuildLightResult();
      complete = true;
      return true;
    }
    int side = radius * 2 + 1, height = vertical * 2 + 1;
    int total = side * side * height;
    for (int budget = 0; cursor < total && budget < 256; budget++) {
      if (budget > 0 && System.nanoTime() - start >= 1_000_000) break;
      int i = cursor++;
      BlockPos pos =
          origin.offset(i % side - radius, i / (side * side) - vertical, i / side % side - radius);
      if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
        outsideWorld++;
        continue;
      }
      if (!level.hasChunkAt(pos)) {
        unknown++;
        continue;
      }
      observed++;
      var state = level.getBlockState(pos);
      boolean match;
      if (lights) {
        if (!state.canBeReplaced()
            || !state.getFluidState().isEmpty()
            || level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty())
          continue;
        surfaces++;
        lightSamples.put(pos, level.getBrightness(LightLayer.BLOCK, pos));
        if (Blocks.TORCH.defaultBlockState().canSurvive(level, pos)) torchSites.add(pos);
        match = level.getBrightness(LightLayer.BLOCK, pos) < threshold;
        if (match) dark.add(pos);
      } else
        match =
            filter.startsWith("#")
                ? state.is(TagKey.create(Registries.BLOCK, id))
                : BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(id);
      if (!match) continue;
      matching++;
      if (matches.size() < 64) {
        var row = new JsonObject();
        row.addProperty("x", pos.getX());
        row.addProperty("y", pos.getY());
        row.addProperty("z", pos.getZ());
        row.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        row.addProperty("block_light", level.getBrightness(LightLayer.BLOCK, pos));
        row.addProperty("sky_light", level.getBrightness(LightLayer.SKY, pos));
        matches.add(row);
      }
    }
    complete = cursor >= total;
    return complete;
  }

  boolean complete() {
    return complete;
  }

  void refreshLight(BlockPos changed) {
    if (!lights || !complete) throw new IllegalStateException("照明缓存尚未就绪");
    refresh =
        lightSamples.keySet().stream()
            .filter(p -> p.distManhattan(changed) <= 15)
            .toList()
            .iterator();
    complete = false;
  }

  private void rebuildLightResult() {
    var level = Minecraft.getInstance().level;
    dark.clear();
    matches.asList().clear();
    lightSamples.forEach(
        (pos, light) -> {
          if (staleSamples.contains(pos) || light >= threshold) return;
          dark.add(pos);
          if (matches.size() >= 64) return;
          var row = new JsonObject();
          row.addProperty("x", pos.getX());
          row.addProperty("y", pos.getY());
          row.addProperty("z", pos.getZ());
          row.addProperty(
              "block",
              BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
          row.addProperty("block_light", light);
          row.addProperty("sky_light", level.getBrightness(LightLayer.SKY, pos));
          matches.add(row);
        });
    matching = dark.size();
  }

  JsonObject result() {
    var result = new JsonObject();
    var level = Minecraft.getInstance().level;
    result.addProperty("source", "loaded_client_region");
    result.addProperty("dimension", level.dimension().location().toString());
    result.addProperty("observed_tick", level.getGameTime());
    result.addProperty("observed_cells", observed);
    result.addProperty("unknown_cells", unknown + staleSamples.size());
    result.addProperty("scan_complete", complete);
    result.addProperty("scanned_cells", cursor);
    result.addProperty("total_cells", (radius * 2 + 1) * (radius * 2 + 1) * (vertical * 2 + 1));
    result.addProperty("outside_world_cells", outsideWorld);
    result.addProperty(
        "coverage_scope", lights ? "loaded_surfaces_above_collision_support" : "requested_volume");
    result.addProperty("torch_attachment_candidates", torchSites.size());
    result.addProperty("matching_cells", matching);
    result.addProperty("eligible_surfaces", surfaces);
    result.addProperty("minimum_block_light", threshold);
    result.addProperty("truncated", matching > matches.size());
    result.addProperty("truncated_cells", Math.max(0, matching - matches.size()));
    result.addProperty("count_exact", complete);
    result.addProperty(
        "suggested_radius", matching > matches.size() ? Math.max(1, radius / 2) : radius);
    result.addProperty("truncation_kind", "matching_results_not_returned");
    result.addProperty("truncated_count_exact_for_scanned_scope", true);
    result.addProperty(
        "suggested_action",
        matching > matches.size() ? "缩小半径或移动查询中心；未加载区域继续视为未知" : "当前扫描范围内结果完整；未加载区域仍未知");
    result.add("matches", matches.deepCopy());
    return result;
  }
}
