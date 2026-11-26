package dev.mibao.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** One real targeted use, no blind replay. Shared by direct tools and local farm jobs. */
public final class ItemOnBlockOperation {
  private final BlockPos target;
  private final String filter;
  private final boolean top;
  private final boolean sneak;
  private final boolean emptyHand;
  private final net.minecraft.core.Direction explicitFace;
  private final boolean explicitSubHit;
  private final FaceHitMath.UV targetUv;
  private final SneakHold sneakHold = new SneakHold();
  private InteractionOutcome outcome;
  private final AimGate aim = new AimGate();
  private final Navigator nav = new Navigator();
  private InventoryMove prepare;
  private final InventorySession initial = new InventorySession(false),
      verify = new InventorySession(false);
  private final int oldSlot;
  private int phase, ticks, delay, beforeCount, sneakWarmup, velocitySettleTicks;
  private boolean sent, effect, targetEffect, adjacentPlaced, menu;
  private ItemStack before;
  private BlockState blockBefore, aboveBefore, placedBefore;
  private BlockPos placedPos;
  public String detail = "", error = "";
  private Item bucketResult;
  private int bucketResultBefore;

  private int itemCount(Item item) {
    int n = 0;
    for (int i = 0; i < 41; i++) {
      var s = mc().player.getInventory().getItem(i);
      if (s.is(item)) n += s.getCount();
    }
    return n;
  }

  private long usedAt;
  private net.minecraft.world.phys.Vec3 usedPosition;
  private String machineBefore;
  private long machineRevision;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public ItemOnBlockOperation(BlockPos target, String item, boolean top) {
    this(target, item, top, false);
  }

  public ItemOnBlockOperation(BlockPos target, String item, boolean top, boolean sneak) {
    this(target, item, top, sneak, 3);
  }

  public ItemOnBlockOperation(
      BlockPos target, String item, boolean top, boolean sneak, int stanceRadius) {
    this(target, item, top ? "top" : "any", sneak, stanceRadius, null, null, "");
  }

  public ItemOnBlockOperation(
      BlockPos target,
      String item,
      String face,
      boolean sneak,
      int stanceRadius,
      Double hitU,
      Double hitV,
      String subSlot) {
    this.target = target.immutable();
    this.filter = item == null ? "" : item;
    this.explicitSubHit = FaceHitMath.hasExplicitSubHit(hitU, hitV, subSlot);
    this.targetUv = FaceHitMath.resolveUV(hitU, hitV, subSlot);
    String f = face == null || face.isBlank() ? "any" : face.strip().toLowerCase(java.util.Locale.ROOT);
    if (f.equals("any")) {
      this.top = false;
      this.explicitFace = null;
    } else if (f.equals("top") && !this.explicitSubHit) {
      this.top = true;
      this.explicitFace = null;
    } else {
      var parsed = net.minecraft.core.Direction.byName(f.equals("top") ? "up" : f);
      if (parsed == null)
        throw new IllegalArgumentException("[INVALID_ARGUMENT] face须为 any/top/up/down/north/south/east/west");
      this.top = false;
      this.explicitFace = parsed;
    }
    this.sneak = sneak;
    this.emptyHand = this.filter.isBlank() || this.filter.equals("empty_hand");
    oldSlot = mc().player.getInventory().selected;
    nav.exactStance(true).maxRadius(stanceRadius);
  }

  private static String quantityIdentity(ItemStack stack) {
    var copy = stack.copy();
    if (copy.isDamageableItem() && copy.hasTag()) copy.getTag().remove("Damage");
    return WorldSense.fingerprint(copy);
  }

  private int count() {
    if (before == null || before.isEmpty()) return 0;
    int n = 0;
    String fp = quantityIdentity(before);
    for (int i = 0; i < 41; i++) {
      var s = mc().player.getInventory().getItem(i);
      if (!s.isEmpty() && quantityIdentity(s).equals(fp)) n += s.getCount();
    }
    return n;
  }

  public int tick() {
    if (++ticks > 1400)
      return fail(
          "[ITEM_ON_BLOCK_TIMEOUT] 已停止；" + (sent ? "动作已发出，先重新观察，不可盲目重放；" + context() : "尚未使用物品"));
    if (!mc().level.hasChunkAt(target) || !mc().level.hasChunkAt(target.above()))
      return fail("[UNKNOWN_UNLOADED] 目标区块未加载");
    if (phase == 0) {
      if (mc().player.containerMenu != mc().player.inventoryMenu)
        return fail("[WRONG_CONTEXT] 请先关闭其他容器");
      if (prepare == null && !emptyHand) {
        for (int i = 0; i < 9; i++)
          if (Actions.match(mc().player.getInventory().getItem(i), filter)) {
            mc().player.getInventory().selected = i;
            phase = 1;
            return 0;
          }
      }
      if (prepare == null)
        prepare =
            emptyHand
                ? InventoryMove.emptyHand()
                : InventoryMove.ensure(s -> Actions.match(s, filter));
      int r = prepare.tick();
      if (r < 0) return fail(prepare.error);
      if (r == 0) return 0;
      mc().player.getInventory().selected = prepare.readySlot;
      prepare = null;
      phase = 1;
      return 0;
    }
    if (phase == 1) {
      int r = initial.tick();
      if (r < 0) return fail(initial.error);
      if (r == 0) return 0;
      initial.close();
      if (mc().screen != null) return fail("[WRONG_CONTEXT] 背包无法安全关闭");
      before = mc().player.getMainHandItem().copy();
      if (emptyHand ? !before.isEmpty() : !Actions.match(before, filter))
        return fail("[ITEM_CHANGED] 手持物品变化");
      boolean started =
          explicitFace != null
              ? nav.startForFace(target, explicitFace)
              : (top ? nav.startForPlanting(target) : nav.startForInteraction(target));
      if (!started) return fail(navFailure());
      phase = 2;
      return 0;
    }
    if (phase == 2) {
      int r = nav.tick();
      if (r < 0) return fail(navFailure());
      if (r == 0) return 0;
      nav.stop();
      if (mc().player.getDeltaMovement().horizontalDistanceSqr() > 0.0004
          && ++velocitySettleTicks < 6) {
        return 0;
      }
      if (sneak) {
        sneakHold.hold();
        if (++sneakWarmup < 2) return 0;
      }
      if (mc().screen != null || mc().player.containerMenu != mc().player.inventoryMenu)
        return fail("[MENU_CHANGED] 使用前菜单变化");
      if (emptyHand
          ? !mc().player.getMainHandItem().isEmpty()
          : !WorldSense.fingerprint(before)
              .equals(WorldSense.fingerprint(mc().player.getMainHandItem())))
        return fail("[ITEM_CHANGED] 手持物品变化");
      BlockHitResult hit =
          explicitFace != null
              ? (explicitSubHit
                  ? Reach.faceAt(
                      target,
                      mc().player.getEyePosition(),
                      explicitFace,
                      targetUv.u(),
                      targetUv.v())
                  : Reach.faceFrom(target, mc().player.getEyePosition(), explicitFace))
              : (top ? Reach.top(target) : Reach.hit(target));
      if (hit == null) return fail("[NO_VISIBLE_FACE] 没有可见且在距离内的交互面");
      if (!aim.ready(hit.getLocation())) return 0;
      placedPos = hit.getBlockPos().relative(hit.getDirection());
      blockBefore = mc().level.getBlockState(target);
      aboveBefore = mc().level.getBlockState(target.above());
      placedBefore = mc().level.hasChunkAt(placedPos) ? mc().level.getBlockState(placedPos) : aboveBefore;
      machineBefore = MachineObservation.state(target);
      machineRevision = MachineObservation.revision(target);
      if (before.is(Items.BUCKET)) {
        var fluid = mc().level.getFluidState(target);
        bucketResult =
            fluid.is(net.minecraft.tags.FluidTags.WATER)
                ? Items.WATER_BUCKET
                : fluid.is(net.minecraft.tags.FluidTags.LAVA) ? Items.LAVA_BUCKET : null;
      } else if (before.is(Items.WATER_BUCKET) || before.is(Items.LAVA_BUCKET))
        bucketResult = Items.BUCKET;
      if (bucketResult != null) bucketResultBefore = itemCount(bucketResult);
      beforeCount = count();
      outcome = InteractionOutcome.begin(target, placedPos, target.above());
      if (explicitSubHit) outcome.recordHitPoint(targetUv.u(), targetUv.v());
      PlayerEvidence.begin();
      PlayerEvidence.forget(target);
      PlayerEvidence.forget(target.above());
      PlayerEvidence.forget(placedPos);
      Actions.look(hit.getLocation());
      mc().player.setYHeadRot(mc().player.getYRot());
      if (mc().player.connection != null) {
        mc().player.connection.send(
            new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                mc().player.getYRot(), mc().player.getXRot(), mc().player.onGround()));
      }
      MenuContext.expect(target);
      sent = true;
      usedAt = System.nanoTime();
      usedPosition = hit.getLocation();
      if (before.getItem() instanceof BucketItem)
        mc().gameMode.useItem(mc().player, InteractionHand.MAIN_HAND);
      else mc().gameMode.useItemOn(mc().player, InteractionHand.MAIN_HAND, hit);
      mc().player.swing(InteractionHand.MAIN_HAND);
      phase = 3;
      return 0;
    }
    if (phase == 3) {
      delay++;
      if (sneak) sneakHold.maintain();
      outcome.tick();
      if (sneak && delay >= 8) sneakHold.release();
      boolean hitTargetNow =
          changed(target, blockBefore)
              || MachineObservation.changed(target, machineBefore, machineRevision);
      boolean adjNow =
          placedPos != null && !placedPos.equals(target) && changed(placedPos, placedBefore);
      targetEffect |= hitTargetNow;
      adjacentPlaced |= adjNow;
      effect |= hitTargetNow || changed(target.above(), aboveBefore) || adjNow;
      menu = mc().player.containerMenu != mc().player.inventoryMenu;
      if (menu) {
        if (!MenuContext.matches(target)) return fail("[MENU_CHANGED] 物品使用后出现来源不明的菜单");
        if (MenuContext.synchronizedNow())
          return success("menu_synchronized", Math.max(0, beforeCount - count()));
        if (delay > 100) return fail("[MENU_UNCONFIRMED] 已打开但同步未完成，不重放；" + context());
        return 0;
      }
      // Wait for packet processing before a full inventory verification. A no-effect use is not
      // retried.
      if (delay < 8 || !effect && delay < 30) return 0;
      phase = 4;
    }
    outcome.tick();
    int r = verify.tick();
    if (r < 0) return fail("[USE_UNCONFIRMED] " + verify.error + "；不重复使用");
    if (r == 0) return 0;
    boolean hitTargetFinal =
        changed(target, blockBefore)
            || MachineObservation.changed(target, machineBefore, machineRevision);
    boolean adjFinal =
        placedPos != null && !placedPos.equals(target) && changed(placedPos, placedBefore);
    targetEffect |= hitTargetFinal;
    adjacentPlaced |= adjFinal;
    effect |= hitTargetFinal || changed(target.above(), aboveBefore) || adjFinal;
    int consumed = Math.max(0, beforeCount - count());
    verify.close();
    boolean containerOrSubTarget = blockBefore.hasBlockEntity() || explicitSubHit;
    if (containerOrSubTarget
        && !sneak
        && before.getItem() instanceof BlockItem
        && !blockBefore.is(Blocks.FARMLAND)
        && !targetEffect
        && adjacentPlaced) {
      String adjId =
          net.minecraft.core.registries.BuiltInRegistries.BLOCK
              .getKey(mc().level.getBlockState(placedPos).getBlock())
              .toString();
      return fail(
          "[PLACED_ADJACENT_NOT_INSERTED] 目标方块("
              + target.toShortString()
              + ")未接收物品，方块被放置到相邻格 "
              + placedPos.toShortString()
              + "（变为 "
              + adjId
              + "）；不是入库成功，请核对朝向/面内坐标/抽屉状态，不自动重放；消耗="
              + consumed
              + "；"
              + outcome.detail());
    }
    if (bucketResult != null && consumed == 1 && itemCount(bucketResult) == bucketResultBefore + 1)
      return success("bucket_exchange_verified", consumed);
    if (blockBefore.is(Blocks.FARMLAND)
        && before.getItem() instanceof BlockItem placed
        && placed.getBlock() instanceof net.minecraft.world.level.block.CropBlock) {
      var actual = mc().level.getBlockState(target.above());
      var server = PlayerEvidence.block(target.above());
      if (aboveBefore.isAir()
          && actual.is(placed.getBlock())
          && actual.equals(server)
          && consumed == 1) return success("seed_consumed_and_seedling_confirmed", consumed);
      return fail("[PLANT_UNCONFIRMED] 种子消耗与对应幼苗未同时确认，不重复播种");
    }
    if (before.getItem() instanceof BoneMealItem
        && blockBefore.getBlock() instanceof net.minecraft.world.level.block.CropBlock) {
      // Beetroot bone meal can be consumed without an age increase. Consumption is verified
      // separately.
      if (consumed == 1 && mc().level.getBlockState(target).is(blockBefore.getBlock()))
        return success(
            effect ? "fertilizer_consumed_and_crop_changed" : "fertilizer_consumed_without_growth",
            consumed);
      return fail("[FERTILIZER_UNCONFIRMED] 未确认骨粉消耗或目标作物变化，不重复当前动作");
    }
    if (before.getItem() instanceof SpawnEggItem egg) {
      String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(egg).toString();
      if (id.startsWith("minecraft:")
          && !before.hasTag()
          && EntitySpawnEvidence.confirms(egg.getType(null), usedPosition, usedAt, consumed))
        return success("egg_consumed_and_matching_server_entity_spawned", consumed);
      return fail("[USE_UNCONFIRMED] 刷怪蛋消耗=" + consumed + "；没有取得唯一匹配的新实体服务器同步，先观察，不重放");
    }
    if (EntitySpawnEvidence.placedEntityType(before) != null) {
      if (EntitySpawnEvidence.placedEntity(
          before, usedPosition, usedAt, consumed, mc().player.isCreative()))
        return success("matching_server_entity_spawn_and_item_use", consumed);
      return fail("[USE_UNCONFIRMED] 放置实体未取得唯一匹配的新实体同步；先观察，不重放");
    }
    if (BlockUseProof.verified(effect, false, true))
      return success(BlockUseProof.outcome(true, consumed), consumed);
    if (outcome.confirmed())
      return fail(
          "[USE_PARTIAL] 仅观察到目标处关联现象，未取得物品对方块使用的直接核验；"
              + context()
              + "；消耗="
              + consumed
              + "；"
              + outcome.detail()
              + "；先观察，不自动重放");
    return fail(
        "[USE_UNCONFIRMED] "
            + BlockUseProof.outcome(false, consumed)
            + "；"
            + context()
            + "；消耗="
            + consumed
            + "；"
            + outcome.detail()
            + "；先观察，不自动重放");
  }

  /** Target, requested face, current hand and the delayed sample, for unconfirmed receipts. */
  private String context() {
    return "目标="
        + target.toShortString()
        + (top ? "/top" : "")
        + "；手持="
        + handText()
        + (outcome == null ? "" : "；" + InteractionReceipt.readout(outcome.evidence()));
  }

  private String handText() {
    var stack = mc().player.getMainHandItem();
    return stack.isEmpty()
        ? "空手"
        : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()
            + "×"
            + stack.getCount();
  }

  /** Navigation failures keep the target and the stance-candidate diagnosis. */
  private String navFailure() {
    String candidates = nav.candidateReport();
    return "无法到达交互站位；目标="
        + target.toShortString()
        + "；手持="
        + handText()
        + "；"
        + nav.error()
        + (candidates.isEmpty() || nav.error().contains("candidates=") ? "" : "；" + candidates);
  }

  private boolean changed(BlockPos p, BlockState old) {
    var server = PlayerEvidence.block(p);
    return server != null && !server.equals(old) && server.equals(mc().level.getBlockState(p));
  }

  private int success(String proof, int consumed) {
    detail =
        "成功：物品对方块使用已核实；目标 "
            + target.toShortString()
            + "；证据="
            + proof
            + "；物品减少="
            + consumed
            + (outcome == null ? "" : "；" + outcome.detail())
            + "。";
    WorldSense.record(target, mc().level.getBlockState(target));
    WorldSense.record(target.above(), mc().level.getBlockState(target.above()));
    stop();
    return 1;
  }

  private int fail(String why) {
    error = why;
    stop();
    return -1;
  }

  public void stop() {
    sneakHold.release();
    nav.stop();
    if (prepare != null) prepare.stop();
    initial.close();
    verify.close();
    if (mc().player != null) mc().player.getInventory().selected = oldSlot;
  }

  public boolean sent() {
    return sent;
  }
}
