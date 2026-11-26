package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Standalone PRO-level agent scaffolding primitive ({@code scaffold_build}):
 * <ul>
 *   <li>{@code pillar_up}: verified jump-and-place under feet (1..12 blocks)
 *   <li>{@code ramp}: hollow 2-block-per-step solid staircase toward target_y
 *   <li>{@code scaffolding_tower}: vertical {@code minecraft:scaffolding} + overhang bridge
 *   <li>{@code temp_support}: single temporary support block for awkward side faces
 *   <li>{@code teardown}: LIFO safe reverse removal of tracked scaffold blocks
 * </ul>
 * Temporary scaffolds are recorded into {@link Store#scaffoldLedger()} with {@link
 * RollbackJob#recordingSuppressed} so they never pollute player {@link RollbackJournal}.
 */
public final class ScaffoldJob extends Actions.Job {
  private final String action;
  private final String blockId;
  private final ArrayDeque<BlueprintScaffoldPlanner.ScaffoldCell> planQueue = new ArrayDeque<>();
  private final int pillarTargetHeight;
  private boolean descentRequiredBeforeMine;
  private final int teardownLimit;

  private PlaceJob activePlace;
  private MiningJob activeMine;
  private CollectionJob activeCollect;
  private Navigator approachNav;
  private BlockPos approachTarget;
  private boolean solidStepOffTried;
  private boolean dropCollectStarted;
  private InventoryMove prepare;
  private int completed, skipped, failed, collectedDrops;
  private final Set<String> removedBlockIds = new LinkedHashSet<>();
  private final Deque<String> pendingDropCollectIds = new ArrayDeque<>();
  private int pillarPhase, pillarDone, pillarStepTicks, pillarWaitConfirm;
  private BlockPos pillarNextPos;
  private BlockState pillarBeforeState;
  private boolean finishedNormally;
  private final ClimbExit climbExit = new ClimbExit();

  static BlockPos findSolidStepOffCell(
      Minecraft mc,
      BlockPos targetPos,
      int groundY,
      java.util.function.Predicate<BlockPos> isScaffoldBlock) {
    if (mc.player == null || mc.level == null) return null;
    BlockPos origin = mc.player.blockPosition();
    BlockPos best = null;
    double bestScore = Double.MAX_VALUE;
    int minDy = Math.max(-8, Math.min(-2, groundY - targetPos.getY()));
    for (int dy = minDy; dy <= 1; dy++) {
      for (int dx = -3; dx <= 3; dx++) {
        for (int dz = -3; dz <= 3; dz++) {
          BlockPos cand = targetPos.offset(dx, dy, dz);
          if (!mc.level.hasChunkAt(cand)) continue;
          if (TeardownReachPlanner.needsSolidStepOff(
              cand.getX(),
              cand.getY(),
              cand.getZ(),
              targetPos.getX(),
              targetPos.getY(),
              targetPos.getZ())) {
            continue;
          }
          if (Navigator.isClimbable(cand)
              || !mc.level.getBlockState(cand).getFluidState().isEmpty()
              || !Double.isFinite(Navigator.surfaceY(cand))
              || !Navigator.stand(cand)) {
            continue;
          }
          boolean onScaffold =
              (isScaffoldBlock != null
                      && (isScaffoldBlock.test(cand) || isScaffoldBlock.test(cand.below())))
                  || mc.level.getBlockState(cand).getBlock() instanceof ScaffoldingBlock
                  || (mc.level.hasChunkAt(cand.below())
                      && mc.level.getBlockState(cand.below()).getBlock()
                          instanceof ScaffoldingBlock);
          if (onScaffold) continue;
          double score =
              Math.abs(cand.getY() - groundY) * 10.0 + Math.sqrt(cand.distSqr(origin));
          if (score < bestScore) {
            bestScore = score;
            best = cand;
          }
        }
      }
    }
    return best;
  }

  static final class ClimbExit {
    enum ExitState {
      MOVING,
      SAFE,
      BLOCKED
    }

    private int descentTicks, stuckTicks, stepOutTicks;
    private double lastY = Double.NaN;
    private BlockPos stepOutCell;

    void reset() {
      descentTicks = 0;
      stuckTicks = 0;
      stepOutTicks = 0;
      lastY = Double.NaN;
      stepOutCell = null;
    }

    ExitState step(Minecraft mc) {
      var p = mc.player;
      if (p == null || mc.level == null) {
        PlayerControls.release();
        return ExitState.BLOCKED;
      }
      boolean inScaffolding =
          mc.level.hasChunkAt(p.blockPosition())
              && mc.level.getBlockState(p.blockPosition()).getBlock() instanceof ScaffoldingBlock;
      boolean belowScaffolding =
          mc.level.hasChunkAt(p.blockPosition().below())
              && mc.level.getBlockState(p.blockPosition().below()).getBlock()
                  instanceof ScaffoldingBlock;
      int columnDepth = 0;
      BlockPos probe = p.blockPosition().below();
      for (int k = 0; k < 24 && mc.level.hasChunkAt(probe); k++, probe = probe.below()) {
        if (!(mc.level.getBlockState(probe).getBlock() instanceof ScaffoldingBlock)) break;
        columnDepth++;
      }
      int maxDescentTicks = TeardownReachPlanner.descentBudgetTicks(columnDepth + (inScaffolding ? 1 : 0));

      if (stepOutCell == null) {
        double curY = p.getY();
        if (!Double.isNaN(lastY) && Math.abs(curY - lastY) < 0.02) stuckTicks++;
        else stuckTicks = 0;
        lastY = curY;

        BlockPos chosen = findSafeStepOut(mc, p.blockPosition());
        TeardownReachPlanner.ExitAction act =
            TeardownReachPlanner.evaluateClimbExit(
                true,
                p.onClimbable(),
                p.onGround(),
                belowScaffolding,
                inScaffolding,
                chosen != null,
                descentTicks,
                stuckTicks,
                maxDescentTicks);
        switch (act) {
          case SAFE -> {
            PlayerControls.release();
            return ExitState.SAFE;
          }
          case DESCEND -> {
            descentTicks++;
            PlayerControls.motionState(false, false, false, true);
            Actions.status = "脚手架安全下降准备拆架...";
            return ExitState.MOVING;
          }
          case BLOCKED -> {
            PlayerControls.release();
            return ExitState.BLOCKED;
          }
          case STEP_OUT -> stepOutCell = chosen;
        }
      }
      stepOutTicks++;
      if (!p.onClimbable() && !belowScaffolding && !inScaffolding) {
        PlayerControls.release();
        return p.onGround() ? ExitState.SAFE : ExitState.BLOCKED;
      }
      if (stepOutTicks > 25) {
        PlayerControls.release();
        return ExitState.BLOCKED;
      }
      double dx = stepOutCell.getX() + 0.5 - p.getX();
      double dz = stepOutCell.getZ() + 0.5 - p.getZ();
      p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
      p.setXRot(0f);
      PlayerControls.motionState(true, false, false, true);
      Actions.status = "离开脚手架柱体...";
      return ExitState.MOVING;
    }

    private static BlockPos findSafeStepOut(Minecraft mc, BlockPos base) {
      for (Direction d : Direction.Plane.HORIZONTAL) {
        BlockPos c = base.relative(d);
        if (!mc.level.hasChunkAt(c) || !mc.level.hasChunkAt(c.below())) continue;
        BlockState st = mc.level.getBlockState(c);
        BlockState above = mc.level.getBlockState(c.above());
        BlockState below = mc.level.getBlockState(c.below());
        if (!(st.getBlock() instanceof ScaffoldingBlock)
            && !(below.getBlock() instanceof ScaffoldingBlock)
            && st.getFluidState().isEmpty()
            && above.getFluidState().isEmpty()
            && st.getCollisionShape(mc.level, c).isEmpty()
            && above.getCollisionShape(mc.level, c.above()).isEmpty()
            && !below.getCollisionShape(mc.level, c.below()).isEmpty()) {
          return c.immutable();
        }
      }
      return null;
    }
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public static JsonObject statusJson() {
    JsonArray ledger = Store.scaffoldLedger();
    var out = new JsonObject();
    out.addProperty("dimension", Store.worldDimKey());
    out.addProperty("tracked_count", ledger.size());
    var preview = new JsonArray();
    for (int i = Math.max(0, ledger.size() - 12); i < ledger.size(); i++) {
      preview.add(ledger.get(i));
    }
    out.add("recent_cells", preview);
    return out;
  }

  public static JsonObject abortActive() {
    if (Actions.job instanceof ScaffoldJob job) {
      job.stopChildren();
      job.finishedNormally = true;
      job.finish("部分完成：[SCAFFOLD_ABORTED] 已中止脚手架作业，未拆脚手架已保留在账本供后续 teardown。" + statusJson());
      var out = statusJson();
      out.addProperty("aborted", true);
      return out;
    }
    var out = statusJson();
    out.addProperty("aborted", false);
    out.addProperty("message", "当前没有活跃的脚手架作业");
    return out;
  }

  public ScaffoldJob(JsonObject a, Consumer<String> done) {
    super(done);
    TaskReadiness.requireWorld(false);
    this.action = Agent.str(a, "action", "status").strip().toLowerCase(Locale.ROOT);
    String explicitItem = Agent.str(a, "item", "");
    this.teardownLimit = Math.max(0, Agent.number(a, "limit", 0));

    var p = mc().player;
    if (p == null || mc().level == null)
      throw new IllegalStateException("[WORLD_REQUIRED] 请先进入世界");
    boolean creative =
        mc().gameMode != null && mc().gameMode.getPlayerMode() == GameType.CREATIVE;
    if (!creative && mc().gameMode != null && mc().gameMode.getPlayerMode() != GameType.SURVIVAL) {
      throw new IllegalArgumentException("[MODE_REQUIRED] 脚手架施工需要生存或创造模式");
    }

    switch (action) {
      case "pillar_up" -> {
        int h = Math.max(1, Math.min(12, Agent.number(a, "height", 2)));
        String mat = pickMaterial(BlueprintScaffoldPlanner.Mode.PILLAR, explicitItem, h, h);
        this.blockId = mat;
        this.pillarTargetHeight = h;
        this.descentRequiredBeforeMine = false;
      }
      case "temp_support" -> {
        int x = Rules.requiredInt(a, "x");
        int y = Rules.requiredInt(a, "y");
        int z = Rules.requiredInt(a, "z");
        String mat = pickMaterial(BlueprintScaffoldPlanner.Mode.PILLAR, explicitItem, 1, 1);
        this.blockId = mat;
        this.pillarTargetHeight = 0;
        this.descentRequiredBeforeMine = false;
        this.planQueue.add(
            new BlueprintScaffoldPlanner.ScaffoldCell(
                x, y, z, BlueprintScaffoldPlanner.Kind.TEMP_SUPPORT));
      }
      case "scaffolding_tower" -> {
        int targetY = Rules.requiredInt(a, "target_y");
        int bx = Agent.number(a, "x", p.blockPosition().getX());
        int by = Agent.number(a, "y", p.blockPosition().getY());
        int bz = Agent.number(a, "z", p.blockPosition().getZ());
        long rise = (long) targetY - (long) by;
        if (rise < 1 || rise > 64) {
          throw new IllegalArgumentException("[INVALID_ARGUMENT] target_y - y 须在 1..64 之间");
        }
        int boundedRise = (int) rise;
        String overhangDir = Agent.str(a, "overhang_dir", "").strip().toLowerCase(Locale.ROOT);
        int overhangLen = Math.max(0, Math.min(5, Agent.number(a, "overhang_len", 0)));
        int odx = "east".equals(overhangDir) ? 1 : ("west".equals(overhangDir) ? -1 : 0);
        int odz = "south".equals(overhangDir) ? 1 : ("north".equals(overhangDir) ? -1 : 0);
        String mat =
            pickMaterial(
                BlueprintScaffoldPlanner.Mode.SCAFFOLDING,
                explicitItem.isBlank() ? "minecraft:scaffolding" : explicitItem,
                boundedRise,
                boundedRise + overhangLen);
        this.blockId = mat;
        this.pillarTargetHeight = 0;
        this.descentRequiredBeforeMine = false;
        var cells =
            BlueprintScaffoldPlanner.planScaffoldingTower(
                bx, by, bz, targetY + 1, odx, odz, overhangLen, Set.of());
        if (cells.isEmpty())
          throw new IllegalArgumentException("[INVALID_ARGUMENT] target_y 须高于起点 y");
        this.planQueue.addAll(cells);
      }
      case "ramp" -> {
        int targetY = Rules.requiredInt(a, "target_y");
        String dir = Agent.str(a, "direction", "north").strip().toLowerCase(Locale.ROOT);
        if (!Set.of("north", "south", "east", "west").contains(dir))
          throw new IllegalArgumentException("[INVALID_ARGUMENT] direction须为 north/south/east/west");
        int bx = Agent.number(a, "x", p.blockPosition().getX());
        int by = Agent.number(a, "y", p.blockPosition().getY());
        int bz = Agent.number(a, "z", p.blockPosition().getZ());
        int rise = targetY - by;
        if (rise <= 0 || rise > 32)
          throw new IllegalArgumentException("[INVALID_ARGUMENT] target_y - y 须在 1..32 之间");
        String mat = pickMaterial(BlueprintScaffoldPlanner.Mode.SOLID_RAMP, explicitItem, rise, rise * 2 + 2);
        this.blockId = mat;
        this.pillarTargetHeight = 0;
        this.descentRequiredBeforeMine = false;
        Direction d = Direction.byName(dir);
        for (int s = 1; s <= rise; s++) {
          int cx = bx + d.getStepX() * s;
          int cz = bz + d.getStepZ() * s;
          int cy = by + s - 1;
          if (s > 1) {
            planQueue.add(
                new BlueprintScaffoldPlanner.ScaffoldCell(
                    cx, cy - 1, cz, BlueprintScaffoldPlanner.Kind.RAMP_SUPPORT));
          }
          planQueue.add(
              new BlueprintScaffoldPlanner.ScaffoldCell(
                  cx, cy, cz, BlueprintScaffoldPlanner.Kind.RAMP_STEP));
        }
      }
      case "teardown" -> {
        this.blockId = "";
        this.pillarTargetHeight = 0;
        this.descentRequiredBeforeMine =
            p.onClimbable()
                || mc().level.getBlockState(p.blockPosition()).getBlock() instanceof ScaffoldingBlock
                || mc().level.getBlockState(p.blockPosition().below()).getBlock()
                    instanceof ScaffoldingBlock;
      }
      default -> throw new IllegalArgumentException(
          "[INVALID_ARGUMENT] action须为 status/pillar_up/ramp/scaffolding_tower/temp_support/teardown");
    }
  }

  private static String pickMaterial(
      BlueprintScaffoldPlanner.Mode mode, String explicitItem, int heightNeeded, int estCells) {
    Map<String, Integer> bag = BlueprintLibrary.playerBagItemCounts();
    if (mc().gameMode != null && mc().gameMode.getPlayerMode() == GameType.CREATIVE && explicitItem.isBlank()) {
      return mode == BlueprintScaffoldPlanner.Mode.SCAFFOLDING
          ? "minecraft:scaffolding"
          : "minecraft:cobblestone";
    }
    var choice =
        BlueprintScaffoldPlanner.selectMaterial(
            mode, explicitItem, bag, Map.of(), heightNeeded, estCells);
    if (!choice.error().isBlank()) throw new IllegalArgumentException(choice.error());
    return choice.blockId();
  }

  @Override
  void tick() {
    if (finishedNormally) return;
    if (++ticks > 7200) {
      finishSafely("部分完成：[SCAFFOLD_TIMEOUT] 脚手架作业超时，完成 " + completed + "，跳过 " + skipped + "，失败 " + failed);
      return;
    }
    var mc = mc();
    if (mc.player == null || mc.level == null) {
      finishSafely("失败：[WRONG_CONTEXT] 世界未就绪");
      return;
    }

    boolean prevSuppressed = RollbackJob.recordingSuppressed;
    RollbackJob.recordingSuppressed = true;
    try {
      if ("pillar_up".equals(action)) {
        tickPillarUp(mc);
        return;
      }
      if ("teardown".equals(action)) {
        tickTeardown(mc);
        return;
      }
      tickPlanPlacement(mc);
    } finally {
      RollbackJob.recordingSuppressed = prevSuppressed;
    }
  }

  private void tickPlanPlacement(Minecraft mc) {
    if (activePlace != null) {
      activePlace.tick();
      Actions.status = "搭设脚手架 · 剩余 " + (planQueue.size() + 1) + " 格";
      return;
    }
    while (!planQueue.isEmpty()) {
      var cell = planQueue.removeFirst();
      BlockPos pos = new BlockPos(cell.wx(), cell.wy(), cell.wz());
      if (!mc.level.hasChunkAt(pos)) {
        failed++;
        continue;
      }
      BlockState cur = mc.level.getBlockState(pos);
      if (BuiltInRegistries.BLOCK.getKey(cur.getBlock()).toString().equals(blockId)) {
        skipped++;
        continue;
      }
      if (!cur.canBeReplaced() && !cur.isAir()) {
        skipped++;
        continue;
      }
      PlaceJob job =
          new PlaceJob(
              blockId,
              pos,
              reply -> {
                activePlace = null;
                if (finishedNormally || Actions.job != this) return;
                if (!Rules.actionFailed(reply)) {
                  completed++;
                  recordScaffold(cell, blockId);
                } else {
                  failed++;
                }
              });
      job.managed = true;
      activePlace = job;
      return;
    }
    String head = failed == 0 ? "成功" : (completed > 0 ? "部分完成" : "失败");
    finishSafely(
        head
            + "：脚手架("
            + action
            + ")完成，新放 "
            + completed
            + "，跳过已有/占格 "
            + skipped
            + "，失败 "
            + failed
            + "。"
            + statusJson());
  }

  private void tickPillarUp(Minecraft mc) {
    Actions.status = "脚手架垫高 · " + pillarDone + "/" + pillarTargetHeight;
    if (pillarDone >= pillarTargetHeight) {
      finishSafely("成功：原地垫高完成 " + pillarDone + " 格（" + blockId + "）。" + statusJson());
      return;
    }
    var p = mc.player;
    boolean creative =
        mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.CREATIVE;
    if (pillarPhase == 0) {
      if (prepare == null && !creative) {
        prepare = InventoryMove.ensure(s -> s.getItem() instanceof BlockItem && Actions.match(s, blockId));
      }
      if (prepare != null) {
        int r = prepare.tick();
        if (r < 0) {
          finishSafely("失败：" + prepare.error);
          return;
        }
        if (r == 0) return;
        p.getInventory().selected = prepare.readySlot;
        prepare = null;
      } else {
        int slot = findHotbarBlock(mc, blockId);
        if (slot < 0) {
          finishSafely("失败：[NEED_SCAFFOLD] 快捷栏找不到 " + blockId);
          return;
        }
        p.getInventory().selected = slot;
      }

      BlockPos feet = p.blockPosition();
      if (!mc.level.hasChunkAt(feet) || !mc.level.hasChunkAt(feet.above(2))) {
        finishSafely("失败：[UNLOADED_TARGET] 头顶或脚下区块未加载");
        return;
      }
      if (!mc.level.getBlockState(feet.above(2)).getCollisionShape(mc.level, feet.above(2)).isEmpty()) {
        finishSafely("部分完成：[CEILING_BLOCKED] 头顶两格被遮挡，已垫高 " + pillarDone + " 格");
        return;
      }
      BlockState atFeet = mc.level.getBlockState(feet);
      if (!atFeet.canBeReplaced() && !atFeet.isAir()) {
        finishSafely("部分完成：[TARGET_OCCUPIED] 脚下位置被占用，已垫高 " + pillarDone + " 格");
        return;
      }
      pillarNextPos = feet.immutable();
      pillarBeforeState = atFeet;
      pillarStepTicks = 0;
      pillarWaitConfirm = 0;
      pillarPhase = 1;
      return;
    }

    if (pillarPhase == 1) {
      // Align horizontally near block center and look straight down before jumping
      double cx = pillarNextPos.getX() + 0.5;
      double cz = pillarNextPos.getZ() + 0.5;
      if (Math.abs(p.getX() - cx) > 0.25 || Math.abs(p.getZ() - cz) > 0.25) {
        float yaw = (float) Math.toDegrees(Math.atan2(-(cx - p.getX()), cz - p.getZ()));
        p.setYRot(yaw);
        p.setXRot(88f);
        PlayerControls.motionState(true, false, false, true);
        if (++pillarStepTicks < 18) return;
      }
      PlayerControls.release();
      p.setXRot(89f);
      p.setYHeadRot(p.getYRot());
      PlayerEvidence.forget(pillarNextPos);
      if (!p.onGround()) {
        if (++pillarStepTicks > 30) {
          finishSafely("部分完成：[UNSTABLE_FOOTING] 未稳立脚底，已垫高 " + pillarDone + " 格");
        }
        return;
      }
      pillarStepTicks = 0;
      pillarPhase = 2;
      return;
    }

    if (pillarPhase == 2) {
      // Hold jump cleanly until feet clear pillarNextPos + 1.02, then click top of support block below
      p.setXRot(89f);
      PlayerControls.motionState(false, true, false, false);
      if (++pillarStepTicks > 20) {
        PlayerControls.release();
        finishSafely("部分完成：[JUMP_TIMEOUT] 跳跃垫高超时，已垫高 " + pillarDone + " 格");
        return;
      }
      if (p.getY() >= pillarNextPos.getY() + 1.02) {
        var hit = Reach.top(pillarNextPos.below());
        if (hit == null) return;
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        p.swing(InteractionHand.MAIN_HAND);
        PlayerControls.motionState(false, false, false, false);
        pillarWaitConfirm = 0;
        pillarPhase = 3;
      }
      return;
    }

    // Phase 3: await server confirmation
    PlayerControls.motionState(false, false, false, false);
    BlockState server = PlayerEvidence.block(pillarNextPos);
    BlockState current = mc.level.getBlockState(pillarNextPos);
    String curId = BuiltInRegistries.BLOCK.getKey(current.getBlock()).toString();
    if (server != null && server.equals(current) && curId.equals(blockId)) {
      pillarDone++;
      completed++;
      recordScaffold(
          new BlueprintScaffoldPlanner.ScaffoldCell(
              pillarNextPos.getX(),
              pillarNextPos.getY(),
              pillarNextPos.getZ(),
              BlueprintScaffoldPlanner.Kind.PILLAR),
          blockId);
      pillarPhase = 0;
      return;
    }
    if (++pillarWaitConfirm > 25) {
      finishSafely("部分完成：[PLACE_UNCONFIRMED] 垫脚方块未获服务端确认，已垫高 " + pillarDone + " 格");
    }
  }

  private void tickTeardown(Minecraft mc) {
    if (descentRequiredBeforeMine) {
      ClimbExit.ExitState exit = climbExit.step(mc);
      if (exit == ClimbExit.ExitState.MOVING) return;
      if (exit == ClimbExit.ExitState.BLOCKED) {
        finishSafely(
            "部分完成：[CLIMB_EXIT_BLOCKED] 无法安全离开脚手架柱体，已保留账本停拆。" + statusJson());
        return;
      }
      descentRequiredBeforeMine = false;
    }
    if (activeMine != null) {
      activeMine.tick();
      Actions.status = "LIFO 安全拆架中 · 剩余 " + Store.scaffoldLedger().size() + " 格";
      return;
    }
    if (activeCollect != null) {
      activeCollect.tick();
      Actions.status = "脚手架回收掉落物 · 已收 " + collectedDrops + " 件";
      return;
    }

    if (approachNav != null) {
      int nr = approachNav.tick();
      if (nr == 0) {
        Actions.status = "接近待拆脚手架栈顶...";
        return;
      }
      BlockPos approached = approachTarget;
      lastApproachError = approachNav.error();
      approachNav.stop();
      approachNav = null;
      approachTarget = null;
      if (nr < 0) {
        failed++;
        finishSafely(
            "部分完成：[SCAFFOLD_TEARDOWN_BLOCKED] 无法接近栈顶脚手架("
                + (approached == null ? "未知" : approached.toShortString())
                + ")："
                + approachNavErrorFallback()
                + "；已保留剩余账本停拆。"
                + statusJson());
        return;
      }
    }

    JsonArray ledger = Store.scaffoldLedger();
    while (ledger.size() > 0 && (teardownLimit == 0 || completed < teardownLimit)) {
      int i = ledger.size() - 1;
      JsonElement raw = ledger.get(i);
      if (!raw.isJsonObject()) {
        ledger.remove(i);
        Store.saveSoon();
        skipped++;
        continue;
      }
      JsonObject top = raw.getAsJsonObject();
      if (!top.has("wx") || !top.has("wy") || !top.has("wz") || !top.has("block")) {
        ledger.remove(i);
        Store.saveSoon();
        skipped++;
        continue;
      }
      BlockPos pos =
          new BlockPos(top.get("wx").getAsInt(), top.get("wy").getAsInt(), top.get("wz").getAsInt());
      String expectedBlock = top.get("block").getAsString();
      boolean loaded = mc.level.hasChunkAt(pos);
      double dist = Math.sqrt(pos.distSqr(mc.player.blockPosition()));
      TeardownReachPlanner.Decision reachDecision = TeardownReachPlanner.plan(dist, loaded);
      if (reachDecision.mode() == TeardownReachPlanner.PlanMode.TOO_FAR) {
        failed++;
        finishSafely("部分完成：" + reachDecision.reason() + "。" + statusJson());
        return;
      }
      BlockState cur = mc.level.getBlockState(pos);
      String curId = BuiltInRegistries.BLOCK.getKey(cur.getBlock()).toString();
      if (cur.isAir() || !curId.equals(expectedBlock)) {
        ledger.remove(i);
        Store.saveSoon();
        skipped++;
        solidStepOffTried = false;
        continue;
      }
      if (TeardownReachPlanner.needsSolidStepOff(
          mc.player.blockPosition().getX(),
          mc.player.blockPosition().getY(),
          mc.player.blockPosition().getZ(),
          pos.getX(),
          pos.getY(),
          pos.getZ())) {
        if (!solidStepOffTried) {
          solidStepOffTried = true;
          BlockPos safeCell =
              findSolidStepOffCell(
                  mc,
                  pos,
                  mc.player.blockPosition().getY() - 1,
                  p -> isTrackedInLedger(ledger, p));
          if (safeCell != null) {
            Navigator nav = new Navigator();
            if (nav.startLocal(safeCell)) {
              approachNav = nav;
              approachTarget = safeCell;
              lastApproachError = "";
              return;
            }
          }
        }
        if (pos.equals(mc.player.blockPosition().below())) {
          finishSafely(
              "部分完成：[PLAYER_SUPPORT] 第 #"
                  + i
                  + " 格("
                  + pos.toShortString()
                  + ") 正处于玩家正脚下，已安全停拆（已拆 "
                  + completed
                  + " 格）。"
                  + statusJson());
          return;
        }
      }
      if (reachDecision.mode() == TeardownReachPlanner.PlanMode.APPROACH_THEN_MINE) {
        Navigator nav =
            new Navigator().interactionDy(-6, 3).allowScaffoldingColumnMine(true).maxRadius(5);
        if (!nav.startForInteraction(pos)) {
          failed++;
          finishSafely(
              "部分完成：[SCAFFOLD_TEARDOWN_BLOCKED] 栈顶脚手架("
                  + pos.toShortString()
                  + ")过远且无法寻路接近："
                  + Rules.clean(nav.error(), 160)
                  + "；已保留剩余账本停拆。"
                  + statusJson());
          return;
        }
        approachNav = nav;
        approachTarget = pos;
        lastApproachError = "";
        return;
      }
      try {
        MiningJob mine =
            new MiningJob(
                    expectedBlock,
                    1,
                    reachDecision.mineRadius(),
                    pos,
                    true,
                    5,
                    reply -> {
                      activeMine = null;
                      if (finishedNormally || Actions.job != this) return;
                      if (!Rules.actionFailed(reply)) {
                        removeLedgerPos(pos.getX(), pos.getY(), pos.getZ());
                        completed++;
                        removedBlockIds.add(expectedBlock);
                        solidStepOffTried = false;
                      } else {
                        failed++;
                        finishSafely(
                            "部分完成：[SCAFFOLD_TEARDOWN_BLOCKED] 栈顶脚手架("
                                + pos.toShortString()
                                + ")拆除受阻："
                                + Rules.clean(reply, 180)
                                + "；已保留剩余账本停拆。"
                                + statusJson());
                      }
                    })
                .scaffoldTeardown(true);
        mine.managed = true;
        activeMine = mine;
        return;
      } catch (IllegalArgumentException e) {
        failed++;
        finishSafely(
            "部分完成：[SCAFFOLD_TEARDOWN_BLOCKED] 栈顶脚手架("
                + pos.toShortString()
                + ")无法启动拆除："
                + Rules.clean(e.getMessage(), 160)
                + "；已保留剩余账本停拆。"
                + statusJson());
        return;
      }
    }

    boolean collectMode =
        mc.gameMode != null
            && (mc.gameMode.getPlayerMode() == GameType.SURVIVAL
                || mc.gameMode.getPlayerMode() == GameType.ADVENTURE);
    if (failed == 0
        && !dropCollectStarted
        && TeardownReachPlanner.shouldCollectTeardownDrops(
            collectMode, completed, !removedBlockIds.isEmpty())) {
      dropCollectStarted = true;
      pendingDropCollectIds.addAll(removedBlockIds);
    }
    if (dropCollectStarted && launchNextDropCollection(mc)) {
      return;
    }

    finishSafely(
        (failed == 0 ? "成功" : "部分完成")
            + "：脚手架 LIFO 拆解完成，已拆 "
            + completed
            + (collectedDrops > 0 ? "，已回收 " + collectedDrops + " 件" : "")
            + "，已不存在跳过 "
            + skipped
            + "，受阻 "
            + failed
            + "。"
            + statusJson());
  }

  private boolean launchNextDropCollection(Minecraft mc) {
    if (mc.level == null || mc.player == null) {
      pendingDropCollectIds.clear();
      return false;
    }
    if (mc.player.containerMenu != mc.player.inventoryMenu || mc.screen != null) {
      pendingDropCollectIds.clear();
      return false;
    }
    BlockPos center = mc.player.blockPosition();
    int radius = 14;
    var area = new net.minecraft.world.phys.AABB(center).inflate(radius);
    while (!pendingDropCollectIds.isEmpty()) {
      String itemId = pendingDropCollectIds.removeFirst();
      var drops =
          mc.level.getEntitiesOfClass(
              net.minecraft.world.entity.item.ItemEntity.class,
              area,
              e -> e.isAlive() && Actions.match(e.getItem(), itemId));
      if (drops.isEmpty()) continue;
      try {
        CollectionJob job =
            new CollectionJob(
                    itemId,
                    Integer.MAX_VALUE,
                    radius,
                    center,
                    e -> true,
                    true,
                    reply -> {
                      if (CollectionJob.lastReceipt != null) {
                        collectedDrops += Math.max(0, CollectionJob.lastReceipt.gained());
                      }
                      activeCollect = null;
                    })
                .maxTicks(360)
                .backgroundDuty(true);
        job.managed = true;
        activeCollect = job;
        Actions.status = "脚手架回收掉落物 · 已收 " + collectedDrops + " 件";
        return true;
      } catch (IllegalArgumentException e) {
        activeCollect = null;
      }
    }
    return false;
  }

  private static void removeLedgerPos(int wx, int wy, int wz) {
    JsonArray ledger = Store.scaffoldLedger();
    for (int idx = ledger.size() - 1; idx >= 0; idx--) {
      JsonElement el = ledger.get(idx);
      if (!el.isJsonObject()) continue;
      JsonObject row = el.getAsJsonObject();
      if (!row.has("wx") || !row.has("wy") || !row.has("wz")) continue;
      if (row.get("wx").getAsInt() == wx
          && row.get("wy").getAsInt() == wy
          && row.get("wz").getAsInt() == wz) {
        ledger.remove(idx);
        Store.saveSoon();
        return;
      }
    }
  }

  private static boolean isTrackedInLedger(JsonArray ledger, BlockPos pos) {
    for (int idx = 0; idx < ledger.size(); idx++) {
      JsonElement el = ledger.get(idx);
      if (!el.isJsonObject()) continue;
      JsonObject row = el.getAsJsonObject();
      if (!row.has("wx") || !row.has("wy") || !row.has("wz")) continue;
      if (row.get("wx").getAsInt() == pos.getX()
          && row.get("wy").getAsInt() == pos.getY()
          && row.get("wz").getAsInt() == pos.getZ()) {
        return true;
      }
    }
    return false;
  }

  private static int findHotbarBlock(Minecraft mc, String id) {
    for (int i = 0; i < 9; i++) {
      ItemStack s = mc.player.getInventory().getItem(i);
      if (!s.isEmpty() && s.getItem() instanceof BlockItem && Actions.match(s, id)) return i;
    }
    return -1;
  }

  private static void recordScaffold(BlueprintScaffoldPlanner.ScaffoldCell cell, String blockId) {
    var row = new JsonObject();
    row.addProperty("wx", cell.wx());
    row.addProperty("wy", cell.wy());
    row.addProperty("wz", cell.wz());
    row.addProperty("block", blockId);
    row.addProperty("kind", cell.kind().name());
    Store.scaffoldLedger().add(row);
    Store.saveSoon();
  }

  private void finishSafely(String msg) {
    finishedNormally = true;
    RollbackJob.recordingSuppressed = false;
    PlayerControls.release();
    finish(msg);
  }

  private String lastApproachError = "";

  private String approachNavErrorFallback() {
    return lastApproachError.isBlank() ? "导航接近失败" : lastApproachError;
  }

  private void stopChildren() {
    RollbackJob.recordingSuppressed = false;
    if (approachNav != null) {
      approachNav.stop();
      approachNav = null;
    }
    approachTarget = null;
    if (activePlace != null) {
      activePlace.stop();
      activePlace = null;
    }
    if (activeMine != null) {
      activeMine.stop();
      activeMine = null;
    }
    if (activeCollect != null) {
      activeCollect.stop();
      activeCollect = null;
    }
    pendingDropCollectIds.clear();
    if (prepare != null) {
      prepare.stop();
      prepare = null;
    }
    PlayerControls.release();
  }

  @Override
  void stop() {
    stopChildren();
    super.stop();
  }
}
