package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;

/** Explicit same-world recovery of a named drop near the recorded death point. */
final class DeathReturnJob extends Actions.Job {
  private final BlockPos death;
  private final String item;
  private final int count;
  private boolean arrived;

  DeathReturnJob(JsonObject args, Consumer<String> done) {
    super(done);
    TaskReadiness.requireWorld(true);
    var recorded = Store.data.checkpoints.get("death:last");
    if (recorded == null)
      throw new IllegalArgumentException("[DEATH_POINT_UNKNOWN] 这个世界没有死亡点记录");
    if (!dimension.equals(Agent.str(recorded, "dimension", "")))
      throw new IllegalArgumentException("[TARGET_OTHER_DIMENSION] 请先回到死亡点所在维度");
    death = BlockPos.containing(recorded.get("x").getAsDouble(), recorded.get("y").getAsDouble(),
        recorded.get("z").getAsDouble());
    item = SkillCatalog.required(args, "item");
    if (item.equals("*"))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 请指定要找的物品 ID 或准确指纹");
    count = SkillCatalog.bounded(args, "count", 1, 1, 64);
  }

  @Override void tick() {
    if (!arrived) {
      var target = new JsonObject();
      target.addProperty("x", death.getX());
      target.addProperty("y", death.getY());
      target.addProperty("z", death.getZ());
      child("go_to", target, reply -> {
        if (!StepEvidence.complete("go_to", reply)) result("DEATH_POINT_UNREACHABLE", reply, 0);
        else arrived = true;
      });
      return;
    }
    var args = new JsonObject();
    args.addProperty("item", item);
    args.addProperty("count", count);
    args.addProperty("radius", 5);
    CollectionJob.lastReceipt = null;
    child("collect_items", args, reply -> {
      var pickup = CollectionJob.lastReceipt;
      int gained = pickup != null && pickup.epoch() == epoch && pickup.inventoryVerified() ? pickup.gained() : 0;
      result(gained >= count && !Rules.actionFailed(reply) ? "NONE" : "RECOVERY_INCOMPLETE", reply, gained);
    });
  }

  private void result(String code, String detail, int gained) {
    var out = new JsonObject();
    out.addProperty("status", code.equals("NONE") ? "verified" : "partial");
    out.addProperty("business_success", code.equals("NONE"));
    out.addProperty("reason_code", code);
    out.addProperty("detail", Rules.clean(detail, 350));
    out.addProperty("item", item);
    out.addProperty("confirmed_inventory_increase", gained);
    out.addProperty("death_point", death.toShortString());
    out.addProperty("drop_origin", "unverified");
    finish(out.toString());
  }
}
