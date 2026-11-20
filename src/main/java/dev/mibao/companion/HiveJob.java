package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.CampfireBlock;

/** One checked harvest of a smoked vanilla hive; item and hive evidence are separate. */
final class HiveJob extends Actions.Job {
  private final BlockPos hive;
  private final String tool, product;

  HiveJob(JsonObject args, Consumer<String> done) {
    super(done);
    TaskReadiness.requireWorld(true);
    hive = Actions.target(args);
    String mode = Agent.str(args, "mode", "bottle");
    if (!mode.equals("bottle") && !mode.equals("shears"))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] mode=bottle/shears");
    tool = mode.equals("bottle") ? "minecraft:glass_bottle" : "minecraft:shears";
    product = mode.equals("bottle") ? "minecraft:honey_bottle" : "minecraft:honeycomb";
  }

  @Override void tick() {
    if (!world.hasChunkAt(hive)) {
      result("UNLOADED_TARGET", "蜂箱所在区块未加载", false, 0);
      return;
    }
    var state = world.getBlockState(hive);
    if (!(state.getBlock() instanceof BeehiveBlock)
        || state.getValue(BeehiveBlock.HONEY_LEVEL) != 5) {
      result("HIVE_NOT_READY", "观察不到蜜量已满的原版蜂箱或蜂巢", false, 0);
      return;
    }
    if (!CampfireBlock.isSmokeyPos(world, hive)) {
      result("HIVE_SMOKE_REQUIRED", "未观察到营火烟雾，等待玩家准备采蜜位置", false, 0);
      return;
    }
    int before = PlayerEvidence.total(PlayerEvidence.actual(product));
    var args = new JsonObject();
    args.addProperty("x", hive.getX());
    args.addProperty("y", hive.getY());
    args.addProperty("z", hive.getZ());
    args.addProperty("item", tool);
    child("use_item_on_block", args, receipt -> {
      var confirmed = PlayerEvidence.block(hive);
      boolean harvested = confirmed != null && confirmed.equals(world.getBlockState(hive))
          && confirmed.getBlock() == state.getBlock() && confirmed.getValue(BeehiveBlock.HONEY_LEVEL) == 0;
      int gained = Math.max(0, PlayerEvidence.total(PlayerEvidence.actual(product)) - before);
      result(harvested && gained > 0 && !Rules.actionFailed(receipt) ? "NONE" : "HIVE_PICKUP_UNCONFIRMED",
          receipt, harvested, gained);
    });
  }

  private void result(String code, String detail, boolean harvested, int gained) {
    var result = new JsonObject();
    result.addProperty("status", code.equals("NONE") ? "verified" : "partial");
    result.addProperty("business_success", code.equals("NONE"));
    result.addProperty("reason_code", code);
    result.addProperty("detail", Rules.clean(detail, 400));
    result.addProperty("harvest_verified", harvested);
    result.addProperty("inventory_item", product);
    result.addProperty("observed_inventory_increase", gained);
    finish(result.toString());
  }
}
