package dev.mibao.companion;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.*;

/** Initial planting or bounded fertilization. Each use is verified by the shared operation. */
public final class FarmCareJob extends Actions.Job {
  private final CropSpec spec;
  private final Block crop;
  private final BlockPos center;
  private final boolean grow;
  private final int wanted, radius, maxUses;
  private final Set<BlockPos> finished = new HashSet<>();
  private final List<BlockPos> candidates = new ArrayList<>();
  private final TargetRecovery<BlockPos> recovery = new TargetRecovery<>(1, 12);
  private int scan, completed, uses;
  private BlockPos target;
  private ItemOnBlockOperation operation;
  private SupplyWait supply;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  private final FarmBatch<BlockPos> batch;

  public FarmCareJob(
      String crop, int count, int radius, boolean grow, int maxUses, Consumer<String> done) {
    super(done);
    spec = CropSpec.of(crop);
    this.crop = BuiltInRegistries.BLOCK.get(new ResourceLocation(spec.block()));
    this.wanted = count;
    this.radius = radius;
    this.grow = grow;
    this.maxUses = maxUses;
    batch = Agent.session == null ? null : Agent.session.farmBatch();
    center = mc().player.blockPosition();
    TaskReadiness.requireWorld(true);
  }

  private boolean eligible(BlockPos p) {
    if (!mc().level.hasChunkAt(p) || !mc().level.hasChunkAt(p.above())) return false;
    if (batch != null
        && !batch.accepts(
            grow ? FarmBatch.Stage.GROW : FarmBatch.Stage.PLANT, grow ? p : p.above()))
      return false;
    var s = mc().level.getBlockState(p);
    return grow ? s.is(crop) : s.is(Blocks.FARMLAND) && mc().level.getBlockState(p.above()).isAir();
  }

  private boolean available() {
    String id = grow ? "minecraft:bone_meal" : spec.seed();
    for (int i = 0; i < 36; i++) {
      var s = mc().player.getInventory().getItem(i);
      if (!s.isEmpty()
          && !s.hasTag()
          && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(id)) return true;
    }
    return false;
  }

  void tick() {
    if (++ticks > 7200) {
      result("达到6分钟预算");
      return;
    }
    Actions.status =
        (grow ? "骨粉催熟" : "播种") + " · 已核实 " + completed + " / " + wanted + "；使用 " + uses;
    if (operation != null) {
      int r = operation.tick();
      if (r == 0) return;
      if (r < 0) {
        result(operation.error + "；当前位置 " + target.toShortString() + "，未重放");
        return;
      }
      operation = null;
      if (!grow
          || mc().level.getBlockState(target).is(crop)
              && ((CropBlock) crop).isMaxAge(mc().level.getBlockState(target))) {
        confirmTarget();
      }
      target = null;
      return;
    }
    if (completed >= wanted) {
      result("对应方块状态及物品操作已核实");
      return;
    }
    if (uses >= maxUses) {
      result("达到物品使用次数上限");
      return;
    }
    if (mc().screen != null) {
      result("出现其他界面，停止");
      return;
    }
    int size = 2 * radius + 1, total = size * size * 7;
    if (scan == 0 && grow && batch != null && batch.bound(FarmBatch.Stage.GROW)) {
      candidates.addAll(batch.targets(FarmBatch.Stage.GROW));
      scan = total;
    }
    if (scan < total) {
      for (int i = 0; i < 128 && scan < total; i++, scan++) {
        var p =
            center.offset(
                scan % size - radius, scan / (size * size) - 3, (scan / size) % size - radius);
        if (p.distSqr(center) <= radius * radius && eligible(p)) candidates.add(p);
      }
      if (scan < total) return;
    }
    candidates.removeIf(p -> finished.contains(p) || !eligible(p));
    candidates.sort(Comparator.comparingDouble(p -> p.distSqr(mc().player.blockPosition())));
    target = null;
    for (var p : candidates)
      if (recovery.available(p, ticks)) {
        target = p;
        break;
      }
    if (target == null) {
      result("没有更多当前已加载且符合条件的目标");
      return;
    }
    if (grow && ((CropBlock) crop).isMaxAge(mc().level.getBlockState(target))) {
      confirmTarget();
      target = null;
      return;
    }
    if (!available()) {
      if (supply == null) supply = new SupplyWait(30);
      Actions.status = "农作等待补给 · " + (grow ? "minecraft:bone_meal" : spec.seed());
      if (supply.check(System.currentTimeMillis(), false) == SupplyWait.State.EXPIRED)
        result("30秒内没有补给");
      return;
    }
    supply = null;
    // Reject inaccessible candidates before any item use, then try another loaded candidate.
    var nav = new Navigator();
    boolean reachable = grow ? nav.startForInteraction(target) : nav.startForPlanting(target);
    nav.stop();
    if (!reachable) {
      recovery.reject(target, ticks, 40, "目标不可达");
      if (recovery.full()) result("不可达目标预算耗尽");
      return;
    }
    String item = grow ? "minecraft:bone_meal" : spec.seed();
    String fp =
        WorldSense.fingerprint(
            new net.minecraft.world.item.ItemStack(
                BuiltInRegistries.ITEM.get(new ResourceLocation(item))));
    uses++;
    operation = new ItemOnBlockOperation(target, fp, !grow);
  }

  private void result(String why) {
    finish(
        (completed >= wanted ? "成功：" : "部分完成：")
            + (grow ? "催熟" : "播种")
            + "已核实 "
            + completed
            + " / "
            + wanted
            + " 株，已尝试使用 "
            + uses
            + " 次。"
            + why);
  }

  private void confirmTarget() {
    completed++;
    finished.add(target);
    if (batch != null)
      batch.record(
          grow ? FarmBatch.Stage.GROW : FarmBatch.Stage.PLANT,
          (grow ? target : target.above()).immutable());
  }

  void stop() {
    if (operation != null) operation.stop();
    super.stop();
  }

  String interrupted() {
    return "农作准备已停止，已确认 "
        + completed
        + " 株；"
        + (target == null ? "" : target.toShortString() + " 处操作可能需重新核实");
  }
}
