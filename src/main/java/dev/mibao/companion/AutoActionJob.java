package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;

/**
 * Built-in background auto-clicker & sustained-action engine for cobblestone generators (auto
 * tool-swap), Create hand cranks (with auto-eat), sieves/bonemeal, and mob grinders. Never hijacks
 * OS cursor and respects F4 eco mode & F9 emergency stop.
 */
public final class AutoActionJob extends Actions.Job {
  private final GeneratorPolicy.AutoMode mode;
  private final int targetCount, intervalTicks, maxTicks, minDurability;
  private final boolean untilToolsSpent;
  private String itemFilter;
  private BlockPos targetPos;
  private final Navigator nav = new Navigator();
  private final AimGate aim = new AimGate();
  private InventoryMove refillMove;
  private boolean movingToReach, wasBreaking, miningStarted, eatingFood, inventoryChecked;
  private int completedActions, toolSwitches, waitRegenTicks, lastActionTick, prevSlot = -1, prevUseSlot = -1;
  private final Map<String, Integer> before;
  private BlockState lastTargetState;
  private String lastBrokenId = "";

  public AutoActionJob(
      GeneratorPolicy.AutoMode mode,
      BlockPos targetPos,
      int count,
      int intervalTicks,
      int maxSeconds,
      boolean untilToolsSpent,
      int minDurability,
      String itemFilter,
      Consumer<String> done) {
    super(done);
    this.mode = mode;
    this.targetPos = targetPos == null ? null : targetPos.immutable();
    this.targetCount = Math.max(0, Math.min(2000, count));
    this.intervalTicks = GeneratorPolicy.clampIntervalTicks(intervalTicks);
    this.maxTicks = GeneratorPolicy.clampMaxSeconds(maxSeconds) * 20;
    this.untilToolsSpent = untilToolsSpent;
    this.minDurability = Math.max(0, Math.min(256, minDurability));
    this.itemFilter = itemFilter == null ? "" : itemFilter.trim();
    PlayerEvidence.begin();
    this.before = PlayerEvidence.actual("*");
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  private BlockPos resolveInitialTarget() {
    if (targetPos != null) return targetPos;
    var p = mc().player;
    if (p == null) return null;
    double range = mc().gameMode != null ? mc().gameMode.getPickRange() : 4.5;
    var hit = p.pick(range, 1.0f, false);
    if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
      targetPos = bhr.getBlockPos().immutable();
      return targetPos;
    }
    return null;
  }

  @Override
  void tick() {
    var mc = mc();
    if (mc.player == null || mc.level == null || mc.gameMode == null) {
      finish("失败：[WRONG_CONTEXT] 世界未就绪。");
      return;
    }
    if (mc.screen != null || mc.player.containerMenu != mc.player.inventoryMenu) {
      finishSummary("打开了菜单界面，后台挂机已安全停手");
      return;
    }
    if (HealthRecovery.restricted()) {
      finishSummary("触发低血保护，后台挂机已安全停手");
      return;
    }
    if (++ticks > maxTicks) {
      finishSummary("达到时间上限（" + (maxTicks / 20) + "秒）");
      return;
    }
    if (targetCount > 0 && !untilToolsSpent && completedActions >= targetCount) {
      finishSummary("已达到目标次数 " + completedActions);
      return;
    }
    if (refillMove != null) {
      int r = refillMove.tick();
      if (r < 0) {
        String err = refillMove.error;
        refillMove = null;
        finishSummary("物料或工具补给耗尽（" + err + "）");
        return;
      }
      if (r == 0) return;
      mc.player.getInventory().selected = refillMove.readySlot;
      refillMove = null;
      return;
    }

    switch (mode) {
      case MINE_HOLD -> tickMineHold(mc);
      case ATTACK_CLICK -> tickAttackClick(mc);
      case USE_HOLD -> tickUse(mc, true);
      case USE_CLICK -> tickUse(mc, false);
    }
  }

  private void tickMineHold(Minecraft mc) {
    if (mc.gameMode.getPlayerMode() != GameType.SURVIVAL) {
      finish("失败：[MODE_REQUIRED] 刷石机连挖需要生存模式。");
      return;
    }
    BlockPos pos = resolveInitialTarget();
    if (pos == null || !mc.level.hasChunkAt(pos)) {
      finish("失败：[NO_TARGET] 请先准星对准刷石机方块，或显式提供 x/y/z 坐标。");
      return;
    }
    Actions.status =
        "刷石机连挖 "
            + completedActions
            + (untilToolsSpent ? "（直到耗尽镐子）" : targetCount > 0 ? "/" + targetCount : "")
            + (toolSwitches > 0 ? " · 换镐 " + toolSwitches : "");

    if (movingToReach) {
      if (Reach.hit(pos) != null) {
        nav.stop();
        movingToReach = false;
      } else {
        int r = nav.tick();
        if (r < 0) {
          finish("失败：" + nav.error());
          return;
        }
        if (r > 0) movingToReach = false;
        return;
      }
    } else if (Reach.hit(pos) == null && pos.distSqr(mc.player.blockPosition()) > 12) {
      if (!nav.startForInteraction(pos)) {
        finish("失败：[OUT_OF_REACH] 无法走到目标方块附近：" + nav.error());
        return;
      }
      movingToReach = true;
      return;
    }

    BlockState state = mc.level.getBlockState(pos);
    if (state.isAir() || !state.getFluidState().isEmpty()) {
      if (wasBreaking) {
        completedActions++;
        wasBreaking = false;
        miningStarted = false;
        inventoryChecked = false;
        waitRegenTicks = 0;
        return;
      }
      miningStarted = false;
      if (++waitRegenTicks > 160) {
        finishSummary("目标格超过8秒未刷新出新方块");
      }
      return;
    }
    waitRegenTicks = 0;
    lastTargetState = state;
    lastBrokenId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

    if (state.getDestroySpeed(mc.level, pos) < 0) {
      finishSummary("目标方块不可破坏");
      return;
    }
    if (!isGeneratorSafeNow(mc, pos, state)) {
      finish("失败：[NEIGHBOR_HAZARD] 目标上方有流体/火或玩家不在干燥安全地面。");
      return;
    }
    if (!inventoryChecked && !hasPickupCapacity(mc, state)) {
      finishSummary("背包已满，已安全停机");
      return;
    }
    inventoryChecked = true;

    int chosenSlot = chooseMineToolSlot(mc, state);
    if (chosenSlot < 0) {
      finishSummary("可用镐子已全部用尽或达到耐久保护线");
      return;
    }
    if (chosenSlot >= 9) {
      var stack = mc.player.getInventory().getItem(chosenSlot).copy();
      refillMove = InventoryMove.ensure(s -> ItemStack.isSameItemSameTags(s, stack));
      toolSwitches++;
      miningStarted = false;
      return;
    }
    if (mc.player.getInventory().selected != chosenSlot) {
      mc.player.getInventory().selected = chosenSlot;
      if (prevSlot != chosenSlot && prevSlot >= 0) toolSwitches++;
      miningStarted = false;
    }
    prevSlot = chosenSlot;

    var hit = Reach.hit(pos);
    if (hit == null) {
      if (!nav.startForInteraction(pos)) {
        finish("失败：[TARGET_OCCLUDED] 准星无法命中目标：" + pos.toShortString());
        return;
      }
      movingToReach = true;
      return;
    }
    if (!aim.ready(hit.getLocation())) return;

    if (!miningStarted) {
      mc.gameMode.startDestroyBlock(pos, hit.getDirection());
      miningStarted = true;
    } else {
      mc.gameMode.continueDestroyBlock(pos, hit.getDirection());
    }
    mc.player.swing(InteractionHand.MAIN_HAND);
    wasBreaking = true;
  }

  private void tickAttackClick(Minecraft mc) {
    Actions.status =
        "刷怪塔连点 · 已挥击 "
            + completedActions
            + (targetCount > 0 ? " / " + targetCount : " 次");
    var mobs =
        mc.level.getEntitiesOfClass(
            LivingEntity.class,
            mc.player.getBoundingBox().inflate(3.8),
            e ->
                e != mc.player
                    && e.isAlive()
                    && e instanceof Monster
                    && !(e instanceof WitherBoss)
                    && !(e instanceof EnderDragon)
                    && (!e.hasCustomName() || Store.config.attackNamed));
    mobs.sort(Comparator.comparingDouble(e -> mc.player.distanceToSqr(e)));
    LivingEntity mob = null;
    for (var cand : mobs) {
      if (MeleeOps.canStrike(cand)) {
        mob = cand;
        break;
      }
    }
    if (mob == null) {
      if (!mobs.isEmpty()) Actions.look(MeleeOps.aim(mobs.get(0)));
      return;
    }
    boolean ready =
        intervalTicks <= 0
            ? mc.player.getAttackStrengthScale(0.5f) >= 0.95f
            : (ticks - lastActionTick >= intervalTicks);
    if (!ready) return;
    MeleeOps.strike(mob);
    lastActionTick = ticks;
    completedActions++;
  }

  private void tickUse(Minecraft mc, boolean holdMode) {
    BlockPos pos = resolveInitialTarget();
    if (pos == null || !mc.level.hasChunkAt(pos)) {
      finish("失败：[NO_TARGET] 请先准星对准机械/方块，或显式提供 x/y/z 坐标。");
      return;
    }
    if (holdMode) {
      if (eatingFood) {
        if (!mc.player.isUsingItem() || mc.player.getFoodData().getFoodLevel() >= 18) {
          if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
          PlayerControls.release();
          eatingFood = false;
          if (prevUseSlot >= 0 && prevUseSlot < 9) {
            mc.player.getInventory().selected = prevUseSlot;
            prevUseSlot = -1;
          }
        }
        Actions.status = "手摇挂机中 · 自动进食恢复饱食";
        return;
      }
      if (mc.player.getFoodData().getFoodLevel() <= 12) {
        int foodSlot = findFoodSlot(mc);
        if (foodSlot >= 0) {
          if (prevUseSlot < 0) prevUseSlot = mc.player.getInventory().selected;
          if (foodSlot >= 9) {
            var copy = mc.player.getInventory().getItem(foodSlot).copy();
            refillMove = InventoryMove.ensure(s -> ItemStack.isSameItemSameTags(s, copy));
            return;
          }
          mc.player.getInventory().selected = foodSlot;
          mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
          PlayerControls.holdFood();
          eatingFood = true;
          return;
        }
      }
    }

    if (!holdMode && itemFilter.isBlank() && !mc.player.getMainHandItem().isEmpty()) {
      itemFilter = BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString();
    }
    if (!itemFilter.isBlank() && !itemFilter.equals("*")) {
      if (!Actions.match(mc.player.getMainHandItem(), itemFilter)) {
        refillMove = InventoryMove.ensure(s -> Actions.match(s, itemFilter));
        return;
      }
    }

    if (movingToReach) {
      if (Reach.hit(pos) != null) {
        nav.stop();
        movingToReach = false;
      } else {
        int r = nav.tick();
        if (r < 0) {
          finish("失败：" + nav.error());
          return;
        }
        if (r > 0) movingToReach = false;
        return;
      }
    }
    var hit = Reach.hit(pos);
    if (hit == null) {
      if (!nav.startForInteraction(pos)) {
        finish("失败：[OUT_OF_REACH] 无法接近并交互目标：" + pos.toShortString());
        return;
      }
      movingToReach = true;
      return;
    }
    if (!aim.ready(hit.getLocation())) return;

    int step = holdMode ? 2 : Math.max(1, intervalTicks);
    Actions.status =
        mode.label + " · 已交互 " + completedActions + (targetCount > 0 ? " / " + targetCount : " 次");
    if (ticks - lastActionTick < step) return;
    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
    mc.player.swing(InteractionHand.MAIN_HAND);
    lastActionTick = ticks;
    completedActions++;
  }

  private boolean isGeneratorSafeNow(Minecraft mc, BlockPos pos, BlockState state) {
    boolean aboveBad = false;
    boolean horizFluid = false;
    for (var d : Direction.values()) {
      BlockPos n = pos.relative(d);
      if (!mc.level.hasChunkAt(n)) return false;
      var s = mc.level.getBlockState(n);
      if (!s.getFluidState().isEmpty() || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)) {
        if (d == Direction.UP) {
          aboveBad = true;
        } else if (d == Direction.DOWN) {
          if (s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || !s.getFluidState().is(FluidTags.WATER)) {
            aboveBad = true;
          }
        } else {
          horizFluid = true;
        }
      }
    }
    BlockPos feet = mc.player.blockPosition();
    boolean dry =
        !mc.player.isInLava()
            && !mc.player.isOnFire()
            && mc.level.hasChunkAt(feet)
            && mc.level.getBlockState(feet).getFluidState().isEmpty();
    return GeneratorPolicy.allowFluidNeighbor(
        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
        true,
        true,
        aboveBad,
        horizFluid,
        dry);
  }

  private boolean hasPickupCapacity(Minecraft mc, BlockState state) {
    String guessId = BuiltInRegistries.ITEM.getKey(state.getBlock().asItem()).toString();
    if ("minecraft:stone".equals(guessId)) guessId = "minecraft:cobblestone";
    List<PickupCapacity.Slot> slots = new ArrayList<>();
    for (int i = 0; i < 36; i++) {
      var s = mc.player.getInventory().getItem(i);
      slots.add(new PickupCapacity.Slot(WorldSense.fingerprint(s), s.getCount(), s.getMaxStackSize()));
    }
    return PickupCapacity.room(slots, guessId, 64) > 0;
  }

  private int chooseMineToolSlot(Minecraft mc, BlockState state) {
    int bestSlot = -1;
    float bestScore = untilToolsSpent ? Float.MAX_VALUE : -Float.MAX_VALUE;
    boolean needsTool = state.requiresCorrectToolForDrops();
    for (int i = 0; i < 36; i++) {
      var s = mc.player.getInventory().getItem(i);
      if (s.isEmpty()) continue;
      if (needsTool && !s.isCorrectToolForDrops(state)) continue;
      if (s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= minDurability) continue;
      float speed = s.getDestroySpeed(state);
      if (speed <= 1.0f && needsTool) continue;
      if (!needsTool && speed <= 1.0f) continue;
      if (untilToolsSpent) {
        if (speed < bestScore) {
          bestScore = speed;
          bestSlot = i;
        }
      } else {
        if (speed > bestScore) {
          bestScore = speed;
          bestSlot = i;
        }
      }
    }
    if (bestSlot < 0 && !needsTool && !untilToolsSpent) {
      return mc.player.getInventory().selected;
    }
    return bestSlot;
  }

  private static int findFoodSlot(Minecraft mc) {
    for (int i = 0; i < 36; i++) {
      var s = mc.player.getInventory().getItem(i);
      if (s.isEmpty() || s.getFoodProperties(mc.player) == null) continue;
      String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
      if (ControlPolicy.FOOD.contains(id)) return i;
    }
    return -1;
  }

  private void finishSummary(String reason) {
    int gained =
        PlayerEvidence.revision == 0
            ? 0
            : DeltaProof.gained(before, PlayerEvidence.actual("*"), PlayerEvidence.confirmed("*"));
    var json = new JsonObject();
    json.addProperty("mode", mode.name());
    json.addProperty("completed_actions", completedActions);
    json.addProperty("tool_switches", toolSwitches);
    json.addProperty("inventory_gained", gained);
    if (!lastBrokenId.isBlank()) json.addProperty("last_block", lastBrokenId);
    finish("成功：" + mode.label + " 结束（" + reason + "）：累计 " + completedActions + " 次，换工具 " + toolSwitches + " 次，净增 " + gained + " 件。" + json);
  }

  @Override
  void stop() {
    if (mc().player != null && mc().gameMode != null) {
      mc().gameMode.stopDestroyBlock();
      if (eatingFood && mc().player.isUsingItem()) mc().gameMode.releaseUsingItem(mc().player);
    }
    PlayerControls.release();
    nav.stop();
    if (refillMove != null) refillMove.stop();
    super.stop();
  }

  @Override
  String interrupted() {
    return mode.label + " 已安全停止，累计执行 " + completedActions + " 次。";
  }
}
