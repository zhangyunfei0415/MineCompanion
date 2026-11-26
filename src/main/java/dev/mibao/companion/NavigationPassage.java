package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Only the path search may ignore an operable gate. Movement waits for observed open geometry. */
public final class NavigationPassage {
  private final AimGate aim = new AimGate();
  private BlockPos pending, triggerPos;
  private int wait, attempts;
  private String error = "";

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public String error() {
    return error;
  }

  private static boolean operable(BlockState s) {
    return s.is(BlockTags.WOODEN_DOORS)
        || s.getBlock() instanceof FenceGateBlock
        || s.is(BlockTags.WOODEN_TRAPDOORS);
  }

  private static boolean closed(BlockState s) {
    return (operable(s) || s.is(Blocks.IRON_DOOR))
        && s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
        && !s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);
  }

  private static BlockPos findIronDoorTrigger(BlockPos doorPos, boolean requireReachable) {
    var level = mc().level;
    var player = mc().player;
    if (level == null) return null;
    BlockPos origin = player != null ? player.blockPosition() : doorPos;
    BlockPos best = null;
    double bestDist = Double.MAX_VALUE;
    for (BlockPos p :
        BlockPos.betweenClosed(
            doorPos.offset(-2, -1, -2), doorPos.offset(2, 2, 2))) {
      if (!level.hasChunkAt(p)) continue;
      var st = level.getBlockState(p);
      if (!st.is(BlockTags.BUTTONS) && !st.is(Blocks.LEVER)) continue;
      if (requireReachable && Reach.hit(p) == null) continue;
      double dist = p.distSqr(origin);
      if (dist < bestDist) {
        bestDist = dist;
        best = p.immutable();
      }
    }
    return best;
  }

  public static boolean clear(AABB area, boolean planning) {
    if (mc().level.noCollision(mc().player, area)) return true;
    boolean ignoreGates = planning && Store.config.autoOpenDoors;
    boolean ignoreScaffolding = Store.config.allowClimb;
    if (!ignoreGates && !ignoreScaffolding) return false;
    if (!mc().level.getEntityCollisions(mc().player, area).isEmpty()) return false;
    for (BlockPos at :
        BlockPos.betweenClosed(
            BlockPos.containing(area.minX - 1, area.minY - 1, area.minZ - 1),
            BlockPos.containing(area.maxX + 1, area.maxY + 1, area.maxZ + 1))) {
      if (!mc().level.hasChunkAt(at)) return false;
      var state = mc().level.getBlockState(at);
      if (ClimbRules.isScaffoldingCollisionPassable(
          state.getBlock() instanceof ScaffoldingBlock, ignoreScaffolding)) continue;
      if (ignoreGates
          && (operable(state) || (state.is(Blocks.IRON_DOOR) && findIronDoorTrigger(at, false) != null)))
        continue;
      for (var box :
          state.getCollisionShape(mc().level, at, CollisionContext.of(mc().player)).toAabbs())
        if (box.move(at).intersects(area)) return false;
    }
    return mc().level.getWorldBorder().isWithinBounds(area);
  }

  /** 1 ready, 0 bounded wait, -1 blocked. Does not synthesize server state or remove a block. */
  public int prepare(AABB corridor) {
    if (pending != null) {
      var state = mc().level.getBlockState(pending);
      if (!closed(state)) {
        var proof = PlayerEvidence.block(pending);
        if (PassageProof.open(
            attempts > 0,
            closed(state),
            proof != null && proof.equals(state),
            proof == null || closed(proof))) {
          pending = null;
          triggerPos = null;
          wait = attempts = 0;
          return 0;
        }
        if (++wait > 60) {
          error = "[DOOR_BLOCKED] 未收到开门的服务器方块更新：" + pending.toShortString();
          return -1;
        }
        return 0;
      }
      if (++wait > 60) {
        error = "[DOOR_BLOCKED] 门未按客户端交互打开：" + pending.toShortString() + "；可能被领地/服务器拒绝";
        return -1;
      }
      if (attempts > 0) return 0;
    } else
      for (BlockPos at :
          BlockPos.betweenClosed(
              BlockPos.containing(corridor.minX - 1, corridor.minY - 1, corridor.minZ - 1),
              BlockPos.containing(corridor.maxX + 1, corridor.maxY + 1, corridor.maxZ + 1))) {
        var state = mc().level.getBlockState(at);
        if (!closed(state)) continue;
        boolean intersects =
            state.getCollisionShape(mc().level, at).toAabbs().stream()
                .anyMatch(b -> b.move(at).intersects(corridor));
        if (intersects) {
          if (state.is(BlockTags.WOODEN_TRAPDOORS)
              && at.getY() < Math.floor(corridor.minY + 0.05)
              && mc().level.hasChunkAt(at.below())) {
            var below = mc().level.getBlockState(at.below());
            if (below.getCollisionShape(mc().level, at.below()).isEmpty()
                && !below.is(BlockTags.CLIMBABLE)) {
              continue;
            }
          }
          BlockPos trig = state.is(Blocks.IRON_DOOR) ? findIronDoorTrigger(at, true) : null;
          if (state.is(Blocks.IRON_DOOR) && trig == null) continue;
          pending = at.immutable();
          triggerPos = trig;
          wait = attempts = 0;
          break;
        }
      }
    if (pending == null) return 1;
    if (!Store.config.autoOpenDoors || mc().player.isShiftKeyDown()) {
      error = "[DOOR_BLOCKED] 关闭的门阻挡路线；自动开门关闭或玩家正潜行：" + pending.toShortString();
      return -1;
    }
    BlockPos clickTarget = triggerPos != null ? triggerPos : pending;
    var hit = Reach.hit(clickTarget);
    if (hit == null && mc().player != null) {
      hit = Reach.mineFrom(clickTarget, mc().player.getEyePosition());
    }
    if (hit == null) {
      error = "[DOOR_BLOCKED] 门或开关暂不可交互：" + clickTarget.toShortString();
      return -1;
    }
    if (!aim.ready(hit.getLocation())) return 0;
    PlayerEvidence.forget(pending);
    mc().gameMode.useItemOn(mc().player, InteractionHand.MAIN_HAND, hit);
    attempts++;
    return 0;
  }

  public static String nearby() {
    var p = mc().player;
    int count = 0;
    var examples = new ArrayList<String>();
    for (BlockPos at :
        BlockPos.betweenClosed(
            p.blockPosition().offset(-8, -2, -8), p.blockPosition().offset(8, 2, 8))) {
      if (!mc().level.hasChunkAt(at)) continue;
      var s = mc().level.getBlockState(at);
      if ((s.getBlock() instanceof DoorBlock
              || s.getBlock() instanceof FenceGateBlock
              || s.getBlock() instanceof TrapDoorBlock)
          && s.hasProperty(
              net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
          && !s.getValue(
              net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)) {
        count++;
        if (examples.size() < 4) examples.add(at.toShortString());
      }
    }
    return count == 0 ? "" : "；附近关闭的门/活板门 " + count + " 处 " + examples + "（附近障碍提示，不代表一定在目标路径上）";
  }
}
