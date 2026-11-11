package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Loaded-client support surfaces near a requested level, including fractional block heights. */
public final class ColumnProbe {
  public static BlockPos nearest(int x, int z, int reference, int range) {
    BlockPos best = null;
    double score = Double.MAX_VALUE;
    var level = Minecraft.getInstance().level;
    for (int y = Math.max(level.getMinBuildHeight() + 1, reference - range);
        y <= Math.min(level.getMaxBuildHeight() - 2, reference + range); y++) {
      BlockPos cell = new BlockPos(x, y, z);
      if (!level.hasChunkAt(cell) || !Navigator.stand(cell)) continue;
      double distance = Math.abs(Navigator.foot(cell).y - reference);
      if (distance < score) { score = distance; best = cell; }
    }
    return best;
  }

  public static JsonObject inspect(JsonObject args) {
    var mc = Minecraft.getInstance();
    int x = Agent.number(args, "x", mc.player.blockPosition().getX());
    int z = Agent.number(args, "z", mc.player.blockPosition().getZ());
    int reference = Agent.number(args, "reference_y", mc.player.blockPosition().getY());
    int range = Rules.bounded(Agent.number(args, "vertical_range", 16), 1, 64);
    int radius = Rules.bounded(Agent.number(args, "radius", 0), 0, 2);
    JsonArray columns = new JsonArray();
    for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
      JsonObject column = new JsonObject();
      column.addProperty("x", x + dx);
      column.addProperty("z", z + dz);
      boolean loaded = mc.level.hasChunkAt(new BlockPos(x + dx, reference, z + dz));
      column.addProperty("loaded", loaded);
      BlockPos cell = loaded ? nearest(x + dx, z + dz, reference, range) : null;
      column.addProperty("status", !loaded ? "unknown_unloaded" : cell == null ? "no_safe_surface_in_range" : "observed");
      if (cell != null) {
        column.addProperty("cell_y", cell.getY());
        column.addProperty("feet_y", Navigator.foot(cell).y);
        column.addProperty("support_y", cell.getY() - 1);
        column.addProperty("support", mc.level.getBlockState(cell.below()).toString());
      }
      columns.add(column);
    }
    JsonObject result = new JsonObject();
    result.add("columns", columns);
    result.addProperty("dimension", mc.level.dimension().location().toString());
    result.addProperty("source", "loaded_client_collision_shapes");
    result.addProperty("reference_y", reference);
    result.addProperty("vertical_range", range);
    result.addProperty("observed_tick", mc.level.getGameTime());
    return result;
  }

  private ColumnProbe() {}
}
