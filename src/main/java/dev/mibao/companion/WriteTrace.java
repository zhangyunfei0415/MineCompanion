package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Appends the observed block state after a write and remembers it for the inspect_block settle
 * wait.
 */
final class WriteTrace {
  private WriteTrace() {}

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  static String dimension() {
    return mc().level.dimension().location().toString();
  }

  static long tick() {
    return mc().level.getGameTime();
  }

  static String after(BlockPos pos) {
    if (pos == null || mc().level == null || !mc().level.hasChunkAt(pos))
      return "；block_after=unknown_unloaded";
    return "；block_after=" + json(pos);
  }

  static JsonObject json(BlockPos pos) {
    JsonObject out = new JsonObject();
    out.addProperty("x", pos.getX());
    out.addProperty("y", pos.getY());
    out.addProperty("z", pos.getZ());
    var state = mc().level.getBlockState(pos);
    out.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    out.addProperty("state", state.toString());
    return out;
  }

  /**
   * Wraps a write tool result: success/partial receipts get block_after and feed the settle memory.
   */
  static Consumer<String> written(BlockPos pos, Consumer<String> done) {
    return text -> done.accept(append(pos, text));
  }

  /** Same for place_block, whose final position is only known through PlaceJob.lastPlaced. */
  static Consumer<String> placed(Consumer<String> done) {
    return text -> done.accept(append(PlaceJob.lastPlaced, text));
  }

  private static String append(BlockPos pos, String text) {
    if (text == null) return null;
    if (!text.startsWith("成功") && !text.startsWith("部分完成")) return text;
    boolean known = pos != null && mc().level != null && mc().level.hasChunkAt(pos);
    if (known) {
      var state = mc().level.getBlockState(pos);
      RecentWrites.record(
          dimension(), pos.getX(), pos.getY(), pos.getZ(), tick(), state.toString());
    }
    if (text.contains("block_after=")) return text;
    return text + after(pos);
  }
}
