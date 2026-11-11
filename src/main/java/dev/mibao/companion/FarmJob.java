package dev.mibao.companion;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/** Harvest and replant only vanilla crops through the normal player's game mode. */
public final class FarmJob extends Actions.Job {
  private final Navigator nav = new Navigator();
  private InventoryMove prepare;
  private final Block crop;
  private final Item seed;
  private final String cropId, seedId;
  private final int wanted, radius, oldSlot;
  private final BlockPos center;
  private final List<BlockPos> candidates = new ArrayList<>();
  private BlockPos target;
  private int scan, phase, delay, harvested, planted, seedBefore, retries, slot;
  private final TargetRecovery<BlockPos> recovery = new TargetRecovery<>(2, 12);
  private SupplyWait seedWait;
  private int seedWaits;
  private final FarmBatch<BlockPos> batch;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public FarmJob(String id, int wanted, int radius, Consumer<String> done) {
    super(done);
    this.wanted = wanted;
    this.radius = radius;
    cropId = id;
    batch = Agent.session == null ? null : Agent.session.farmBatch();
    center = mc().player.blockPosition();
    oldSlot = mc().player.getInventory().selected;
    switch (id) {
      case "minecraft:wheat" -> {
        crop = Blocks.WHEAT;
        seed = Items.WHEAT_SEEDS;
      }
      case "minecraft:carrots" -> {
        crop = Blocks.CARROTS;
        seed = Items.CARROT;
      }
      case "minecraft:potatoes" -> {
        crop = Blocks.POTATOES;
        seed = Items.POTATO;
      }
      case "minecraft:beetroots" -> {
        crop = Blocks.BEETROOTS;
        seed = Items.BEETROOT_SEEDS;
      }
      default -> throw new IllegalArgumentException("目前仅适配原版小麦、胡萝卜、马铃薯和甜菜");
    }
    seedId = BuiltInRegistries.ITEM.getKey(seed).toString();
    if (mc().gameMode.getPlayerMode() != GameType.SURVIVAL)
      throw new IllegalArgumentException("收割补种核验需要生存模式");
    if (mc().player.containerMenu != mc().player.inventoryMenu)
      throw new IllegalArgumentException("请先关闭容器");
    PlayerEvidence.begin();
  }

  private int seedSlot() {
    for (int i = 0; i < 9; i++) {
      var s = mc().player.getInventory().getItem(i);
      if (s.is(seed) && !s.hasTag()) return i;
    }
    return -1;
  }

  private boolean mature(BlockPos p) {
    if (batch != null && !batch.accepts(FarmBatch.Stage.HARVEST, p)) return false;
    if (!mc().level.hasChunkAt(p) || !mc().level.hasChunkAt(p.below())) return false;
    var s = mc().level.getBlockState(p);
    return s.is(crop)
        && ((CropBlock) crop).isMaxAge(s)
        && mc().level.getBlockState(p.below()).is(Blocks.FARMLAND);
  }

  private void result(String why) {
    finish(
        (planted >= wanted ? "成功：" : "部分完成：")
            + "已确认收割 "
            + harvested
            + " 株、补种 "
            + planted
            + " / "
            + wanted
            + " 株。"
            + why
            + " 产物尚未按拾取数量验收，可运行收集技能。");
  }

  private boolean prepareSeed() {
    nav.stop();
    boolean available = false;
    for (int i = 0; i < 36; i++) {
      var s = mc().player.getInventory().getItem(i);
      if (s.is(seed) && !s.hasTag()) {
        available = true;
        break;
      }
    }
    if (!available) {
      if (seedWait == null) {
        if (++seedWaits > 2) {
          result("等待种子达到两次预算。" + pending());
          return false;
        }
        seedWait = new SupplyWait(30);
      }
      Actions.status = "农作等待临时补给 · " + seedId + "；不会继续收割";
      if (seedWait.check(System.currentTimeMillis(), false) == SupplyWait.State.EXPIRED)
        result("30秒内未取得可用种子。" + pending());
      return false;
    }
    seedWait = null;
    prepare = InventoryMove.ensure(s -> s.is(seed) && !s.hasTag());
    return true;
  }

  private void skipUnharvested(String why) {
    nav.stop();
    recovery.reject(target, ticks, 40, why);
    if (recovery.full()) {
      result("12个农作目标不可达/变化，停止。" + why);
      return;
    }
    if (mature(target) && recovery.available(target, ticks + 40)) candidates.add(target);
    target = null;
    phase = 1;
  }

  void tick() {
    if (prepare != null) {
      Actions.status = "农作准备 · 从主背包取种子并核验";
      int r = prepare.tick();
      if (r < 0) {
        result(prepare.error + pending());
        return;
      }
      if (r > 0) {
        mc().player.getInventory().selected = prepare.readySlot;
        prepare = null;
      }
      return;
    }
    Actions.status = "收割补种 · 收割 " + harvested + " / 补种 " + planted + " / 目标 " + wanted;
    if (++ticks > 6000) {
      result("达到5分钟上限。" + pending());
      return;
    }
    if (mc().player.containerMenu != mc().player.inventoryMenu || mc().screen != null) {
      result("菜单改变，停止。" + pending());
      return;
    }
    if (planted >= wanted) {
      result("每株已核对幼苗更新和种子消耗。");
      return;
    }
    if (phase == 0) {
      int size = 2 * radius + 1, total = size * size * 7;
      if (scan == 0 && batch != null && batch.bound(FarmBatch.Stage.HARVEST)) {
        candidates.addAll(batch.targets(FarmBatch.Stage.HARVEST));
        scan = total;
      }
      for (int i = 0; i < 128 && scan < total; i++, scan++) {
        int x = scan % size - radius,
            z = (scan / size) % size - radius,
            y = scan / (size * size) - 3;
        BlockPos p = center.offset(x, y, z);
        if (p.distSqr(center) <= radius * radius && mature(p)) candidates.add(p);
      }
      if (scan < total) return;
      candidates.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
      phase = 1;
      return;
    }
    if (phase == 1) {
      if (candidates.isEmpty()) {
        result("没有更多已加载的成熟目标；未成熟作物保持原样。");
        return;
      }
      slot = seedSlot();
      if (slot < 0 && harvested > planted) {
        prepareSeed();
        return;
      }
      if (mc().player.getInventory().getFreeSlot() < 0) {
        result("背包没有空位，请先存货。");
        return;
      }
      target = null;
      for (var p : candidates)
        if (recovery.available(p, ticks)) {
          target = p;
          break;
        }
      if (target == null) {
        if (recovery.waiting(ticks)) return;
        result("已无可重试的成熟目标。" + recovery.lastReason());
        return;
      }
      candidates.remove(target);
      if (!mature(target)) {
        target = null;
        return;
      }
      if (!nav.startForInteraction(target)) {
        skipUnharvested("目标不可达：" + nav.error());
        return;
      }
      phase = 2;
      delay = 0;
      return;
    }
    if (!mc().level.hasChunkAt(target)) {
      result("目标已卸载。" + pending());
      return;
    }
    if (phase == 6) {
      int r = nav.tick();
      if (r < 0) {
        result("补种站位不可达：" + nav.error() + pending());
        return;
      }
      if (r > 0) phase = 4;
      return;
    }
    if (phase == 2) {
      int r = nav.tick();
      if (r < 0) {
        skipUnharvested("路线受阻：" + nav.error());
        return;
      }
      if (r == 0) return;
      if (!mature(target)) {
        target = null;
        phase = 1;
        return;
      }
      slot = seedSlot();
      if (slot < 0 && harvested > planted) {
        prepareSeed();
        return;
      }
      if (slot >= 0) mc().player.getInventory().selected = slot;
      PlayerEvidence.forget(target);
      phase = 3;
      delay = 0;
    }
    if (phase == 3) {
      var confirmed = PlayerEvidence.block(target);
      if (confirmed != null && confirmed.isAir() && mc().level.getBlockState(target).isAir()) {
        harvested++;
        mc().gameMode.stopDestroyBlock();
        phase = nav.startForPickup(new net.minecraft.world.phys.AABB(target)) ? 7 : 4;
        delay = 0;
        retries = 0;
        return;
      }
      if (++delay > 100) {
        result("收割未取得服务器方块确认。" + pending());
        return;
      }
      if (!mature(target)) {
        mc().gameMode.stopDestroyBlock();
        return;
      }
      var hit = Reach.hit(target);
      if (hit == null) {
        result("收割目标不再可见。" + pending());
        return;
      }
      Actions.look(hit.getLocation());
      if (delay == 1) mc().gameMode.startDestroyBlock(target, hit.getDirection());
      else mc().gameMode.continueDestroyBlock(target, hit.getDirection());
      mc().player.swing(InteractionHand.MAIN_HAND);
      return;
    }
    if (phase == 7) {
      int reached = nav.tick();
      if (reached == 0) return;
      nav.stop();
      phase = 4;
      return;
    }
    if (phase == 4) {
      if (!mc().level.getBlockState(target).isAir()
          || !mc().level.getBlockState(target.below()).is(Blocks.FARMLAND)) {
        result("目标或耕地已变化。" + pending());
        return;
      }
      slot = seedSlot();
      if (slot < 0) {
        prepareSeed();
        return;
      }
      BlockHitResult hit = Reach.top(target.below());
      if (hit == null) {
        if (!nav.startForPlanting(target.below())) {
          result("看不到耕地上表面：" + nav.error() + pending());
          return;
        }
        phase = 6;
        return;
      }
      mc().player.getInventory().selected = slot;
      seedBefore = PlayerEvidence.total(PlayerEvidence.actual(seedId));
      PlayerEvidence.forget(target);
      Actions.look(hit.getLocation());
      mc().gameMode.useItemOn(mc().player, InteractionHand.MAIN_HAND, hit);
      mc().player.swing(InteractionHand.MAIN_HAND);
      phase = 5;
      delay = 0;
      return;
    }
    if (phase == 5) {
      var server = PlayerEvidence.block(target);
      var actual = mc().level.getBlockState(target);
      boolean young =
          server != null
              && server.equals(actual)
              && server.is(crop)
              && ((CropBlock) crop).getAge(server) == 0;
      if (DeltaProof.planted(
          young,
          seedBefore,
          PlayerEvidence.total(PlayerEvidence.actual(seedId)),
          PlayerEvidence.total(PlayerEvidence.confirmed(seedId)))) {
        planted++;
        if (batch != null) batch.record(FarmBatch.Stage.HARVEST, target.immutable());
        WorldSense.record(target, actual);
        Store.save();
        target = null;
        phase = 1;
        return;
      }
      if (++delay > 100) {
        if (retries++ < 1
            && mc().level.getBlockState(target).isAir()
            && PlayerEvidence.total(PlayerEvidence.actual(seedId)) == seedBefore) {
          phase = 4;
          return;
        }
        result("补种未取得幼苗与种子消耗的共同证明。" + pending());
      }
    }
  }

  private String pending() {
    return target != null && (phase >= 3 || harvested > planted)
        ? "请核实当前操作位置 " + target.toShortString() + "；收割或补种可能尚未确认。"
        : "";
  }

  @Override
  String interrupted() {
    return "农作已停止；已确认收割 " + harvested + "、补种 " + planted + "。" + pending();
  }

  @Override
  void stop() {
    if (prepare != null) prepare.stop();
    nav.stop();
    if (mc().player != null) mc().player.getInventory().selected = oldSlot;
    super.stop();
  }
}
