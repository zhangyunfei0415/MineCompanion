package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Incremental loaded-region index with a small exposed-vein frontier. */
final class MiningSurvey {
  final BlockPos origin;
  private final MiningSpec spec;
  private final Block block;
  private final TagKey<Block> tag;
  private final Map<BlockPos, BlockState> candidates = new LinkedHashMap<>();
  private final Set<BlockPos> rejected = new HashSet<>();
  private int cursor;
  int unknown;

  MiningSurvey(MiningSpec spec, BlockPos origin) {
    this.spec = spec;
    this.origin = origin;
    var id = ResourceLocation.tryParse(spec.block().replaceFirst("^#", ""));
    if (spec.block().startsWith("#")) {
      tag = TagKey.create(Registries.BLOCK, id);
      block = null;
      if (BuiltInRegistries.BLOCK.getTag(tag).isEmpty())
        throw new IllegalArgumentException("[UNKNOWN_BLOCK_TAG] 当前客户端没有该方块标签");
    } else {
      block =
          BuiltInRegistries.BLOCK
              .getOptional(id)
              .orElseThrow(() -> new IllegalArgumentException("[UNKNOWN_BLOCK] 未知矿物方块ID"));
      tag = null;
    }
  }

  boolean contains(BlockPos pos) {
    return spec.contains(
        origin.getX(), origin.getY(), origin.getZ(), pos.getX(), pos.getY(), pos.getZ());
  }

  boolean matches(BlockState state) {
    return tag == null ? state.is(block) : state.is(tag);
  }

  List<Block> types() {
    if (tag == null) return List.of(block);
    return BuiltInRegistries.BLOCK.getTag(tag).orElseThrow().stream().map(h -> h.value()).toList();
  }

  boolean advance() {
    var level = Minecraft.getInstance().level;
    int side = 2 * spec.radius() + 1, total = side * side * (2 * spec.vertical() + 1);
    long started = System.nanoTime();
    for (int budget = 0; cursor < total && budget < 256; budget++) {
      if (budget > 0 && System.nanoTime() - started >= 1_000_000) break;
      int i = cursor++;
      var pos =
          origin.offset(
              i % side - spec.radius(),
              i / (side * side) - spec.vertical(),
              i / side % side - spec.radius());
      if (level.isOutsideBuildHeight(pos)) continue;
      if (!level.hasChunkAt(pos)) {
        unknown++;
        continue;
      }
      observe(pos);
    }
    return cursor >= total;
  }

  private void observe(BlockPos pos) {
    var level = Minecraft.getInstance().level;
    if (!contains(pos) || rejected.contains(pos) || !level.hasChunkAt(pos)) return;
    var state = level.getBlockState(pos);
    if (!matches(state) || state.hasBlockEntity() || !state.getFluidState().isEmpty()) return;
    boolean exposed = false;
    for (var direction : Direction.values()) {
      var neighbor = pos.relative(direction);
      if (!level.hasChunkAt(neighbor)) return;
      if (level.getBlockState(neighbor).isAir()) exposed = true;
    }
    if ((exposed || spec.mode().equals("baritone") && spec.terrain().equals("modify"))
        && candidates.size() < 4096) candidates.put(pos.immutable(), state);
  }

  List<BlockPos> targets(int maximum, BlockPos anchor) {
    var level = Minecraft.getInstance().level;
    candidates
        .entrySet()
        .removeIf(
            e ->
                rejected.contains(e.getKey())
                    || !level.hasChunkAt(e.getKey())
                    || !level.getBlockState(e.getKey()).equals(e.getValue()));
    return candidates.keySet().stream()
        .sorted(Comparator.comparingDouble(p -> p.distSqr(anchor)))
        .limit(maximum)
        .toList();
  }

  void completed(Collection<BlockPos> broken) {
    for (var pos : broken) {
      candidates.remove(pos);
      rejected.add(pos.immutable());
    }
    if (!spec.mode().equals("surface"))
      for (var pos : broken)
        for (int x = -1; x <= 1; x++)
          for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) observe(pos.offset(x, y, z));
  }

  void reject(Collection<BlockPos> positions) {
    positions.forEach(
        p -> {
          candidates.remove(p);
          rejected.add(p.immutable());
        });
  }

  int scanned() {
    return cursor;
  }
}
