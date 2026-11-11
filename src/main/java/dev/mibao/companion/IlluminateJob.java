package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Reuses ordinary placement, then resamples actual client light after propagation. */
final class IlluminateJob extends Actions.Job {
  private final JsonObject args;
  private final int maximum;
  private final Set<BlockPos> attempted = new HashSet<>();
  private final JsonArray placements = new JsonArray();
  private RegionSurvey survey;
  private int placed, delay;

  IlluminateJob(JsonObject args, Consumer<String> done) {
    super(done);
    this.args = args.deepCopy();
    var origin = Minecraft.getInstance().player.blockPosition();
    for (String axis : List.of("x", "y", "z"))
      if (!this.args.has(axis)) this.args.addProperty(axis,
          axis.equals("x") ? origin.getX() : axis.equals("y") ? origin.getY() : origin.getZ());
    maximum = SkillCatalog.bounded(args, "count", 16, 1, 64);
    survey = new RegionSurvey(this.args, true);
  }

  @Override void tick() {
    if (++ticks > 24000) { complete("时间预算耗尽"); return; }
    if (delay-- > 0) return;
    Actions.status = "区域照明 · 已确认 " + placed + " / " + maximum;
    if (!survey.advance()) return;
    if (placed >= maximum) { complete("材料预算已完成"); return; }
    var mc = Minecraft.getInstance();
    BlockPos target = survey.dark.stream().filter(p -> !attempted.contains(p))
        .min(Comparator.comparingDouble(p -> p.distSqr(mc.player.blockPosition()))).orElse(null);
    if (target == null) { complete("区域扫描结束"); return; }
    attempted.add(target);
    if (attempted.size() > maximum * 3) { complete("可用放置候选已尝试"); return; }
    if (!mc.player.isCreative() && !mc.player.getInventory().contains(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TORCH))) {
      complete("缺少普通火把"); return;
    }
    Actions.job = new PlaceJob("minecraft:torch", target, result -> {
      if (Agent.epoch != epoch || !Agent.enabled) return;
      if (mc.level.getBlockState(target).is(Blocks.TORCH)
          || mc.level.getBlockState(target).is(Blocks.WALL_TORCH)) {
        placed++;
        placements.add(Store.GSON.toJsonTree(new int[] {target.getX(), target.getY(), target.getZ()}));
      }
      Actions.job = this;
      survey = new RegionSurvey(this.args, true);
      delay = 10;
    });
  }

  private void complete(String reason) {
    JsonObject evidence = survey.result();
    evidence.addProperty("placed_verified", placed);
    evidence.add("placements", placements);
    boolean covered = survey.dark.isEmpty() && evidence.get("unknown_cells").getAsInt() == 0;
    finish(new ActionResult(covered ? ActionResult.Status.verified : ActionResult.Status.partial,
        ActionResult.Code.NONE, reason + "；覆盖按实际客户端方块光核对", evidence).json());
  }
}
