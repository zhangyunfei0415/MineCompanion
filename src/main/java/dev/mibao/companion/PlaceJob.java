package dev.mibao.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.*;

/** Place one real held block; never switches to empty hand or writes world state directly. */
public final class PlaceJob extends Actions.Job {
  public static BlockPos lastPlaced;
  private Direction face;

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
  private boolean sneaking;
  private final boolean creative;

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
      return "[TARGET_OCCUPIED] 目标格不可替换：" + BuiltInRegistries.BLOCK.getKey(mc().level.getBlockState(p).getBlock());
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
    Actions.status = "放置方块 · " + (phase == 0 ? "准备物品" : phase < 3 ? "寻找可见支撑面" : "核对方块与消耗");
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
          finish("失败：[CREATIVE_HOTBAR_REQUIRED] 创造建筑材料须已在快捷栏，不生成物品。");
          return;
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
      if (!safe(pos)) {
        finish("失败：[PLACE_CHANGED] 放置位置已变化。");
        return;
      }
      var hit = Reach.placementFrom(pos, mc().player.getEyePosition(), face);
      var stack = mc().player.getMainHandItem();
      if (hit == null || !Actions.match(stack, fingerprint)) {
        finish("失败：[PLACE_CHANGED] 物品或支撑面不可见。");
        return;
      }
      var context = new BlockPlaceContext(mc().player, InteractionHand.MAIN_HAND, stack, hit);
      if (!context.canPlace() || !context.getClickedPos().equals(pos)) {
        finish("失败：[PLACE_CONDITION] 位置不能正常放置该方块。");
        return;
      }
      if (!mc().player.isShiftKeyDown()) {
        mc().player.setShiftKeyDown(true);
        mc().getConnection()
            .send(
                new ServerboundPlayerCommandPacket(
                    mc().player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
        sneaking = true;
      }
      PlayerEvidence.forget(pos);
      if (!aim.ready(hit.getLocation())) return;
      mc().gameMode.useItemOn(mc().player, InteractionHand.MAIN_HAND, hit);
      mc().player.swing(InteractionHand.MAIN_HAND);
      phase = 2;
      wait = 0;
      return;
    }
    var server = PlayerEvidence.block(pos);
    boolean matched =
        server != null && placedMatches(server) && mc().level.getBlockState(pos).equals(server);
    if (phase == 2 && matched && SignEditor.active()) {
      lastPlaced = pos;
      WorldSense.record(pos, server);
      Store.save();
      finish("部分完成：[PLACED_EDITOR_OPEN] 告示牌方块已由服务器确认；当前编辑界面可用write_sign写字或close_screen关闭，物品消耗待核实");
      return;
    }
    if (phase == 2) {
      if (matched) {
        releaseSneak();
        phase = 3;
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
      Store.save();
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
      Store.save();
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
    return state.is(block)
        || (block instanceof net.minecraft.world.level.block.StandingSignBlock
            && state.getBlock() instanceof net.minecraft.world.level.block.WallSignBlock
            && net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock())
                .toString()
                .equals(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(block)
                        .toString()
                        .replace("_sign", "_wall_sign")));
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
    nav.stop();
    if (prepare != null) prepare.stop();
    super.stop();
  }

  @Override
  String interrupted() {
    return "放置已停止；已发出的放置可能生效，请核对当前位置和物品，避免重复放置。";
  }
}
