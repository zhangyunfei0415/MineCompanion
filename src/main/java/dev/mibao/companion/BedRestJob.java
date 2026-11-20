package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;

/** One normal bed interaction, with sleep evidence independent of setting a respawn point. */
final class BedRestJob extends Actions.Job {
  private final BlockPos bed;

  BedRestJob(JsonObject args, Consumer<String> done) {
    super(done);
    TaskReadiness.requireWorld(true);
    if (!world.dimensionType().bedWorks())
      throw new IllegalArgumentException("[BED_UNSAFE_DIMENSION] 当前维度不可使用床");
    bed = Actions.target(args);
    if (!world.hasChunkAt(bed) || !(world.getBlockState(bed).getBlock() instanceof BedBlock))
      throw new IllegalArgumentException("[BED_NOT_OBSERVED] 指定位置没有已观察的床");
  }

  @Override void tick() {
    var state = world.getBlockState(bed);
    if (!(state.getBlock() instanceof BedBlock)) {
      result("BED_NOT_OBSERVED", "床已改变");
      return;
    }
    if (state.getValue(BedBlock.OCCUPIED)) {
      result("BED_OCCUPIED", "床已经被占用");
      return;
    }
    var args = new JsonObject();
    args.addProperty("x", bed.getX());
    args.addProperty("y", bed.getY());
    args.addProperty("z", bed.getZ());
    child("use_item_on_block", args, reply -> {
      var p = Minecraft.getInstance().player;
      if (p.isSleeping() && p.getSleepingPos().filter(pos -> pos.distSqr(bed) <= 1).isPresent())
        result("NONE", "已躺在指定床；多人跳夜仍取决于服务器规则");
      else result("SLEEP_UNCONFIRMED", "未观察到持续睡眠，白天设置重生点需单独核实。" + reply);
    });
  }

  private void result(String code, String detail) {
    var out = new JsonObject();
    out.addProperty("status", code.equals("NONE") ? "verified" : "partial");
    out.addProperty("business_success", code.equals("NONE"));
    out.addProperty("reason_code", code);
    out.addProperty("detail", Rules.clean(detail, 350));
    out.addProperty("bed", bed.toShortString());
    finish(out.toString());
  }
}
