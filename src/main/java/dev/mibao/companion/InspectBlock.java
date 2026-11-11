package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;

/**
 * inspect_block: reads one loaded block, waiting out a just-finished write on the same coordinate.
 */
public final class InspectBlock extends Actions.Job {
  private final BlockPos pos;
  private final int settle;
  private int waited;

  private InspectBlock(BlockPos pos, int settle, Consumer<String> done) {
    super(done);
    this.pos = pos;
    this.settle = settle;
  }

  static void run(JsonObject args, Consumer<String> done) {
    BlockPos pos = Actions.target(args);
    int settle = SkillCatalog.bounded(args, "settleTicks", 2, 0, 20);
    if (Actions.job != null) {
      done.accept(receipt(pos, settle, 0));
      return;
    }
    Actions.job = new InspectBlock(pos, settle, done);
  }

  @Override
  void tick() {
    Actions.status = "检查方块 · 等待写入同步";
    int wait =
        RecentWrites.waitTicks(
            WriteTrace.dimension(), pos.getX(), pos.getY(), pos.getZ(), WriteTrace.tick(), settle);
    if (waited < wait) {
      waited++;
      return;
    }
    finish(receipt(pos, settle, waited));
  }

  @Override
  String interrupted() {
    return "方块检查已停止；未完成的写入等待不会重放。";
  }

  static String receipt(BlockPos pos, int settle, int waited) {
    JsonObject out = WorldSense.inspect(pos);
    String dimension = WriteTrace.dimension();
    long now = WriteTrace.tick();
    var recent = RecentWrites.describe(dimension, pos.getX(), pos.getY(), pos.getZ(), now);
    for (var e : recent.entrySet()) out.add(e.getKey(), e.getValue());
    out.addProperty("settle_ticks", settle);
    out.addProperty("waited_ticks", waited);
    out.addProperty(
        "pending",
        RecentWrites.waitTicks(dimension, pos.getX(), pos.getY(), pos.getZ(), now, settle) > 0);
    return out.toString();
  }
}
