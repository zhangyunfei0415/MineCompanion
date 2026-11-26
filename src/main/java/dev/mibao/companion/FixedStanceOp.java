package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;

/**
 * Shared fixed-stance single-block operations owned by a parent {@link Actions.Job}. Never owns a
 * {@link Navigator} or overwrites {@code Actions.job}.
 */
interface FixedStanceOp {
  enum Status {
    RUNNING,
    VERIFIED,
    NEED_STANCE,
    DENIED,
    UNCERTAIN
  }

  enum BreakAuthority {
    BULK,
    EXPLICIT_TARGET
  }

  Status tick();

  void cancel();

  boolean issued();

  default boolean isManagingScreen() {
    return false;
  }

  String reasonCode();

  String detail();

  static Minecraft mc() {
    return Minecraft.getInstance();
  }

  static void closeManagedInventoryIfOpen() {
    var mc = mc();
    if (mc.screen instanceof InventoryScreen && InventorySession.canClose()) {
      if (mc.player != null) mc.player.closeContainer();
      if (mc.screen instanceof InventoryScreen) mc.setScreen(null);
    }
  }

  static void abortPrepare(InventoryMove prepare) {
    if (prepare != null) {
      prepare.stop();
      closeManagedInventoryIfOpen();
    }
  }

  final class Break implements FixedStanceOp {
    private final BlockPos target;
    private final BreakAuthority authority;
    private final AimGate aim = new AimGate();
    private final boolean creative;
    private InventoryMove prepare;
    private BlockState beforeState;
    private BlockState expectedState;
    private boolean startedDestroy;
    private boolean toolReady;
    private int destroyTicks;
    private String reason = "";
    private String detailMsg = "";

    Break(BlockPos target, BreakAuthority authority) {
      this.target = target.immutable();
      this.authority = authority;
      this.creative =
          mc().gameMode != null && mc().gameMode.getPlayerMode() == GameType.CREATIVE;
    }

    BlockState beforeState() {
      return beforeState;
    }

    @Override
    public boolean issued() {
      return startedDestroy;
    }

    @Override
    public boolean isManagingScreen() {
      return prepare != null;
    }

    @Override
    public String reasonCode() {
      return reason;
    }

    @Override
    public String detail() {
      return detailMsg;
    }

    @Override
    public void cancel() {
      if (mc().gameMode != null) mc().gameMode.stopDestroyBlock();
      aim.reset();
      FixedStanceOp.abortPrepare(prepare);
      prepare = null;
    }

    @Override
    public Status tick() {
      var level = mc().level;
      var player = mc().player;
      if (level == null || player == null || mc().gameMode == null) {
        return deny("WRONG_CONTEXT", "世界未就绪");
      }
      if (!level.hasChunkAt(target)) {
        return deny("UNKNOWN_UNLOADED", "目标区块未加载");
      }
      BlockState live = level.getBlockState(target);
      if (startedDestroy) {
        destroyTicks++;
        BlockState server = PlayerEvidence.block(target);
        if (MiningProof.broken(
            true, server != null && server.isAir(), live.isAir())) {
          return Status.VERIFIED;
        }
        int limit = creative ? 80 : 300;
        if (destroyTicks > limit) {
          return uncertain("BREAK_TIMEOUT", "破坏超时未获服务器空气确认");
        }
        if (creative) return Status.RUNNING;
        if (live != expectedState && !live.isAir()) {
          return uncertain("TARGET_CHANGED", "破坏途中目标方块变化");
        }
        if (!live.isAir()) {
          var hit = Reach.mine(target);
          if (hit == null) {
            mc().gameMode.stopDestroyBlock();
            return uncertain("REACH_LOST", "挖掘途中目标超出可达视线");
          }
          Actions.look(hit.getLocation());
          mc().gameMode.continueDestroyBlock(target, hit.getDirection());
        }
        return Status.RUNNING;
      }

      if (live.isAir()) {
        beforeState = live;
        return Status.VERIFIED;
      }
      if (live.getDestroySpeed(level, target) < 0) {
        return deny("BLOCK_PROTECTED", "目标不可破坏");
      }
      if (!live.getFluidState().isEmpty()) {
        return deny("FLUID_TARGET", "目标格含流体，禁止盲拆");
      }
      String blockId = BuiltInRegistries.BLOCK.getKey(live.getBlock()).toString();
      boolean targeted = authority == BreakAuthority.EXPLICIT_TARGET;
      String policyDeny =
          BreakPolicy.denial(blockId, live.hasBlockEntity(), targeted, false, false, false);
      if (!policyDeny.isBlank()) {
        return deny("BLOCK_PROTECTED", policyDeny);
      }

      BlockPos feet = player.blockPosition();
      if (target.equals(feet.below())
          || ((target.equals(feet) || target.equals(feet.above()))
              && !live.getCollisionShape(level, target).isEmpty())) {
        return needStance("PLAYER_SUPPORT", "目标在玩家脚下或占用身体碰撞格，需换站位");
      }

      // Check adjacent hazards + external falling columns
      boolean fluidOrFireAbove = false;
      boolean hasHorizontalFluid = false;
      boolean hasHorizontalFire = false;
      for (Direction d : Direction.values()) {
        BlockPos n = target.relative(d);
        if (!level.hasChunkAt(n)) return deny("UNLOADED_NEIGHBOR", "周围区块未知");
        var ns = level.getBlockState(n);
        var fs = ns.getFluidState();
        boolean isFire = ns.is(Blocks.FIRE) || ns.is(Blocks.SOUL_FIRE);
        if (!fs.isEmpty() || isFire) {
          if (d == Direction.UP || (d == Direction.DOWN && (isFire || !fs.is(FluidTags.WATER)))) {
            fluidOrFireAbove = true;
          } else if (isFire) {
            hasHorizontalFire = true;
          } else if (fs.is(FluidTags.WATER) || fs.is(FluidTags.LAVA)) {
            hasHorizontalFluid = true;
          } else {
            fluidOrFireAbove = true;
          }
        }
      }
      boolean drySafe =
          !player.isInLava()
              && !player.isOnFire()
              && level.hasChunkAt(feet)
              && level.getBlockState(feet).getFluidState().isEmpty();
      var fv =
          GeneratorPolicy.verdict(
              blockId,
              targeted,
              false,
              fluidOrFireAbove,
              hasHorizontalFluid,
              hasHorizontalFire,
              drySafe,
              Store.config.customGeneratorBlocks);
      if (fv != GeneratorPolicy.FluidVerdict.OK) {
        return deny("FLUID_EXPOSURE", "周围存在流体或火焰风险：" + fv);
      }

      if (level.getBlockState(target.above()).getBlock() instanceof FallingBlock) {
        return deny("FALLING_COLUMN_ABOVE", "目标正上方仍存在未拆落沙/砂砾，保留防塌");
      }
      AABB fallBox =
          new AABB(target.getX(), target.getY(), target.getZ(), target.getX() + 1, target.getY() + 5, target.getZ() + 1)
              .minmax(player.getBoundingBox().expandTowards(0, 4, 0));
      if (!level.getEntitiesOfClass(FallingBlockEntity.class, fallBox).isEmpty()) {
        return deny("FALLING_ENTITY_ACTIVE", "附近存在正在下落的方块实体，暂停拆列");
      }

      var hit = Reach.mine(target);
      if (hit == null) {
        return needStance("OUT_OF_REACH", "当前站位不可及目标：" + target.toShortString());
      }

      if (!creative && !toolReady) {
        if (prepare != null) {
          int r = prepare.tick();
          if (r < 0) {
            String err = prepare.error;
            FixedStanceOp.abortPrepare(prepare);
            prepare = null;
            return deny("TOOL_PREP_FAILED", err);
          }
          if (r == 0) return Status.RUNNING;
          player.getInventory().selected = prepare.readySlot;
          FixedStanceOp.closeManagedInventoryIfOpen();
          prepare = null;
          toolReady = true;
          return Status.RUNNING;
        }
        int sel = selectBestTool(live);
        if (sel == -2) {
          return deny("NO_SUITABLE_TOOL", "缺少可正确采集且耐久足够的工具");
        }
        if (sel >= 9) {
          String fp = WorldSense.fingerprint(player.getInventory().getItem(sel));
          prepare = InventoryMove.ensure(s -> !s.isEmpty() && WorldSense.fingerprint(s).equals(fp));
          int r = prepare.tick();
          if (r < 0) {
            String err = prepare.error;
            FixedStanceOp.abortPrepare(prepare);
            prepare = null;
            return deny("TOOL_PREP_FAILED", err);
          }
          if (r == 0) return Status.RUNNING;
          player.getInventory().selected = prepare.readySlot;
          FixedStanceOp.closeManagedInventoryIfOpen();
          prepare = null;
          toolReady = true;
          return Status.RUNNING;
        } else if (sel >= 0) {
          player.getInventory().selected = sel;
          toolReady = true;
        } else {
          toolReady = true;
        }
      }

      if (!aim.ready(hit.getLocation())) {
        if (aim.timedOut()) return needStance("AIM_TIMEOUT", "瞄准未收敛，尝试换位");
        return Status.RUNNING;
      }

      // Final live raycast before sending destroy
      var liveHit = Reach.mine(target);
      if (liveHit == null) return needStance("OUT_OF_REACH", "发送前射线落出目标面");
      beforeState = live;
      expectedState = live;
      PlayerEvidence.forget(target);
      Actions.look(liveHit.getLocation());
      if (player.connection != null) {
        player.connection.send(
            new ServerboundMovePlayerPacket.Rot(
                player.getYRot(), player.getXRot(), player.onGround()));
      }
      mc().gameMode.startDestroyBlock(target, liveHit.getDirection());
      player.swing(InteractionHand.MAIN_HAND);
      startedDestroy = true;
      destroyTicks = 0;
      return Status.RUNNING;
    }

    private int selectBestTool(BlockState state) {
      var inv = mc().player.getInventory();
      int best = -1;
      float bestSpeed = 0f;
      boolean needsTool = state.requiresCorrectToolForDrops();
      for (int i = 0; i < 36; i++) {
        ItemStack s = inv.getItem(i);
        if (s.isEmpty()) continue;
        if (s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= 2) continue;
        if (needsTool && !s.isCorrectToolForDrops(state)) continue;
        float sp = s.getDestroySpeed(state);
        if (sp > bestSpeed) {
          bestSpeed = sp;
          best = i;
        }
      }
      if (best < 0 && needsTool) return -2;
      return best;
    }

    private Status deny(String code, String msg) {
      reason = code;
      detailMsg = msg;
      return Status.DENIED;
    }

    private Status needStance(String code, String msg) {
      reason = code;
      detailMsg = msg;
      return Status.NEED_STANCE;
    }

    private Status uncertain(String code, String msg) {
      reason = code;
      detailMsg = msg;
      return Status.UNCERTAIN;
    }
  }

  final class Place implements FixedStanceOp {
    private final BlockPos target;
    private final String itemId;
    private final JsonObject desiredProps;
    private final boolean allowTargetSourceWater;
    private final AimGate aim = new AimGate();
    private final boolean creative;
    private Block block;
    private PlacementIntent intent;
    private InventoryMove prepare;
    private String fingerprint;
    private int beforeCount, waitTicks, entityWaitTicks;
    private BlockState beforeState, afterState;
    private boolean sent;
    private String reason = "", detailMsg = "";

    Place(BlockPos target, String itemId, JsonObject desiredProps, boolean allowTargetSourceWater) {
      this.target = target.immutable();
      this.itemId = itemId;
      this.desiredProps = desiredProps == null ? new JsonObject() : desiredProps.deepCopy();
      this.allowTargetSourceWater = allowTargetSourceWater;
      this.creative =
          mc().gameMode != null && mc().gameMode.getPlayerMode() == GameType.CREATIVE;
    }

    BlockState beforeState() {
      return beforeState;
    }

    BlockState afterState() {
      return afterState;
    }

    @Override
    public boolean issued() {
      return sent;
    }

    @Override
    public boolean isManagingScreen() {
      return prepare != null;
    }

    @Override
    public String reasonCode() {
      return reason;
    }

    @Override
    public String detail() {
      return detailMsg;
    }

    @Override
    public void cancel() {
      aim.reset();
      FixedStanceOp.abortPrepare(prepare);
      prepare = null;
    }

    @Override
    public Status tick() {
      var level = mc().level;
      var player = mc().player;
      if (level == null || player == null || mc().gameMode == null) {
        reason = "WRONG_CONTEXT";
        detailMsg = "世界未就绪";
        return Status.DENIED;
      }
      if (!level.hasChunkAt(target)) {
        reason = "UNKNOWN_UNLOADED";
        detailMsg = "目标区块未加载";
        return Status.DENIED;
      }
      BlockState live = level.getBlockState(target);
      if (sent) {
        waitTicks++;
        BlockState server = PlayerEvidence.block(target);
        boolean matched =
            server != null
                && server.is(block)
                && (intent == null || intent.matches(server))
                && (!allowTargetSourceWater
                    || server.getFluidState().isSource()
                        && server.getFluidState().is(FluidTags.WATER));
        if (matched && live.equals(server)) {
          if (creative) {
            afterState = live;
            return Status.VERIFIED;
          }
          int curCount = PlayerEvidence.total(PlayerEvidence.actual(fingerprint));
          if (curCount < beforeCount || PlayerEvidence.total(PlayerEvidence.confirmed(fingerprint)) < beforeCount) {
            afterState = live;
            return Status.VERIFIED;
          }
        }
        if (waitTicks >= 10
            && server != null
            && server.canBeReplaced()
            && live.canBeReplaced()
            && (creative || PlayerEvidence.total(PlayerEvidence.actual(fingerprint)) == beforeCount)) {
          return needStance("PLACE_REJECTED_SERVER", "服务器退回空位且未消耗物品，尝试换位重试");
        }
        if (waitTicks > 100) {
          reason = "PLACE_UNCONFIRMED";
          detailMsg = "放置超时未获服务器同步：" + target.toShortString();
          return Status.UNCERTAIN;
        }
        return Status.RUNNING;
      }

      if (block == null) {
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
          reason = "UNKNOWN_ITEM";
          detailMsg = "未知物品ID：" + itemId;
          return Status.DENIED;
        }
        Item it = BuiltInRegistries.ITEM.get(key);
        if (!(it instanceof BlockItem bi)) {
          reason = "INVALID_BLOCK_ITEM";
          detailMsg = "不是可放置方块物品：" + itemId;
          return Status.DENIED;
        }
        block = bi.getBlock();
        intent = desiredProps.size() > 0 ? new PlacementIntent(desiredProps, block) : null;
      }

      if (live.is(block)
          && (intent == null || intent.matches(live))
          && (!allowTargetSourceWater
              || (live.getFluidState().isSource() && live.getFluidState().is(FluidTags.WATER)))) {
        afterState = live;
        return Status.VERIFIED;
      }

      if (!live.canBeReplaced()) {
        reason = "TARGET_OCCUPIED";
        detailMsg = "目标格不可替换：" + BuiltInRegistries.BLOCK.getKey(live.getBlock());
        return Status.DENIED;
      }
      if (!live.getFluidState().isEmpty()) {
        boolean waterSourceOk =
            allowTargetSourceWater
                && live.getFluidState().isSource()
                && live.getFluidState().is(FluidTags.WATER);
        if (!waterSourceOk) {
          reason = "FLUID_TARGET";
          detailMsg = "目标格含不可覆盖流体";
          return Status.DENIED;
        }
      }
      if (player.getBoundingBox().inflate(0.08, 0.0, 0.08).intersects(new AABB(target))) {
        return needStance("BODY_OCCUPIES_TARGET", "玩家身体贴近或占用放置格，需换站位");
      }
      boolean entityBlocked =
          !level
              .getEntities(
                  (net.minecraft.world.entity.Entity) null,
                  new AABB(target),
                  e -> e != player && e.isAlive() && !(e instanceof ItemEntity))
              .isEmpty();
      if (entityBlocked) {
        if (++entityWaitTicks <= 30) return Status.RUNNING;
        return needStance("ENTITY_OBSTRUCTED", "目标格被其他生物占用，稍后换位重试");
      }
      entityWaitTicks = 0;

      // Drive active inventory swap to completion before inspecting hotbar (swap places item on tick 2 before sync finishes)
      if (prepare != null) {
        int r = prepare.tick();
        if (r < 0) {
          String err = prepare.error;
          FixedStanceOp.abortPrepare(prepare);
          prepare = null;
          reason = "MISSING_MATERIAL";
          detailMsg = err;
          return Status.DENIED;
        }
        if (r == 0) return Status.RUNNING;
        player.getInventory().selected = prepare.readySlot;
        FixedStanceOp.closeManagedInventoryIfOpen();
        prepare = null;
        return Status.RUNNING;
      }

      // Prepare item in hotbar
      int hotbarSlot = -1;
      for (int i = 0; i < 9; i++) {
        ItemStack s = player.getInventory().getItem(i);
        if (!s.isEmpty() && s.getItem() instanceof BlockItem && Actions.match(s, itemId)) {
          hotbarSlot = i;
          break;
        }
      }
      if (hotbarSlot >= 0) {
        player.getInventory().selected = hotbarSlot;
      } else if (creative) {
        for (int i = 9; i < 36; i++) {
          ItemStack s = player.getInventory().getItem(i);
          if (!s.isEmpty() && s.getItem() instanceof BlockItem && Actions.match(s, itemId)) {
            mc().gameMode.handlePickItem(i);
            return Status.RUNNING;
          }
        }
        reason = "MISSING_MATERIAL";
        detailMsg = "创造模式背包中缺少可放置方块：" + itemId;
        return Status.DENIED;
      } else {
        prepare =
            InventoryMove.ensure(
                s -> !s.isEmpty() && s.getItem() instanceof BlockItem && Actions.match(s, itemId));
        int r = prepare.tick();
        if (r < 0) {
          String err = prepare.error;
          FixedStanceOp.abortPrepare(prepare);
          prepare = null;
          reason = "MISSING_MATERIAL";
          detailMsg = err;
          return Status.DENIED;
        }
        if (r == 0) return Status.RUNNING;
        player.getInventory().selected = prepare.readySlot;
        FixedStanceOp.closeManagedInventoryIfOpen();
        prepare = null;
        return Status.RUNNING;
      }

      ItemStack stack = player.getMainHandItem();
      Vec3 eye = player.getEyePosition();
      BlockHitResult hit =
          Reach.placementFrom(
              target,
              eye,
              null,
              h ->
                  intent == null
                      || (intent.hitAllowed(h) && intent.hitDirectionAllowed(eye, h, block)));
      if (hit == null) {
        return needStance(
            allowTargetSourceWater ? "SLAB_STATE_UNREACHABLE" : "NO_VISIBLE_FACE",
            "当前站位找不到可满足期望状态的支撑面");
      }
      if (!aim.ready(hit.getLocation())) {
        if (aim.timedOut()) return needStance("AIM_TIMEOUT", "放置瞄准未收敛");
        return Status.RUNNING;
      }

      BlockHitResult liveHit =
          Reach.placementFrom(
              target,
              player.getEyePosition(),
              null,
              h ->
                  intent == null
                      || (intent.hitAllowed(h)
                          && intent.hitDirectionAllowed(player.getEyePosition(), h, block)));
      if (liveHit == null) {
        return needStance("NO_VISIBLE_FACE", "发送前支撑面不可见");
      }
      var rawCtx = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, liveHit);
      var ctx =
          (stack.getItem() instanceof BlockItem bi) ? bi.updatePlacementContext(rawCtx) : rawCtx;
      if (ctx == null || !ctx.canPlace() || !ctx.getClickedPos().equals(target)) {
        return needStance("PLACE_CONDITION", "当前支撑面无法在目标格生成方块");
      }
      var predicted = block.getStateForPlacement(ctx);
      if (predicted == null || (intent != null && !intent.matches(predicted))) {
        return needStance(
            allowTargetSourceWater ? "SLAB_STATE_UNREACHABLE" : "WRONG_STATE",
            "当前命中面预测状态与期望不符");
      }

      beforeState = live;
      fingerprint = WorldSense.fingerprint(stack);
      PlayerEvidence.begin();
      beforeCount = PlayerEvidence.total(PlayerEvidence.actual(fingerprint));
      PlayerEvidence.forget(target);
      Actions.look(liveHit.getLocation());
      if (player.connection != null) {
        player.connection.send(
            new ServerboundMovePlayerPacket.Rot(
                player.getYRot(), player.getXRot(), player.onGround()));
      }
      InteractionResult ir = mc().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, liveHit);
      if (!ir.consumesAction()) {
        return needStance("PLACE_REJECTED_CLIENT", "客户端未接受放置(" + ir + ")，尝试换位");
      }
      player.swing(InteractionHand.MAIN_HAND);
      sent = true;
      waitTicks = 0;
      return Status.RUNNING;
    }

    private Status needStance(String code, String msg) {
      reason = code;
      detailMsg = msg;
      return Status.NEED_STANCE;
    }
  }

  final class Use implements FixedStanceOp {
    enum Kind {
      PLACE_WATER,
      REFILL_BUCKET,
      HOE,
      SEED
    }

    private final Kind kind;
    private final BlockPos target; // For SEED this is farmland soil pos (crop lands at target.above())
    private final String itemFilter;
    private final Block expectedCropBlock;
    private final AimGate aim = new AimGate();
    private final boolean creative;
    private InventoryMove prepare;
    private boolean sent;
    private int waitTicks, beforeItemCount, beforeBucketCount;
    private ItemStack beforeHeld;
    private String reason = "", detailMsg = "";

    Use(Kind kind, BlockPos target, String itemFilter, Block expectedCropBlock) {
      this.kind = kind;
      this.target = target.immutable();
      this.itemFilter = itemFilter == null ? "" : itemFilter;
      this.expectedCropBlock = expectedCropBlock;
      this.creative =
          mc().gameMode != null && mc().gameMode.getPlayerMode() == GameType.CREATIVE;
    }

    @Override
    public boolean issued() {
      return sent;
    }

    @Override
    public boolean isManagingScreen() {
      return prepare != null;
    }

    @Override
    public String reasonCode() {
      return reason;
    }

    @Override
    public String detail() {
      return detailMsg;
    }

    @Override
    public void cancel() {
      aim.reset();
      FixedStanceOp.abortPrepare(prepare);
      prepare = null;
    }

    private Status needStance(String code, String msg) {
      reason = code;
      detailMsg = msg;
      return Status.NEED_STANCE;
    }

    @Override
    public Status tick() {
      var level = mc().level;
      var player = mc().player;
      if (level == null || player == null || mc().gameMode == null) {
        reason = "WRONG_CONTEXT";
        detailMsg = "世界未就绪";
        return Status.DENIED;
      }
      if (!level.hasChunkAt(target) || !level.hasChunkAt(target.above())) {
        reason = "UNKNOWN_UNLOADED";
        detailMsg = "目标区块未加载";
        return Status.DENIED;
      }
      if (sent) {
        waitTicks++;
        boolean verified =
            switch (kind) {
              case PLACE_WATER -> {
                var fs = level.getFluidState(target);
                BlockState server = PlayerEvidence.block(target);
                boolean waterOk =
                    fs.is(FluidTags.WATER)
                        && fs.isSource()
                        && server != null
                        && server.getFluidState().is(FluidTags.WATER)
                        && server.getFluidState().isSource();
                boolean bagOk = creative || countItem(Items.BUCKET) > beforeBucketCount;
                yield waterOk && bagOk;
              }
              case REFILL_BUCKET ->
                  creative
                      ? true
                      : countItem(Items.WATER_BUCKET) > beforeItemCount;
              case HOE -> {
                BlockState server = PlayerEvidence.block(target);
                yield level.getBlockState(target).is(Blocks.FARMLAND)
                    && server != null
                    && server.is(Blocks.FARMLAND);
              }
              case SEED -> {
                BlockState server = PlayerEvidence.block(target.above());
                boolean cropOk =
                    expectedCropBlock != null
                        && level.getBlockState(target.above()).is(expectedCropBlock)
                        && server != null
                        && server.is(expectedCropBlock);
                boolean bagOk = creative || countMatching(itemFilter) < beforeItemCount;
                yield cropOk && bagOk;
              }
            };
        if (verified) return Status.VERIFIED;
        if (waitTicks > 100) {
          reason = "USE_UNCONFIRMED";
          detailMsg = "使用物品超时未获期望世界反馈：" + target.toShortString();
          return Status.UNCERTAIN;
        }
        return Status.RUNNING;
      }

      // Idempotent pre-check: skip tool use if target already satisfies desired outcome
      if (kind == Kind.HOE && level.getBlockState(target).is(Blocks.FARMLAND)) {
        return Status.VERIFIED;
      }
      if (kind == Kind.SEED
          && expectedCropBlock != null
          && level.getBlockState(target.above()).is(expectedCropBlock)) {
        return Status.VERIFIED;
      }
      if (kind == Kind.PLACE_WATER
          && level.getFluidState(target).is(FluidTags.WATER)
          && level.getFluidState(target).isSource()) {
        return Status.VERIFIED;
      }

      // Drive active inventory swap to completion before inspecting hotbar (swap places item on tick 2 before sync finishes)
      if (prepare != null) {
        int r = prepare.tick();
        if (r < 0) {
          String err = prepare.error;
          FixedStanceOp.abortPrepare(prepare);
          prepare = null;
          reason = "MISSING_ITEM";
          detailMsg = err;
          return Status.DENIED;
        }
        if (r == 0) return Status.RUNNING;
        player.getInventory().selected = prepare.readySlot;
        FixedStanceOp.closeManagedInventoryIfOpen();
        prepare = null;
        return Status.RUNNING;
      }

      // Prepare required tool/item in hotbar
      int hotbarSlot = findHotbarItem();
      if (hotbarSlot >= 0) {
        player.getInventory().selected = hotbarSlot;
      } else if (creative) {
        for (int i = 9; i < 36; i++) {
          if (matchesRequiredItem(player.getInventory().getItem(i))) {
            mc().gameMode.handlePickItem(i);
            return Status.RUNNING;
          }
        }
        reason = "MISSING_ITEM";
        detailMsg = "创造模式背包中缺少所需物品：" + kind;
        return Status.DENIED;
      } else {
        prepare = InventoryMove.ensure(this::matchesRequiredItem);
        int r = prepare.tick();
        if (r < 0) {
          String err = prepare.error;
          FixedStanceOp.abortPrepare(prepare);
          prepare = null;
          reason = "MISSING_ITEM";
          detailMsg = err;
          return Status.DENIED;
        }
        if (r == 0) return Status.RUNNING;
        player.getInventory().selected = prepare.readySlot;
        FixedStanceOp.closeManagedInventoryIfOpen();
        prepare = null;
        return Status.RUNNING;
      }

      beforeHeld = player.getMainHandItem().copy();
      if (!matchesRequiredItem(beforeHeld)) {
        reason = "MISSING_ITEM";
        detailMsg = "主手未能准备所需物品：" + kind;
        return Status.DENIED;
      }

      Vec3 eye = player.getEyePosition();
      Vec3 aimPoint;
      BlockHitResult useHit = null;
      if (kind == Kind.PLACE_WATER) {
        // Aim at top face of target.below() so BucketItem places water at target
        useHit = Reach.topFrom(target.below(), eye);
        if (useHit == null) {
          reason = "OUT_OF_REACH";
          detailMsg = "当前站位够不到水窝底面";
          return Status.NEED_STANCE;
        }
        aimPoint = useHit.getLocation();
      } else if (kind == Kind.REFILL_BUCKET) {
        if (!creative && beforeHeld.getCount() > 1 && player.getInventory().getFreeSlot() < 0) {
          reason = "INVENTORY_FULL";
          detailMsg =
              "背包已满且空桶堆叠(" + beforeHeld.getCount() + ")，无法容纳装满的水桶";
          return Status.DENIED;
        }
        if (!level.getFluidState(target).isSource() || !level.getFluidState(target).is(FluidTags.WATER)) {
          reason = "WATER_POOL_EMPTY";
          detailMsg = "取水格当前不是源水";
          return Status.DENIED;
        }
        aimPoint = new Vec3(target.getX() + 0.5, target.getY() + 0.8, target.getZ() + 0.5);
        if (eye.distanceTo(aimPoint) > mc().gameMode.getPickRange() - 0.1) {
          reason = "OUT_OF_REACH";
          detailMsg = "距离取水池过远";
          return Status.NEED_STANCE;
        }
        var clip =
            level.clip(
                new net.minecraft.world.level.ClipContext(
                    eye,
                    aimPoint,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY,
                    player));
        if (clip.getType() != HitResult.Type.BLOCK || !clip.getBlockPos().equals(target)) {
          reason = "OUT_OF_REACH";
          detailMsg = "取水视线被池边遮挡，需贴近取水池";
          return Status.NEED_STANCE;
        }
      } else {
        useHit = Reach.topFrom(target, eye);
        if (useHit == null) {
          reason = "OUT_OF_REACH";
          detailMsg = "当前站位够不到耕格顶面";
          return Status.NEED_STANCE;
        }
        aimPoint = useHit.getLocation();
      }

      if (!aim.ready(aimPoint)) {
        if (aim.timedOut()) {
          reason = "AIM_TIMEOUT";
          detailMsg = "农田/水桶瞄准未收敛";
          return Status.NEED_STANCE;
        }
        return Status.RUNNING;
      }

      beforeItemCount =
          kind == Kind.REFILL_BUCKET
              ? countItem(Items.WATER_BUCKET)
              : countMatching(itemFilter);
      beforeBucketCount = countItem(Items.BUCKET);
      PlayerEvidence.forget(target);
      PlayerEvidence.forget(target.above());
      Actions.look(aimPoint);
      if (player.connection != null) {
        player.connection.send(
            new ServerboundMovePlayerPacket.Rot(
                player.getYRot(), player.getXRot(), player.onGround()));
      }
      InteractionResult ir =
          beforeHeld.getItem() instanceof BucketItem
              ? mc().gameMode.useItem(player, InteractionHand.MAIN_HAND)
              : mc().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, useHit);
      if (!ir.consumesAction()) {
        return needStance("USE_REJECTED_CLIENT", "使用物品未生效(" + ir + ")，尝试换位");
      }
      player.swing(InteractionHand.MAIN_HAND);
      sent = true;
      waitTicks = 0;
      return Status.RUNNING;
    }

    private int findHotbarItem() {
      var inv = mc().player.getInventory();
      for (int i = 0; i < 9; i++) {
        if (matchesRequiredItem(inv.getItem(i))) return i;
      }
      return -1;
    }

    private boolean matchesRequiredItem(ItemStack s) {
      if (s.isEmpty()) return false;
      return switch (kind) {
        case PLACE_WATER -> s.is(Items.WATER_BUCKET);
        case REFILL_BUCKET -> s.is(Items.BUCKET);
        case HOE ->
            s.getItem() instanceof HoeItem
                && (creative
                    || !s.isDamageableItem()
                    || s.getMaxDamage() - s.getDamageValue() > 2);
        case SEED -> Actions.match(s, itemFilter);
      };
    }

    private int countItem(Item item) {
      int n = 0;
      var inv = mc().player.getInventory();
      for (int i = 0; i < 41; i++) {
        ItemStack s = inv.getItem(i);
        if (s.is(item)) n += s.getCount();
      }
      return n;
    }

    private int countMatching(String filter) {
      int n = 0;
      var inv = mc().player.getInventory();
      for (int i = 0; i < 41; i++) {
        ItemStack s = inv.getItem(i);
        if (!s.isEmpty() && Actions.match(s, filter)) n += s.getCount();
      }
      return n;
    }
  }
}
