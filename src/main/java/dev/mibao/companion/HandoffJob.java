package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Walk safely near an authorized owner/member, aim at torso height, drop item(s), and verify. */
public final class HandoffJob extends Actions.Job {
  private final String itemFilter, targetPlayerName;
  private final boolean wholeStack;
  private final Navigator nav = new Navigator();
  private final AimGate aim = new AimGate();
  private final InventorySession verify = new InventorySession(false);
  private InventoryMove prepare;
  private int stage, settle, beforeTotal, requested;
  private String fingerprint = "", targetDisplay = "";
  private ItemStack expected = ItemStack.EMPTY;

  public HandoffJob(
      String itemFilter, boolean wholeStack, String targetPlayerName, Consumer<String> done) {
    super(done);
    this.itemFilter = itemFilter;
    this.wholeStack = wholeStack;
    this.targetPlayerName = targetPlayerName == null ? "" : targetPlayerName.trim();
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  private Player resolveTarget() {
    return OwnerContext.findPlayer(mc(), targetPlayerName);
  }

  private boolean authorized(Player target) {
    if (target == null) return false;
    String name = target.getGameProfile().getName();
    if (Store.config.owner != null && name.equalsIgnoreCase(Store.config.owner.trim())) return true;
    if (target.getUUID().equals(FollowControl.currentTargetUuid())) return true;
    return CommunityTasks.member(target.getUUID().toString(), name) != null;
  }

  private BlockPos findStandSpot(Player target) {
    BlockPos center = target.blockPosition();
    BlockPos best = null;
    double bestDist = Double.MAX_VALUE;
    for (int dx = -3; dx <= 3; dx++) {
      for (int dz = -3; dz <= 3; dz++) {
        double d2 = dx * dx + dz * dz;
        if (d2 < 4.0 || d2 > 12.5) continue;
        for (int dy = -1; dy <= 1; dy++) {
          BlockPos p = center.offset(dx, dy, dz);
          if (!Navigator.stand(p) || isHazardousDropZone(p, center)) continue;
          double playerDist = p.distSqr(mc().player.blockPosition());
          if (playerDist < bestDist) {
            bestDist = playerDist;
            best = p;
          }
        }
      }
    }
    return best;
  }

  private static boolean isHazardousDropZone(BlockPos from, BlockPos to) {
    var level = mc().level;
    if (level == null) return true;
    BlockPos mid =
        new BlockPos(
            (from.getX() + to.getX()) / 2,
            Math.min(from.getY(), to.getY()),
            (from.getZ() + to.getZ()) / 2);
    for (BlockPos check : new BlockPos[] {mid, mid.below(), to}) {
      if (!level.hasChunkAt(check)) return true;
      var s = level.getBlockState(check);
      if (s.getFluidState().is(FluidTags.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE))
        return true;
    }
    return false;
  }

  @Override
  void tick() {
    var mc = mc();
    if (++ticks > 600) {
      finish("失败：[HANDOFF_TIMEOUT] 递送超时，请确认主人位置与通道。");
      return;
    }
    if (mc.player == null || mc.level == null) {
      finish("失败：[WRONG_CONTEXT] 世界未就绪。");
      return;
    }
    if (mc.screen != null
        || mc.player.containerMenu != mc.player.inventoryMenu
        || !mc.player.containerMenu.getCarried().isEmpty()) {
      finish("失败：递送物品前请关闭容器界面并收好鼠标物品。");
      return;
    }
    if (mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
      finish("失败：[MODE_UNSUPPORTED] 旁观模式无法丢出物品。");
      return;
    }
    Player target = resolveTarget();
    if (target == null || !authorized(target)) {
      finish("失败：[TARGET_UNAVAILABLE] 未在附近找到已授权的主人或跟随玩家。");
      return;
    }
    targetDisplay = target.getGameProfile().getName();
    Actions.status = "递交物品给 " + targetDisplay + " · " + itemFilter;

    if (stage == 0) {
      prepare = InventoryMove.ensure(s -> Actions.match(s, itemFilter));
      stage = 1;
    }
    if (stage == 1) {
      int r = prepare.tick();
      if (r < 0) {
        finish("失败：" + prepare.error);
        return;
      }
      if (r == 0) return;
      mc.player.getInventory().selected = prepare.readySlot;
      double dist = mc.player.distanceTo(target);
      if (dist <= 3.6 && dist >= 1.5 && mc.player.hasLineOfSight(target)) {
        stage = 3;
      } else {
        BlockPos spot = findStandSpot(target);
        if (spot == null || !nav.startExact(spot)) {
          if (dist <= 3.6 && mc.player.hasLineOfSight(target) && Navigator.grounded()) {
            stage = 3;
            return;
          }
          finish("失败：[NO_SAFE_SPOT] 附近找不到安全干燥的递送站位：" + nav.error());
          return;
        }
        stage = 2;
      }
      return;
    }
    if (stage == 2) {
      double dist = mc.player.distanceTo(target);
      if (dist <= 3.6 && dist >= 1.5 && Navigator.grounded() && mc.player.hasLineOfSight(target)) {
        nav.stop();
        stage = 3;
        return;
      }
      int r = nav.tick();
      if (r < 0) {
        finish("失败：" + nav.error());
        return;
      }
      if (r > 0) stage = 3;
      return;
    }
    if (stage == 3) {
      if (isHazardousDropZone(mc.player.blockPosition(), target.blockPosition())) {
        finish("失败：[HAZARD_DROP_ZONE] 主人旁边存在岩浆/火焰，已阻止丢出以防烧毁。");
        return;
      }
      Vec3 aimPoint = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.65, target.getZ());
      if (!aim.ready(aimPoint)) return;
      if (mc.player.getInventory().selected != prepare.readySlot) {
        // drop() only sends a player-action packet; the carried-slot packet is sent by the next
        // tick's gameMode.tick(). Switch now and drop next tick so the server drops this stack.
        mc.player.getInventory().selected = prepare.readySlot;
        return;
      }
      ItemStack hand = mc.player.getMainHandItem();
      if (hand.isEmpty() || !Actions.match(hand, itemFilter)) {
        finish("失败：[ITEM_CHANGED] 手持物品发生变化。");
        return;
      }
      expected = hand.copy();
      fingerprint = WorldSense.fingerprint(expected);
      requested = wholeStack ? expected.getCount() : 1;
      beforeTotal = PlayerEvidence.total(PlayerEvidence.actual(fingerprint));
      expected.shrink(requested);
      PlayerEvidence.begin();
      mc.player.drop(wholeStack);
      stage = 4;
      settle = 0;
      return;
    }
    if (++settle < 10) return;
    int r = verify.tick();
    if (r < 0) {
      finish("部分完成：已投递物品，但同步核验超时：" + verify.error);
      return;
    }
    if (r == 0) return;
    verify.close();
    int afterTotal = PlayerEvidence.total(PlayerEvidence.actual(fingerprint));
    boolean leftSelf = beforeTotal - afterTotal == requested;
    boolean stillOnGround =
        !mc.level
            .getEntitiesOfClass(
                ItemEntity.class,
                target.getBoundingBox().inflate(3.5),
                e -> e.isAlive() && WorldSense.fingerprint(e.getItem()).equals(fingerprint))
            .isEmpty();
    var out = new JsonObject();
    out.addProperty("target_player", targetDisplay);
    out.addProperty("item", fingerprint);
    out.addProperty("delivered_count", beforeTotal - afterTotal);
    out.addProperty("requested", requested);
    out.addProperty("dropped_near_owner", leftSelf);
    out.addProperty("pickup_delay_ticks", 40);
    out.addProperty("owner_pickup_likely", leftSelf && !stillOnGround);
    out.addProperty("still_on_ground", stillOnGround);
    finish(
        (leftSelf
                ? "成功：已将 "
                    + (beforeTotal - afterTotal)
                    + " 个 "
                    + fingerprint
                    + " 投递给 "
                    + targetDisplay
                    + (stillOnGround ? "（原版丢出带 2 秒拾取冷却，物品已在脚边待拾取）" : "")
                    + "；"
                : "部分完成：库存变化未完全核实；")
            + out);
  }

  @Override
  void stop() {
    nav.stop();
    if (prepare != null) prepare.stop();
    super.stop();
  }

  @Override
  String interrupted() {
    return "递送物品已中断，请核对地上与双方背包。";
  }
}
