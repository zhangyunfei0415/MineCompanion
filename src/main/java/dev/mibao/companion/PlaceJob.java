package dev.mibao.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/** Place one real held block; never switches to empty hand or writes world state directly. */
public final class PlaceJob extends Actions.Job {
  public static BlockPos lastPlaced;
  private Direction face;
  private com.google.gson.JsonObject desired = new com.google.gson.JsonObject();
  private PlacementIntent intent;

  public PlaceJob state(com.google.gson.JsonObject value) {
    desired = value.deepCopy();
    return this;
  }

  public PlaceJob face(String value) {
    if (value == null || value.equals("auto")) return this;
    face = Direction.byName(value.equals("top") ? "up" : value);
    if (face == null)
      throw new IllegalArgumentException(
          "[ARGUMENT_INVALID] face须auto/up/down/north/south/east/west");
    return this;
  }

  private final String filter;
  private BlockPos pos;
  private final Navigator nav = new Navigator();
  private InventoryMove prepare;
  private final InventorySession verify = new InventorySession();
  private int phase, before, wait;
  private String fingerprint;
  private net.minecraft.world.level.block.Block block;
  private net.minecraft.world.level.block.state.BlockState beforePlaceState;
  private boolean sneaking;
  private int sneakWarmup;
  private final SneakHold sneakHold = new SneakHold();
  private final boolean creative;
  private BlockPos scaffoldingStackAnchor;
  private Direction scaffoldingStackFace;
  private int sneakReleaseTicks;
  private int phase15Reapproaches;
  private Vec3 aimAnchor;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public PlaceJob(String item, BlockPos position, Consumer<String> done) {
    this(item, position, 3, done);
  }

  public PlaceJob(String item, BlockPos position, int stanceRadius, Consumer<String> done) {
    super(done);
    filter = item;
    var itemKey = net.minecraft.resources.ResourceLocation.tryParse(item.split("#", 2)[0]);
    if (itemKey != null
        && BuiltInRegistries.ITEM.containsKey(itemKey)
        && !(BuiltInRegistries.ITEM.get(itemKey) instanceof BlockItem))
      throw new IllegalArgumentException(
          "[ITEM_REQUIRES_USE] 不是普通BlockItem（可能为AE部件）；使用interact_block指定真实支撑坐标和face安装，不是缺少材料");
    pos = position;
    lastPlaced = null;
    creative = mc().gameMode.getPlayerMode() == GameType.CREATIVE;
    if (!creative && mc().gameMode.getPlayerMode() != GameType.SURVIVAL)
      throw new IllegalArgumentException("[MODE_REQUIRED] 放置需要生存或创造模式");
    if (!creative)
      prepare =
          InventoryMove.ensure(
              s -> !s.isEmpty() && s.getItem() instanceof BlockItem && Actions.match(s, item));
    nav.exactStance(true).maxRadius(stanceRadius);
  }

  private String siteProblem(BlockPos p) {
    if (!mc().level.hasChunkAt(p)) return "目标区块未加载";
    if (!mc().level.getBlockState(p).canBeReplaced())
      return "[TARGET_OCCUPIED] 目标格不可替换："
          + BuiltInRegistries.BLOCK.getKey(mc().level.getBlockState(p).getBlock());
    if (!mc().level.getFluidState(p).isEmpty()) return "目标格存在流体";
    if (!mc().level
        .getEntities(
            (net.minecraft.world.entity.Entity) null,
            new AABB(p),
            e ->
                e != mc().player
                    && e.isAlive()
                    && !(e instanceof net.minecraft.world.entity.item.ItemEntity))
        .isEmpty()) return "目标格被玩家/实体占用";
    for (Direction d : face == null ? Direction.values() : new Direction[] {face.getOpposite()}) {
      var support = p.relative(d);
      if (mc().level.hasChunkAt(support)
          && !mc().level.getBlockState(support).getShape(mc().level, support).isEmpty()) return "";
    }
    return "[NO_SUPPORT_FACE] 指定方向没有可点击支撑面";
  }

  private boolean safe(BlockPos p) {
    return siteProblem(p).isBlank();
  }

  private final AimGate aim = new AimGate();

  @Override
  void tick() {
    if (++ticks > 2400) {
      finish("部分完成：[PLACE_TIMEOUT] 放置超时，请核对现场，不自动重复。");
      return;
    }
    Actions.status =
        "放置方块 · "
            + (phase == 0
                ? "准备物品"
                : phase == 1 ? "寻找可见支撑面" : phase == 15 ? "瞄准并校验朝向" : "核对方块与消耗");
    if (phase == 0) {
      if (creative) {
        int slot = -1;
        for (int i = 0; i < 9; i++) {
          var s = mc().player.getInventory().getItem(i);
          if (s.getItem() instanceof BlockItem && Actions.match(s, filter)) {
            slot = i;
            break;
          }
        }
        if (slot < 0) {
          if (prepare == null)
            prepare =
                InventoryMove.ensure(
                    s -> s.getItem() instanceof BlockItem && Actions.match(s, filter));
          int ready = prepare.tick();
          if (ready < 0) {
            finish("失败：" + prepare.error);
            return;
          }
          if (ready == 0) return;
          slot = prepare.readySlot;
        }
        mc().player.getInventory().selected = slot;
      } else {
        int r = prepare.tick();
        if (r < 0) {
          finish("失败：" + prepare.error);
          return;
        }
        if (r == 0) return;
        mc().player.getInventory().selected = prepare.readySlot;
      }
      var stack = mc().player.getMainHandItem();
      block = ((BlockItem) stack.getItem()).getBlock();
      intent = new PlacementIntent(desired, block);
      fingerprint = WorldSense.fingerprint(stack);
      PlayerEvidence.begin();
      before = PlayerEvidence.total(PlayerEvidence.actual(fingerprint));
      var center = mc().player.blockPosition();
      List<PlaceCandidates.Site> sites = new ArrayList<>();
      double nearest = Double.MAX_VALUE;
      for (int dx = -3; dx <= 3; dx++)
        for (int dz = -3; dz <= 3; dz++)
          for (int dy = -1; dy <= 1; dy++) {
            var p = center.offset(dx, dy, dz);
            String problem = siteProblem(p);
            sites.add(
                new PlaceCandidates.Site(
                    p.getX(), p.getY(), p.getZ(), Math.sqrt(p.distSqr(center)), problem));
            if (pos == null
                && problem.isBlank()
                && Reach.top(p.below()) != null
                && p.distSqr(center) < nearest) {
              nearest = p.distSqr(center);
              pos = p;
            }
          }
      if (pos == null || !safe(pos)) {
        var report = PlaceCandidates.build(sites);
        String head = pos == null ? "附近未找到目标" : pos.toShortString() + " · " + siteProblem(pos);
        finish(
            "失败：[NO_PLACE_SITE] 放入目标格不可用："
                + head
                + "；"
                + PlaceCandidates.text(report)
                + "；候选JSON="
                + PlaceCandidates.json(report)
                + "。");
        return;
      }
      if (block instanceof net.minecraft.world.level.block.ScaffoldingBlock
          && face == null
          && mc().level.hasChunkAt(pos.below())
          && mc().level.getBlockState(pos.below()).is(block)) {
        for (BlockPos anchor : findScaffoldingColumnAnchors(pos, block)) {
          for (Direction side : orderedHorizontalSides(anchor)) {
            if (nav.startForFace(anchor, side)) {
              scaffoldingStackAnchor = anchor;
              scaffoldingStackFace = side;
              phase = 1;
              return;
            }
          }
        }
      }
      Direction look = intent.lookDirection(block);
      nav.placementIntent(
          eye ->
              look == null
                  || PlacementIntent.stanceInDirectionSector(
                      eye.x, eye.z, pos.getX() + .5, pos.getZ() + .5, look, 0.2),
          (eye, hit) ->
              intent.hitAllowed(hit) && intent.hitDirectionAllowed(eye, hit, block));
      if (!nav.startForPlacement(pos, face)) {
        finish(unreachable());
        return;
      }
      phase = 1;
      return;
    }
    if (phase == 1) {
      int r = nav.tick();
      if (r < 0) {
        finish(unreachable());
        return;
      }
      if (r == 0) return;
      nav.stop();
      aimAnchor = mc().player.position();
      aim.reset();
      sneakWarmup = 0;
      phase = 15;
      return;
    }
    if (phase == 15) {
      if (aimAnchor != null && mc().player.position().distanceToSqr(aimAnchor) > 0.09) {
        releaseSneak();
        aim.reset();
        if (!nav.startForPlacement(pos, face)) {
          finish(unreachable());
          return;
        }
        phase = 1;
        return;
      }
      if (!safe(pos)) {
        finish("失败：[PLACE_CHANGED] 放置位置已变化。");
        return;
      }
      var stack = mc().player.getMainHandItem();
      if (!Actions.match(stack, fingerprint)) {
        finish("失败：[PLACE_CHANGED] 物品或支撑面不可见。");
        return;
      }
      BlockHitResult hit = resolvePlacementHit();
      if (hit == null) {
        if (sneaking && scaffoldingStackAnchor == null) {
          releaseSneak();
          BlockHitResult recovered = resolvePlacementHit();
          if (recovered != null
              && !requiresForceSneak(stack, recovered)
              && !isInteractiveSupport(recovered.getBlockPos())) {
            hit = recovered;
          }
        }
        if (hit == null) {
          if (reapproachInPhase15()) return;
          finish("失败：[PLACE_CHANGED] 物品或支撑面不可见。");
          return;
        }
      }
      if (scaffoldingStackAnchor != null) {
        sneakHold.release();
        sneaking = false;
        if (mc().player.isShiftKeyDown()) {
          // Vanilla ScaffoldingBlockItem stacks upward only when NOT sneaking against an existing scaffold side face.
          if (sneakReleaseTicks == 0) {
            if (mc().getConnection() != null) {
              mc()
                  .getConnection()
                  .send(
                      new ServerboundPlayerCommandPacket(
                          mc().player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
            }
            mc().player.setShiftKeyDown(false);
          }
          if (++sneakReleaseTicks > 5) {
            finish("失败：[SNEAK_CONFLICT] 玩家持续潜行中，无法进行脚手架垂直叠放。");
            return;
          }
          return;
        }
        sneakReleaseTicks = 0;
      } else {
        boolean forceSneak = requiresForceSneak(stack, hit);
        boolean needSneak = forceSneak || isInteractiveSupport(hit.getBlockPos());
        if (needSneak) {
          if (forceSneak || !mc().player.isShiftKeyDown()) {
            sneakHold.hold();
            sneaking = true;
            if (forceSneak && ++sneakWarmup < 2) return;
          }
          var hitNow = resolvePlacementHit();
          if (hitNow == null) {
            if (reapproachInPhase15()) return;
            finish("失败：[PLACE_CHANGED] 潜行稳定后支撑面不可见。");
            return;
          }
          hit = hitNow;
        } else if (sneaking) {
          releaseSneak();
        }
      }
      if (hit == null
          || !intent.hitDirectionAllowed(mc().player.getEyePosition(), hit, block)) {
        finish("失败：[PLACEMENT_STATE_UNAVAILABLE] 当前视角/命中点不在目标朝向扇区内，未发送放置");
        return;
      }
      if (!aim.ready(hit.getLocation())) {
        if (aim.timedOut()) {
          finish("失败：[AIM_UNSTABLE] 瞄准未能在20tick内稳定收敛，未发送放置");
        }
        return;
      }
      var rawCtx = new BlockPlaceContext(mc().player, InteractionHand.MAIN_HAND, stack, hit);
      var context =
          (stack.getItem() instanceof BlockItem bi) ? bi.updatePlacementContext(rawCtx) : rawCtx;
      if (context == null || !context.canPlace() || !context.getClickedPos().equals(pos)) {
        finish("失败：[PLACE_CONDITION] 位置不能正常放置该方块。");
        return;
      }
      PlayerEvidence.forget(pos);
      beforePlaceState = mc().level.getBlockState(pos);
      var predicted = block.getStateForPlacement(context);
      if (predicted == null || !intent.matches(predicted)) {
        finish(
            "失败：[PLACEMENT_STATE_UNAVAILABLE] 当前正常站位/支撑面不能得到期望状态="
                + desired
                + "；客户端预测="
                + predicted
                + "，未发送放置");
        return;
      }
      var player = mc().player;
      if (player.connection != null) {
        player.connection.send(
            new ServerboundMovePlayerPacket.Rot(
                player.getYRot(), player.getXRot(), player.onGround()));
      }
      mc().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
      player.swing(InteractionHand.MAIN_HAND);
      phase = 2;
      wait = 0;
      return;
    }
    if (sneaking) sneakHold.maintain();
    var server = PlayerEvidence.block(pos);
    var live = mc().level.getBlockState(pos);
    boolean matched =
        server != null
            && placedMatches(server)
            && live.equals(server)
            && counterpartMatches(server);
    if (phase == 2 && matched && SignEditor.active()) {
      lastPlaced = pos;
      WorldSense.record(pos, server);
      RollbackJob.recordPlace(pos, beforePlaceState, server, "PlaceJob");
      Store.saveSoon();
      finish("部分完成：[PLACED_EDITOR_OPEN] 告示牌方块已由服务器确认；当前编辑界面可用write_sign写字或close_screen关闭，物品消耗待核实");
      return;
    }
    if (phase == 2) {
      if (matched) {
        releaseSneak();
        phase = 3;
      } else if (server != null
          && server.is(block)
          && live.equals(server)
          && !intent.matches(server)) {
        releaseSneak();
        finish(
            "部分完成：[PLACED_WRONG_STATE] 目标已放置但朝向/属性不符：期望="
                + desired
                + "，实际="
                + server
                + "；已保留现场不自动重放。");
      } else if (++wait > 100) {
        finish("部分完成：[PLACE_UNCONFIRMED] 没有匹配的服务器方块更新，未计作成功；请核对现场。");
      }
      return;
    }
    if (creative) {
      if (mc().gameMode.getPlayerMode() != GameType.CREATIVE
          || !matched
          || !placedMatches(mc().level.getBlockState(pos))) {
        finish("部分完成：[PLACE_CHANGED] 模式或已确认方块又变化，请重新观察，不自动重放。");
        return;
      }
      lastPlaced = pos;
      WorldSense.record(pos, mc().level.getBlockState(pos));
      RollbackJob.recordPlace(pos, beforePlaceState, mc().level.getBlockState(pos), "PlaceJob");
      Store.saveSoon();
      finish(
          "成功：创造模式服务器确认放置 "
              + BuiltInRegistries.BLOCK.getKey(block)
              + " 于 "
              + pos.toShortString()
              + "；不以材料消耗作为验收条件。");
      return;
    }
    int r = verify.tick();
    if (r < 0) {
      finish("部分完成：方块已出现，但" + verify.error);
      return;
    }
    if (r == 0) return;
    if (PlacementProof.accepted(
        true,
        matched,
        placedMatches(mc().level.getBlockState(pos)),
        before,
        PlayerEvidence.total(PlayerEvidence.actual(fingerprint)),
        PlayerEvidence.total(PlayerEvidence.confirmed(fingerprint)))) {
      lastPlaced = pos;
      WorldSense.record(pos, mc().level.getBlockState(pos));
      RollbackJob.recordPlace(pos, beforePlaceState, mc().level.getBlockState(pos), "PlaceJob");
      Store.saveSoon();
      verify.close();
      finish(
          "成功：服务器确认放置 "
              + BuiltInRegistries.BLOCK.getKey(block)
              + " 于 "
              + pos.toShortString()
              + "，物品减少1。");
    } else finish("部分完成：[PLACE_COUNT_UNCONFIRMED] 方块或物品减少未取得共同证明，请核对；不自动重试。");
  }

  private boolean placedMatches(net.minecraft.world.level.block.state.BlockState state) {
    boolean sameBlock =
        state.is(block)
            || (block instanceof net.minecraft.world.level.block.StandingSignBlock
                && state.getBlock() instanceof net.minecraft.world.level.block.WallSignBlock
                && net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(state.getBlock())
                    .toString()
                    .equals(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(block)
                            .toString()
                            .replace("_sign", "_wall_sign")))
            || block instanceof net.minecraft.world.level.block.TorchBlock
                && net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(state.getBlock())
                    .toString()
                    .equals(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(block)
                            .toString()
                            .replace("torch", "wall_torch"));
    return sameBlock && intent.matches(state);
  }

  private boolean counterpartMatches(net.minecraft.world.level.block.state.BlockState state) {
    BlockPos other;
    if (block instanceof net.minecraft.world.level.block.DoorBlock) other = pos.above();
    else if (block instanceof net.minecraft.world.level.block.BedBlock)
      other =
          pos.relative(
              state.getValue(
                  net.minecraft.world.level.block.state.properties.BlockStateProperties
                      .HORIZONTAL_FACING));
    else return true;
    var actual = mc().level.getBlockState(other);
    var received = PlayerEvidence.block(other);
    return received != null
        && received.equals(actual)
        && actual.is(block)
        && (block instanceof net.minecraft.world.level.block.DoorBlock
            ? actual.getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties
                        .DOUBLE_BLOCK_HALF)
                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
            : actual.getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)
                == net.minecraft.world.level.block.state.properties.BedPart.HEAD);
  }

  /** Unreachable receipt keeps the target position and the stance-candidate diagnosis. */
  private String unreachable() {
    String candidates = nav.candidateReport();
    return "失败：[UNREACHABLE] 目标="
        + pos.toShortString()
        + "；"
        + nav.error()
        + (candidates.isEmpty() || nav.error().contains("candidates=") ? "" : "；" + candidates);
  }

  private void releaseSneak() {
    sneakHold.release();
    if (sneaking && mc().player != null) {
      mc().player.setShiftKeyDown(false);
      if (mc().getConnection() != null)
        mc().getConnection()
            .send(
                new ServerboundPlayerCommandPacket(
                    mc().player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
      sneaking = false;
    }
  }

  @Override
  void stop() {
    releaseSneak();
    verify.close();
    nav.stop();
    if (prepare != null) prepare.stop();
    super.stop();
  }

  @Override
  String interrupted() {
    return "放置已停止；已发出的放置可能生效，请核对当前位置和物品，避免重复放置。";
  }

  private List<BlockPos> findScaffoldingColumnAnchors(
      BlockPos target, net.minecraft.world.level.block.Block scafBlock) {
    int playerY = mc().player.blockPosition().getY();
    List<BlockPos> rungs = new ArrayList<>();
    BlockPos cur = target.below();
    for (int steps = 0; steps < 64 && mc().level.hasChunkAt(cur); steps++, cur = cur.below()) {
      if (!mc().level.getBlockState(cur).is(scafBlock)) break;
      rungs.add(cur.immutable());
    }
    rungs.sort(java.util.Comparator.comparingInt(c -> Math.abs(c.getY() - playerY)));
    return rungs.size() > 4 ? rungs.subList(0, 4) : rungs;
  }

  private List<Direction> orderedHorizontalSides(BlockPos anchor) {
    List<Direction> sides =
        new ArrayList<>(
            List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST));
    Vec3 eye = mc().player.getEyePosition();
    sides.sort(
        java.util.Comparator.comparingDouble(
            d -> {
              Vec3 c = Vec3.atCenterOf(anchor).add(d.getStepX() * 0.5, 0, d.getStepZ() * 0.5);
              return eye.distanceToSqr(c);
            }));
    return sides;
  }

  private BlockHitResult resolvePlacementHit() {
    Vec3 eye = mc().player.getEyePosition();
    if (scaffoldingStackAnchor != null) {
      if (!mc().level.hasChunkAt(scaffoldingStackAnchor)
          || !mc().level.getBlockState(scaffoldingStackAnchor).is(block)) {
        return null;
      }
      if (scaffoldingStackFace != null) {
        BlockHitResult h = Reach.faceFrom(scaffoldingStackAnchor, eye, scaffoldingStackFace);
        if (h != null) return h;
      }
      for (Direction side : orderedHorizontalSides(scaffoldingStackAnchor)) {
        BlockHitResult h = Reach.faceFrom(scaffoldingStackAnchor, eye, side);
        if (h != null) {
          scaffoldingStackFace = side;
          return h;
        }
      }
      return null;
    }
    return Reach.placementFrom(
        pos, eye, face, hit -> intent.hitAllowed(hit) && intent.hitDirectionAllowed(eye, hit, block));
  }

  private boolean requiresForceSneak(ItemStack stack, BlockHitResult hit) {
    String stackId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    boolean scaffoldingOnScaffolding =
        block instanceof ScaffoldingBlock
            && hit != null
            && mc().level.hasChunkAt(hit.getBlockPos())
            && mc().level.getBlockState(hit.getBlockPos()).is(block);
    return BreakPolicy.requiresSneakToPlace(stackId)
        || stackId.contains("backpack")
        || scaffoldingOnScaffolding;
  }

  private static boolean isInteractiveSupport(BlockPos supportPos) {
    if (mc().level == null || supportPos == null || !mc().level.hasChunkAt(supportPos)) return false;
    var state = mc().level.getBlockState(supportPos);
    if (state.hasBlockEntity()) return true;
    Block b = state.getBlock();
    return b instanceof DoorBlock
        || b instanceof TrapDoorBlock
        || b instanceof FenceGateBlock
        || b instanceof ButtonBlock
        || b instanceof LeverBlock
        || b instanceof CraftingTableBlock
        || b instanceof AnvilBlock
        || b instanceof BedBlock
        || b instanceof NoteBlock
        || b instanceof DiodeBlock
        || b instanceof AbstractCauldronBlock
        || b instanceof ComposterBlock
        || b instanceof BellBlock
        || b instanceof LecternBlock
        || b instanceof LoomBlock
        || b instanceof StonecutterBlock
        || b instanceof GrindstoneBlock
        || b instanceof CartographyTableBlock
        || b instanceof SmithingTableBlock
        || b instanceof JukeboxBlock
        || b instanceof ScaffoldingBlock
        || b instanceof CakeBlock
        || b instanceof FlowerPotBlock
        || b instanceof DragonEggBlock
        || b instanceof AbstractCandleBlock;
  }

  private boolean reapproachInPhase15() {
    if (phase15Reapproaches >= 2) return false;
    phase15Reapproaches++;
    releaseSneak();
    aim.reset();
    nav.exactStance(true);
    if (!nav.startForPlacement(pos, face)) {
      finish(unreachable());
      return true;
    }
    phase = 1;
    return true;
  }
}
