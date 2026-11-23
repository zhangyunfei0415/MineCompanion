package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;

/** Surface-aware client walking, bounded search and stable arrival. Never modifies terrain. */
public final class Navigator {
  private List<BlockPos> path = List.of();
  private int index, stuck, ticks, replans, settling;
  private Vec3 last;
  private BlockPos target, requestedTarget;
  private String error = "";
  private boolean baritone, exact, topFace, localOnly, interaction, fallbackTargetUsed, baritoneFallbackUsed;
  private java.util.function.Predicate<BlockPos> allowedCell;
  private GridSearch search;
  private Set<BlockPos> goals = Set.of();
  private AABB pickupBox;
  private boolean tightPickup;

  private record Edge(BlockPos from, BlockPos to) {}

  private final Set<Edge> blocked = new HashSet<>();
  private final Map<BlockPos, Boolean> searchStands = new HashMap<>();
  private SwimRecovery water;
  private int waterRecoveries;
  private boolean ownsInput;
  private final BaritoneNavigation external = new BaritoneNavigation();
  private NavigationPassage passage = new NavigationPassage();
  private JumpCycle jump = new JumpCycle();
  private ArrivalGate arrival = new ArrivalGate();
  private int parkourPhase; // 0=NONE, 1=ALIGN_RUNUP, 2=SPRINT_RUNUP, 3=AIRBORNE
  private int parkourIndex = -1;
  private BlockPos parkourTakeoff, parkourLanding;
  private int parkourAlignTicks;
  private boolean placement;

  private void resetParkour() {
    parkourPhase = 0;
    parkourIndex = -1;
    parkourTakeoff = null;
    parkourLanding = null;
    parkourAlignTicks = 0;
  }
  private Direction requiredFace, placementFace;
  private java.util.function.Predicate<Vec3> placementStance = eye -> true;
  private java.util.function.Predicate<BlockHitResult> placementHit = hit -> true;

  Navigator placementIntent(
      java.util.function.Predicate<Vec3> stance, java.util.function.Predicate<BlockHitResult> hit) {
    placementStance = stance;
    placementHit = hit;
    return this;
  }

  private boolean exactStance;
  private int maxRadius = 3;
  private int scannedCandidates, standCandidates, visibleCandidates, bothCandidates;
  private int visibleOnlyCandidates, blockedCandidates, unloadedCandidates, hazardCandidates;
  private BlockPos nearestCandidate, nearestVisibleCandidate;
  private double nearestCandidateDistance = Double.MAX_VALUE;
  private double nearestVisibleDistance = Double.MAX_VALUE;

  public boolean startForFace(BlockPos goal, Direction face) {
    requiredFace = face;
    waterRecoveries = 0;
    placement = false;
    pickupBox = null;
    exact = false;
    topFace = false;
    localOnly = true;
    return begin(goal, true);
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public String error() {
    return error;
  }

  public BlockPos requestedTarget() {
    return requestedTarget;
  }

  public BlockPos resolvedTarget() {
    return target;
  }

  public boolean usedFallback() {
    return fallbackTargetUsed;
  }

  /** Require arrival exactly at a stance cell; call before starting a navigation. */
  public Navigator exactStance(boolean value) {
    exactStance = value;
    return this;
  }

  /** Stance candidate search radius in blocks, clamped to 1..5. */
  public Navigator maxRadius(int value) {
    maxRadius = Math.max(1, Math.min(5, value));
    return this;
  }

  public String candidateReport() {
    if (scannedCandidates == 0) return "";
    BlockPos nearest = nearestVisibleCandidate != null ? nearestVisibleCandidate : nearestCandidate;
    double distance =
        nearestVisibleCandidate != null ? nearestVisibleDistance : nearestCandidateDistance;
    return ArrivalReceipt.candidatesText(
        ArrivalReceipt.stance(
            maxRadius,
            exactStance,
            scannedCandidates,
            standCandidates,
            visibleCandidates,
            bothCandidates,
            visibleOnlyCandidates,
            blockedCandidates,
            unloadedCandidates,
            hazardCandidates,
            nearest == null ? null : nearest.getX(),
            nearest == null ? null : nearest.getY(),
            nearest == null ? null : nearest.getZ(),
            distance));
  }

  public String diagnostic() {
    return "候选站位 "
        + goals.size()
        + "；重规划 "
        + replans
        + "/3；"
        + (search == null
            ? "路径节点 " + index + "/" + path.size()
            : "搜索节点 " + search.expanded() + "/20000")
        + (pickupBox != null ? "；拾取待背包核验" : "；需稳定落地");
  }

  public boolean start(BlockPos goal) {
    requiredFace = null;
    waterRecoveries = 0;
    placement = false;
    pickupBox = null;
    exact = false;
    topFace = false;
    localOnly = false;
    baritoneFallbackUsed = false;
    return begin(goal, false);
  }

  private boolean fallbackToLocal(BlockPos goal) {
    baritoneFallbackUsed = true;
    external.stop();
    baritone = false;
    localOnly = true;
    return begin(goal, false);
  }

  public boolean startLocal(BlockPos goal) {
    requiredFace = null;
    waterRecoveries = 0;
    placement = false;
    pickupBox = null;
    exact = false;
    topFace = false;
    localOnly = true;
    return begin(goal, false);
  }

  public boolean startExact(BlockPos goal) {
    requiredFace = null;
    waterRecoveries = 0;
    placement = false;
    pickupBox = null;
    exact = true;
    topFace = false;
    localOnly = true;
    return begin(goal, false);
  }

  boolean startFollow(BlockPos goal, java.util.function.Predicate<BlockPos> allowed) {
    allowedCell = allowed;
    strictWalk = false;
    return startExact(goal);
  }

  private boolean strictWalk;

  boolean startKnownWalk(BlockPos goal, java.util.function.Predicate<BlockPos> allowed) {
    allowedCell = allowed;
    strictWalk = true;
    return startExact(goal);
  }

  private boolean allowed(BlockPos p) {
    return allowedCell == null || allowedCell.test(p);
  }

  public boolean startForInteraction(BlockPos goal) {
    requiredFace = null;
    waterRecoveries = 0;
    placement = false;
    pickupBox = null;
    exact = false;
    topFace = false;
    localOnly = true;
    return begin(goal, true);
  }

  public boolean startForPlanting(BlockPos goal) {
    requiredFace = null;
    waterRecoveries = 0;
    placement = false;
    pickupBox = null;
    exact = false;
    topFace = true;
    localOnly = true;
    return begin(goal, true);
  }

  public boolean startForPickup(AABB item) {
    requiredFace = null;
    waterRecoveries = 0;
    placement = false;
    pickupBox = item;
    exact = true;
    topFace = false;
    localOnly = true;
    return begin(BlockPos.containing(item.getCenter()), false);
  }

  private PickupGeometry.Box pickup() {
    var b = pickupBox;
    return new PickupGeometry.Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
  }

  private boolean pickupReached() {
    var p = mc().player;
    return tightPickup
        ? PickupGeometry.preferredCloseTouches(
            p.getX(), p.getY(), p.getZ(), p.getBbWidth(), p.getBbHeight(), pickup())
        : PickupGeometry.touches(
            p.getX(), p.getY(), p.getZ(), p.getBbWidth(), p.getBbHeight(), pickup());
  }

  public boolean startForPlacement(BlockPos goal) {
    return startForPlacement(goal, null);
  }

  public boolean startForPlacement(BlockPos goal, Direction face) {
    placementFace = face;
    requiredFace = null;
    waterRecoveries = 0;
    pickupBox = null;
    exact = false;
    topFace = false;
    localOnly = true;
    placement = true;
    return begin(goal, true);
  }

  private boolean visible(BlockPos goal, Vec3 eye) {
    if (placement && !placementStance.test(eye)) return false;
    return (requiredFace != null
            ? Reach.faceFrom(goal, eye, requiredFace)
            : placement
                ? Reach.placementFrom(goal, eye, placementFace, placementHit)
                : topFace ? Reach.topFrom(goal, eye) : Reach.hitFrom(goal, eye))
        != null;
  }

  /** Counts one stance candidate: standability, line of sight and the reason it was rejected. */
  private void scanCandidate(BlockPos goal, BlockPos at, Set<BlockPos> options) {
    var player = mc().player;
    scannedCandidates++;
    boolean unloaded = !mc().level.hasChunkAt(at);
    boolean standable = !unloaded && stand(at);
    boolean seen = false;
    if (!unloaded) seen = visible(goal, foot(at).add(0, player.getEyeHeight(), 0));
    if (standable) {
      standCandidates++;
      if (seen) visibleCandidates++;
      double dx = foot(at).x - player.getX(), dz = foot(at).z - player.getZ();
      double distance = Math.sqrt(dx * dx + dz * dz);
      if (distance < nearestCandidateDistance) {
        nearestCandidateDistance = distance;
        nearestCandidate = at;
      }
      if (seen) {
        bothCandidates++;
        if (distance < nearestVisibleDistance) {
          nearestVisibleDistance = distance;
          nearestVisibleCandidate = at;
        }
        options.add(at);
      }
    } else if (seen) visibleOnlyCandidates++;
    else if (unloaded) unloadedCandidates++;
    else if (hazard(at) || hazard(at.below())) hazardCandidates++;
    else blockedCandidates++;
  }

  private boolean begin(BlockPos goal, boolean use) {
    stop();
    goals = Set.of();
    search = null;
    error = "";
    requestedTarget = goal;
    target = goal;
    fallbackTargetUsed = false;
    interaction = use;
    ticks = stuck = replans = settling = 0;
    scannedCandidates = standCandidates = visibleCandidates = bothCandidates = 0;
    visibleOnlyCandidates = blockedCandidates = unloadedCandidates = hazardCandidates = 0;
    nearestCandidate = nearestVisibleCandidate = null;
    nearestCandidateDistance = nearestVisibleDistance = Double.MAX_VALUE;
    blocked.clear();
    searchStands.clear();
    path = List.of();
    index = 0;
    jump = new JumpCycle();
    arrival = new ArrivalGate();
    passage = new NavigationPassage();
    resetParkour();
    if (mc().screen != null || mc().player.containerMenu != mc().player.inventoryMenu) {
      if (!(mc().screen
              instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)
          || !InventorySession.canClose()) {
        error = "移动前需收好鼠标/合成栏物品并正常关闭界面";
        return false;
      }
      mc().player.closeContainer();
    }
    if (!interaction
        && !exact
        && !localOnly
        && (!mc().level.hasChunkAt(goal) || mc().player.blockPosition().distSqr(goal) > 24 * 24)
        && external.start(goal)) {
      baritone = true;
      last = mc().player.position();
      return true;
    }
    if (!mc().level.hasChunkAt(goal)) {
      error = "目标区块未加载，仍属未知";
      return false;
    }
    if (mc().player.blockPosition().distSqr(goal) > 96 * 96) {
      error = "[TARGET_TOO_FAR] 内置导航目标超过96格，先设置中间点";
      return false;
    }
    if (isDeepWaterStart(mc().player)) {
      if (++waterRecoveries > 2) {
        error = "[WATER_ESCAPE_FAILED] 本次导航两次脱水恢复预算已用完";
        return false;
      }
      water = new SwimRecovery(120);
      return true;
    }
    BlockPos start = standingCell();
    if (interaction && !exactStance && grounded() && visible(goal, mc().player.getEyePosition())) {
      goals = Set.of(start);
      last = mc().player.position();
      return true;
    }
    if (!stand(start)) {
      error =
          "[INVALID_START_STANCE] 起点附近没有与玩家AABB足迹匹配的安全支撑格；玩家=("
              + Math.round(mc().player.getX() * 100) / 100.0
              + ","
              + Math.round(mc().player.getY() * 100) / 100.0
              + ","
              + Math.round(mc().player.getZ() * 100) / 100.0
              + ")，主要支撑格="
              + start.toShortString()
              + "；水中可用swim_out，其他地形用inspect_client核查";
      return false;
    }
    if (start.distSqr(goal) > 96 * 96) {
      error = "内置寻路单次限制96格，请设置中间点";
      return false;
    }
    var player = mc().player;
    tightPickup = false;
    Set<BlockPos> options = new HashSet<>();
    if (pickupBox != null) {
      var allStances =
          PickupGeometry.stances(
              pickup(),
              player.getBbWidth(),
              player.getBbHeight(),
              c -> surfaceY(pos(c)),
              c -> stand(pos(c)));
      var tight =
          allStances.stream()
              .filter(
                  c -> {
                    double feetY = surfaceY(pos(c));
                    return Double.isFinite(feetY)
                        && PickupGeometry.preferredCloseTouches(
                            c.x() + .5,
                            feetY,
                            c.z() + .5,
                            player.getBbWidth(),
                            player.getBbHeight(),
                            pickup());
                  })
              .map(Navigator::pos)
              .toList();
      if (!tight.isEmpty()) {
        tightPickup = true;
        options.addAll(tight);
      } else {
        options.addAll(allStances.stream().map(Navigator::pos).toList());
      }
      if (pickupReached() && stand(start)) options.add(start);
    } else if (interaction) {
      if (stand(start) && visible(goal, player.getEyePosition())) options.add(start);
      if (options.isEmpty())
        for (int dx = -maxRadius; dx <= maxRadius; dx++)
          for (int dz = -maxRadius; dz <= maxRadius; dz++)
            for (int dy = -2; dy <= 3; dy++) scanCandidate(goal, goal.offset(dx, dy, dz), options);
    } else {
      if (stand(start)
          && ArrivalGate.near(
              player.getX(),
              player.getY(),
              player.getZ(),
              goal.getX() + .5,
              exact ? surfaceY(goal) : goal.getY(),
              goal.getZ() + .5,
              exact)) options.add(start);
      if (options.isEmpty())
        for (int dx = -2; dx <= 2; dx++)
          for (int dz = -2; dz <= 2; dz++)
            for (int dy = -1; dy <= 1; dy++) {
              BlockPos at = goal.offset(dx, dy, dz);
              if (exact && !at.equals(goal)) continue;
              Vec3 v = foot(at);
              if (allowed(at) && stand(at)
                  && ArrivalGate.candidate(
                      v.x,
                      v.y,
                      v.z,
                      goal.getX() + .5,
                      exact ? surfaceY(goal) : goal.getY(),
                      goal.getZ() + .5,
                      exact)) options.add(at);
            }
      if (options.isEmpty() && !exact) {
        BlockPos fallbackBest = collectFallbackGoals(goal, start, options);
        if (fallbackBest != null) {
          fallbackTargetUsed = true;
          target = fallbackBest;
        }
      }
    }
    goals = Set.copyOf(options);
    if (goals.isEmpty()) {
      String why =
          pickupBox != null
              ? "没有可站立且位于拾取范围的候选：请检查上盖、地面高差或碰撞；不自动拆障碍"
              : interaction ? "没有看得见目标的安全交互站位" : "目标高度附近没有安全站位，不能把隔层靠近视为到达";
      String candidates = candidateReport();
      error = why + (candidates.isEmpty() ? "" : "；" + candidates);
      return false;
    }
    search = createSearch(start);
    last = player.position();
    return true;
  }

  public int tick() {
    var p = mc().player;
    if (p == null) return -1;
    ownsInput = true;
    if (baritone && external.consumeCancelled()) {
      // Baritone was cancelled by pause/takeover/self-defense: re-plan instead of NO_PATH.
      baritone = false;
      return begin(requestedTarget != null ? requestedTarget : target, interaction) ? 0 : -1;
    }
    if (water != null) {
      Actions.status = "导航恢复 · 上浮并寻找近岸安全落脚点";
      int r = water.tick();
      if (r < 0) return fail(water.error());
      if (r == 0) return 0;
      water = null;
      return begin(target, interaction) ? 0 : -1;
    }
    if (isDeepWaterStart(p) && !baritone) {
      if (++waterRecoveries > 2) return fail("[WATER_ESCAPE_FAILED] 重复落水达到恢复上限");
      water = new SwimRecovery(120);
      return 0;
    }
    if (++ticks > (baritone ? 12000 : 2400)) return fail("[NAVIGATION_TIMEOUT] 导航时间预算耗尽");
    double arrivalY = (exact || fallbackTargetUsed) ? surfaceY(target) : target.getY();
    if (!Double.isFinite(arrivalY)) arrivalY = target.getY();
    if (baritone
        && !exact
        && Math.abs(p.getX() - target.getX() - .5) <= 2
        && Math.abs(p.getZ() - target.getZ() - .5) <= 2) {
      BlockPos surface = ColumnProbe.nearest(target.getX(), target.getZ(), target.getY(), 3);
      if (surface != null && Math.abs(foot(surface).y - target.getY()) <= .65)
        arrivalY = foot(surface).y;
    }
    boolean range =
        pickupBox != null
            ? pickupReached()
            : interaction
                ? visible(target, p.getEyePosition()) && (!exactStance || atExactStance())
                : ArrivalGate.near(
                    p.getX(),
                    p.getY(),
                    p.getZ(),
                    target.getX() + .5,
                    arrivalY,
                    target.getZ() + .5,
                    exact);
    boolean safelyStanding = grounded();
    if (arrival.step(range, safelyStanding, p.getDeltaMovement().y)) {
      stop();
      return 1;
    }
    if (range && safelyStanding) {
      PlayerControls.motionState(false, false);
      return 0;
    }
    if (search != null) {
      release();
      GridSearch.State result = search.advance(192, 2_000_000);
      if (result == GridSearch.State.SEARCHING) return 0;
      if (result != GridSearch.State.FOUND) {
        if (!exact && !interaction && pickupBox == null && !fallbackTargetUsed && requestedTarget != null) {
          Set<BlockPos> expanded = new HashSet<>();
          BlockPos fallbackBest = collectFallbackGoals(requestedTarget, standingCell(), expanded);
          if (fallbackBest != null && !expanded.equals(goals)) {
            fallbackTargetUsed = true;
            target = fallbackBest;
            goals = Set.copyOf(expanded);
            search = createSearch(standingCell());
            return 0;
          }
        }
        return fail("搜索预算内未找到安全路线（展开 " + search.expanded() + "/20000；候选站位 " + goals.size() + "）");
      }
      path = search.path().stream().map(Navigator::pos).toList();
      if (path.isEmpty()) path = List.of(standingCell());
      if (fallbackTargetUsed && !path.isEmpty()) target = path.get(path.size() - 1);
      index = 0;
      resetParkour();
      search = null;
      stuck = 0;
      last = p.position();
    }
    if (p.position().distanceToSqr(last) < .015) stuck++;
    else {
      stuck = 0;
      last = p.position();
    }
    if (stuck > 40 && !baritone) {
      if (!replan(standingCell())) return -1;
      return 0;
    }
    if (stuck > 100 && !baritone) return fail("路线受阻或尚未到达目标高度，请检查地形");
    if (baritone) {
      if ((stuck > 100 || (ticks > 40 && !external.active()))
          && !baritoneFallbackUsed
          && mc().level.hasChunkAt(target)
          && p.blockPosition().distSqr(target) <= 96 * 96) {
        return fallbackToLocal(requestedTarget != null ? requestedTarget : target) ? 0 : -1;
      }
      if (stuck > 200 || (ticks > 100 && !external.active()))
        return fail("[NO_PATH] Baritone 已停止或连续十秒没有位移；请观察路径与目标");
      return 0;
    }
    boolean groundedForWaypoint =
        p.onGround()
            || (Store.config.allowClimb && p.onClimbable())
            || (Store.config.wadeWater && (p.isInWater() || isWater(p.blockPosition())));
    while (index < path.size()
        && ArrivalGate.advanceWaypoint(
            p.distanceToSqr(foot(path.get(index))),
            groundedForWaypoint,
            index == path.size() - 1,
            range))
      index++;
    if (index >= path.size()) {
      if (range) {
        if (++settling > 40) return fail("已接近但未在安全站位稳定落地");
        PlayerControls.motionState(false, false);
        return 0;
      }
      if (!replan(standingCell())) return -1;
      return 0;
    }
    // Skip only collinear level cells; never skip a rise, corner, water, climb or parkour step.
    while (index + 1 < path.size()) {
      var a = path.get(index);
      var b = path.get(index + 1);
      if (a.distManhattan(b) > 1
          || isWater(a)
          || isWater(b)
          || isClimbable(a)
          || isClimbable(b)) break;
      var av = foot(a);
      var bv = foot(b);
      var dv = bv.subtract(p.position());
      if (Math.abs(av.y - bv.y) > .01
          || Math.abs(bv.y - p.getY()) > .15
          || dv.horizontalDistanceSqr() > 9) break;
      var axis = b.subtract(a);
      boolean aligned =
          axis.getX() == 0 ? Math.abs(p.getX() - av.x) < .14 : Math.abs(p.getZ() - av.z) < .14;
      if (!aligned || !allowed(b) || !stand(b) || !traversable(a, b)) break;
      index++;
    }
    if (parkourPhase != 0 && index != parkourIndex) resetParkour();
    BlockPos next = path.get(index);
    BlockPos prevCell = index > 0 ? path.get(index - 1) : standingCell();
    BlockPos takeoffCell =
        (parkourIndex == index && parkourTakeoff != null) ? parkourTakeoff : prevCell;
    if (!allowed(next)) return fail("[FOLLOW_ROUTE_CHANGED] 前方已超出允许的跟随通路");
    Vec3 destination = foot(next);
    Vec3 delta = destination.subtract(p.position());
    if (index == path.size() - 1 && safelyStanding && !range && delta.lengthSqr() < .0036) {
      var remaining = new HashSet<>(goals);
      remaining.remove(next);
      goals = Set.copyOf(remaining);
      if (goals.isEmpty()) return fail("已到候选中心但实际到达/交互/拾取条件不满足；候选已排除，不重复空路径");
      if (!replan(standingCell())) return -1;
      return 0;
    }
    int horizGap =
        Math.max(
                Math.abs(next.getX() - takeoffCell.getX()),
                Math.abs(next.getZ() - takeoffCell.getZ()))
            - 1;
    boolean verticalClimb =
        Store.config.allowClimb
            && next.getX() == prevCell.getX()
            && next.getZ() == prevCell.getZ()
            && next.getY() != prevCell.getY()
            && (p.onClimbable() || isClimbable(prevCell) || isClimbable(next));
    if (verticalClimb) {
      BlockPos ladderPos =
          isClimbable(p.blockPosition())
              ? p.blockPosition()
              : (isClimbable(prevCell) ? prevCell : next);
      var climbState = mc().level.getBlockState(ladderPos);
      float desiredYaw;
      if (climbState.hasProperty(net.minecraft.world.level.block.LadderBlock.FACING)) {
        desiredYaw =
            climbState
                .getValue(net.minecraft.world.level.block.LadderBlock.FACING)
                .getOpposite()
                .toYRot();
      } else if (delta.horizontalDistanceSqr() < 0.04) {
        desiredYaw = p.getYRot();
      } else {
        desiredYaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
      }
      var steering = MovementSteering.toward(p.getYRot(), desiredYaw);
      p.setYRot(steering.yaw());
      p.setXRot(next.getY() > p.getY() ? -25f : 25f);
      if (next.getY() > p.getY() - 0.1) {
        PlayerControls.motionState(true, true, false, false);
      } else {
        PlayerControls.motionState(delta.horizontalDistanceSqr() > 0.09, false, false, false);
      }
      return 0;
    }
    double landingDistSq =
        (p.getX() - destination.x) * (p.getX() - destination.x)
            + (p.getZ() - destination.z) * (p.getZ() - destination.z);
    boolean alreadyCrossedToLanding =
        p.onGround()
            && Math.abs(p.getY() - destination.y) < 0.65
            && (standingCell().equals(next) || landingDistSq < 0.85);
    if (horizGap >= 1 && !strictWalk) {
      if (alreadyCrossedToLanding) {
        resetParkour();
      } else {
        if (parkourPhase == 3 && p.onGround()) {
          resetParkour();
          PlayerControls.motionState(false, false, false, false);
          return 0;
        }
        double ux = Integer.signum(next.getX() - takeoffCell.getX());
        double uz = Integer.signum(next.getZ() - takeoffCell.getZ());
        double tx = takeoffCell.getX() + .5, tz = takeoffCell.getZ() + .5;
        double along = (p.getX() - tx) * ux + (p.getZ() - tz) * uz;
        double distToEdge = 0.5 - along;
        double horizSpeed = Math.hypot(p.getDeltaMovement().x, p.getDeltaMovement().z);
        if (parkourPhase == 0) {
          parkourIndex = index;
          parkourTakeoff = takeoffCell;
          parkourLanding = next;
          parkourAlignTicks = 0;
          parkourPhase = (horizGap >= 2 && along > -0.15 && horizSpeed < 0.15) ? 1 : 2;
        }
        Vec3 targetAim =
            parkourLanding != null ? foot(parkourLanding).subtract(p.position()) : delta;
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-targetAim.x, targetAim.z));
        var steering = MovementSteering.toward(p.getYRot(), desiredYaw);
        p.setYRot(steering.yaw());
        p.setXRot(p.getXRot() + (8 - p.getXRot()) * .35f);
        if (!p.onGround()) {
          parkourPhase = 3;
          PlayerControls.motionState(steering.forward(), false, horizGap >= 2, false);
          return 0;
        }
        if (parkourPhase == 1) {
          double runupDist = horizGap >= 3 ? 1.55 : 0.95;
          if (along <= -(runupDist - 0.25) || ++parkourAlignTicks > 25) {
            parkourPhase = 2;
          } else {
            Vec3 alignTarget = new Vec3(tx - ux * runupDist, p.getY(), tz - uz * runupDist);
            Vec3 rv = alignTarget.subtract(p.position());
            var backSteer =
                MovementSteering.toward(p.getYRot(), (float) Math.toDegrees(Math.atan2(-rv.x, rv.z)));
            p.setYRot(backSteer.yaw());
            PlayerControls.motionState(backSteer.forward(), false, false, false);
            return 0;
          }
        }
        if (horizGap >= 2 && distToEdge < 0.08 && horizSpeed < 0.14) {
          parkourPhase = 1;
          parkourAlignTicks = 0;
          PlayerControls.motionState(false, false, false, true);
          return 0;
        }
        boolean doJump =
            MovementPolicy.takeoffReady(distToEdge, horizSpeed, horizGap)
                || (horizGap == 1 && distToEdge <= 0.14);
        boolean sprint =
            MovementPolicy.shouldSprint(
                true,
                path.size() - index,
                targetAim.length(),
                p.getFoodData().getFoodLevel(),
                steering.forward(),
                false,
                false,
                false,
                false,
                true);
        PlayerControls.motionState(steering.forward(), doJump, sprint, false);
        return 0;
      }
    }
    var corridor = body(p.position()).minmax(body(destination));
    int gate = strictWalk ? 1 : passage.prepare(corridor);
    if (gate < 0) return fail(passage.error());
    if (gate == 0) {
      release();
      stuck = 0;
      last = p.position();
      return 0;
    }
    if (!stand(next)) {
      if (!replan(standingCell())) return -1;
      return 0;
    }
    float desiredYaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
    var steering = MovementSteering.toward(p.getYRot(), desiredYaw);
    p.setYRot(steering.yaw());
    p.setXRot(p.getXRot() + (10 - p.getXRot()) * .35f);
    boolean stepUp =
        destination.y > p.getY() + .35
            && destination.y <= p.getY() + 1.1
            && delta.horizontalDistanceSqr() < 1.85;
    boolean inShallowWater = isWater(p.blockPosition()) || isWater(next) || p.isInWater();
    boolean upcomingCorner = false;
    if (index + 1 < path.size()) {
      BlockPos after = path.get(index + 1);
      int dx1 = next.getX() - prevCell.getX(), dz1 = next.getZ() - prevCell.getZ();
      int dx2 = after.getX() - next.getX(), dz2 = after.getZ() - next.getZ();
      upcomingCorner = (dx1 * dx2 + dz1 * dz2) <= 0;
    }
    float yawRem = MovementSteering.remainingYaw(p.getYRot(), desiredYaw);
    boolean sprint =
        MovementPolicy.shouldSprint(
            Store.config.followSprint,
            path.size() - index,
            target != null ? p.position().distanceTo(foot(target)) : delta.length(),
            p.getFoodData().getFoodLevel(),
            steering.forward(),
            yawRem > 35f || upcomingCorner,
            stepUp,
            inShallowWater,
            p.onClimbable(),
            false);
    boolean climbExitJump =
        Store.config.allowClimb && p.onClimbable() && destination.y > p.getY() + 0.05;
    boolean waterShoreJump =
        inShallowWater && p.isInWater() && destination.y > p.getY() + 0.15;
    boolean jumpBit =
        (climbExitJump || waterShoreJump)
            ? steering.forward()
            : jump.step(
                p.onGround(),
                (stepUp || (inShallowWater && destination.y >= p.getY() + .2)) && steering.forward(),
                ticks);
    PlayerControls.motionState(steering.forward(), jumpBit, sprint, false);
    return 0;
  }

  private int fail(String why) {
    String candidates = candidateReport();
    error = why + (candidates.isEmpty() ? "" : "；" + candidates) + NavigationPassage.nearby();
    stop();
    return -1;
  }

  /** True when the player is exactly on one of the chosen stance cells. */
  private boolean atExactStance() {
    var player = mc().player;
    if (player == null) return false;
    for (BlockPos cell : goals) {
      Vec3 feet = foot(cell);
      if (ArrivalGate.near(
          player.getX(), player.getY(), player.getZ(), feet.x, feet.y, feet.z, true)) return true;
    }
    return false;
  }

  public void stop() {
    boolean owned = ownsInput;
    ownsInput = false;
    resetParkour();
    if (water != null) {
      water.stop();
      water = null;
    }
    if (owned) release();
    if (search != null) {
      search.cancel();
      search = null;
    }
    if (baritone) external.stop();
    baritone = false;
  }

  public static void release() {
    PlayerControls.release();
  }

  static boolean lethalHazard(BlockPos p) {
    if (!mc().level.hasChunkAt(p)) return true;
    var s = mc().level.getBlockState(p);
    var fluid = s.getFluidState();
    boolean badFluid = !fluid.isEmpty() && !fluid.is(net.minecraft.tags.FluidTags.WATER);
    return badFluid
        || s.is(Blocks.FIRE)
        || s.is(Blocks.SOUL_FIRE)
        || s.is(Blocks.SWEET_BERRY_BUSH)
        || s.is(Blocks.POWDER_SNOW)
        || s.is(Blocks.MAGMA_BLOCK)
        || s.is(Blocks.CACTUS)
        || s.is(Blocks.CAMPFIRE)
        || s.is(Blocks.SOUL_CAMPFIRE);
  }

  static boolean isLavaOrFire(BlockPos p) {
    if (mc().level == null || !mc().level.hasChunkAt(p)) return true;
    var s = mc().level.getBlockState(p);
    return s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
        || s.is(Blocks.FIRE)
        || s.is(Blocks.SOUL_FIRE)
        || s.is(Blocks.MAGMA_BLOCK)
        || s.is(Blocks.CAMPFIRE)
        || s.is(Blocks.SOUL_CAMPFIRE);
  }

  /** Rejects cramped cells bordering lava or fire during unstick / roam detours. */
  static boolean isNarrowHazardAdjacent(BlockPos cell) {
    if (mc().level == null || !mc().level.hasChunkAt(cell)) return true;
    if (isLavaOrFire(cell) || isLavaOrFire(cell.above()) || isLavaOrFire(cell.below())) return true;
    boolean nearLavaFire = false;
    int safeExits = 0;
    for (var dir : Direction.Plane.HORIZONTAL) {
      BlockPos side = cell.relative(dir);
      if (isLavaOrFire(side) || isLavaOrFire(side.above()) || isLavaOrFire(side.below())) {
        nearLavaFire = true;
      } else if (stand(side) || stand(side.above()) || stand(side.below())) {
        safeExits++;
      }
    }
    return nearLavaFire && safeExits <= 1;
  }

  private static boolean isWater(BlockPos p) {
    return mc().level != null
        && mc().level.hasChunkAt(p)
        && mc().level.getBlockState(p).getFluidState().is(net.minecraft.tags.FluidTags.WATER);
  }

  private static boolean isClimbable(BlockPos p) {
    return mc().level != null
        && mc().level.hasChunkAt(p)
        && mc().level.getBlockState(p).is(net.minecraft.tags.BlockTags.CLIMBABLE);
  }

  private static boolean shallowWaterStandable(BlockPos cell) {
    if (!Store.config.wadeWater || mc().level == null || !mc().level.hasChunkAt(cell)) return false;
    if (!isWater(cell) || isWater(cell.above()) || isWater(cell.below())) return false;
    if (lethalHazard(cell.below()) || lethalHazard(cell) || lethalHazard(cell.above())) return false;
    return Double.isFinite(surfaceY(cell));
  }

  private static boolean isDeepWaterStart(net.minecraft.client.player.LocalPlayer p) {
    if (p == null || !p.isInWater()) return false;
    return !shallowWaterStandable(p.blockPosition()) && !shallowWaterStandable(standingCell());
  }

  private static boolean hazard(BlockPos p) {
    return lethalHazard(p) || (isWater(p) && !Store.config.wadeWater);
  }

  static double surfaceY(BlockPos cell) {
    if (!mc().level.hasChunkAt(cell) || !mc().level.hasChunkAt(cell.below())) return Double.NaN;
    var shape = mc().level.getBlockState(cell.below()).getCollisionShape(mc().level, cell.below());
    if (shape.isEmpty()) return Double.NaN;
    double top =
        shape.toAabbs().stream()
            .filter(b -> b.minX <= .5 && b.maxX >= .5 && b.minZ <= .5 && b.maxZ >= .5)
            .mapToDouble(b -> b.maxY)
            .max()
            .orElse(Double.NaN);
    return top > 0 && top <= 1.501 ? cell.getY() - 1 + top : Double.NaN;
  }

  static Vec3 foot(BlockPos cell) {
    double y = surfaceY(cell);
    return new Vec3(cell.getX() + .5, Double.isFinite(y) ? y : cell.getY(), cell.getZ() + .5);
  }

  private static AABB body(Vec3 feet) {
    var p = mc().player;
    double half = p.getBbWidth() / 2;
    return new AABB(
        feet.x - half,
        feet.y + 1e-5,
        feet.z - half,
        feet.x + half,
        feet.y + p.getBbHeight(),
        feet.z + half);
  }

  static boolean stand(BlockPos cell) {
    return stand(cell, false);
  }

  private static boolean standPath(BlockPos cell) {
    return stand(cell, true);
  }

  private static boolean stand(BlockPos cell, boolean planning) {
    if (mc().player == null || mc().level == null || !mc().level.hasChunkAt(cell)) return false;
    if (Store.config.allowClimb && isClimbable(cell)) {
      if (lethalHazard(cell) || lethalHazard(cell.above())) return false;
      return NavigationPassage.clear(
          body(new Vec3(cell.getX() + .5, cell.getY(), cell.getZ() + .5)), planning);
    }
    if (shallowWaterStandable(cell)) {
      return NavigationPassage.clear(body(foot(cell)), planning);
    }
    double y = surfaceY(cell);
    if (!Double.isFinite(y)) return false;
    if (isWater(cell) || isWater(cell.above())) return false;
    if (lethalHazard(cell.below())) return false;
    int bodyTop = (int) Math.floor(y + mc().player.getBbHeight());
    for (int by = (int) Math.floor(y); by <= bodyTop; by++)
      if (lethalHazard(new BlockPos(cell.getX(), by, cell.getZ()))) return false;
    return NavigationPassage.clear(body(foot(cell)), planning);
  }

  public static boolean grounded() {
    var p = mc().player;
    if (p == null) return false;
    if (Store.config.allowClimb && p.onClimbable()) return true;
    if (!p.onGround()) return false;
    var box = p.getBoundingBox();
    if (!mc().level.noCollision(p, box.deflate(1e-5))) return false;
    var below = new AABB(box.minX, box.minY - .08, box.minZ, box.maxX, box.minY + .001, box.maxZ);
    for (var shape : mc().level.getBlockCollisions(p, below)) if (!shape.isEmpty()) return true;
    return false;
  }

  /**
   * Support cell under the player's actual AABB footprint. Edge-standing (for example on a furnace
   * rim while blockPosition() points at air) still needs a valid start stance.
   */
  static BlockPos standingCell() {
    var player = mc().player;
    if (player == null) return BlockPos.ZERO;
    if (Store.config.allowClimb && player.onClimbable() && stand(player.blockPosition()))
      return player.blockPosition();
    var box = player.getBoundingBox();
    int feetY = (int) Math.floor(box.minY);
    int minX = (int) Math.floor(box.minX + 1.0E-4), maxX = (int) Math.floor(box.maxX - 1.0E-4);
    int minZ = (int) Math.floor(box.minZ + 1.0E-4), maxZ = (int) Math.floor(box.maxZ - 1.0E-4);
    for (int offsetY : new int[] {0, -1, 1, -2}) {
      int y = feetY + offsetY;
      BlockPos best = null;
      double bestDistance = Double.MAX_VALUE;
      for (int x = minX; x <= maxX; x++)
        for (int z = minZ; z <= maxZ; z++) {
          BlockPos cell = new BlockPos(x, y, z);
          double top = surfaceY(cell);
          if (!Double.isFinite(top) || Math.abs(top - box.minY) > .2) continue;
          if (!stand(cell)) continue;
          double dx = cell.getX() + .5 - player.getX(), dz = cell.getZ() + .5 - player.getZ();
          double distance = dx * dx + dz * dz;
          if (distance < bestDistance) {
            bestDistance = distance;
            best = cell;
          }
        }
      if (best != null) return best;
    }
    BlockPos base = player.blockPosition();
    if (stand(base) && Math.abs(surfaceY(base) - player.getY()) < .2) return base;
    return stand(base.above()) ? base.above() : base;
  }

  private boolean traversable(BlockPos from, BlockPos to) {
    return traversable(from, to, !strictWalk);
  }

  static boolean openMiningStep(BlockPos from, BlockPos to) {
    return stand(from) && stand(to) && traversable(from, to, false);
  }

  static boolean openExplorationStep(BlockPos from, BlockPos to) {
    return standPath(from) && standPath(to) && traversable(from, to, Store.config.autoOpenDoors);
  }

  private static boolean traversable(BlockPos from, BlockPos to, boolean planning) {
    Vec3 a = foot(from), b = foot(to);
    if (!ArrivalGate.transition(a.y, b.y)) return false;
    double high = Math.max(a.y, b.y);
    for (int i = 0; i <= 4; i++) {
      double t = i / 4.0;
      Vec3 v = new Vec3(a.x + (b.x - a.x) * t, high, a.z + (b.z - a.z) * t);
      if (!NavigationPassage.clear(body(v), planning)) return false;
    }
    return true;
  }

  private boolean replan(BlockPos start) {
    release();
    resetParkour();
    if (++replans > 3) {
      fail("局部重规划三次仍受阻；" + diagnostic());
      return false;
    }
    if (index < path.size()) blocked.add(new Edge(start, path.get(index)));
    searchStands.clear();
    search = createSearch(start);
    stuck = settling = 0;
    last = mc().player.position();
    return true;
  }

  private GridSearch createSearch(BlockPos start) {
    int food = mc().player == null ? 20 : mc().player.getFoodData().getFoodLevel();
    String pkMode = strictWalk ? "OFF" : MovementPolicy.normalizeParkourMode(Store.config.parkourMode);
    return GridSearch.weighted(
        cell(start),
        c -> goals.contains(pos(c)),
        c -> {
          BlockPos p = pos(c);
          List<GridSearch.Step> next = new ArrayList<>();
          for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
            for (int dy : new int[] {0, 1, -1, 2, -2}) {
              BlockPos q = p.offset(d[0], dy, d[1]);
              if (q.distSqr(start) > 96 * 96 || blocked.contains(new Edge(p, q)) || !allowed(q)) continue;
              if (searchStands.size() > 12000) searchStands.clear();
              if (searchStands.computeIfAbsent(q, Navigator::standPath) && traversable(p, q)) {
                double cost =
                    MovementPolicy.stepCost(
                        isWater(q) ? MovementPolicy.MoveKind.WADE_WATER : MovementPolicy.MoveKind.WALK);
                next.add(new GridSearch.Step(cell(q), cost));
              }
            }
          if (Store.config.allowClimb) {
            BlockPos up = p.above();
            if (up.distSqr(start) <= 96 * 96
                && allowed(up)
                && !blocked.contains(new Edge(p, up))
                && (isClimbable(p) || isClimbable(up))
                && searchStands.computeIfAbsent(up, Navigator::standPath)) {
              next.add(new GridSearch.Step(cell(up), MovementPolicy.stepCost(MovementPolicy.MoveKind.CLIMB_UP)));
            }
            BlockPos down = p.below();
            if (down.distSqr(start) <= 96 * 96
                && allowed(down)
                && !blocked.contains(new Edge(p, down))
                && isClimbable(down)
                && searchStands.computeIfAbsent(down, Navigator::standPath)) {
              next.add(new GridSearch.Step(cell(down), MovementPolicy.stepCost(MovementPolicy.MoveKind.CLIMB_DOWN)));
            }
          }
          if (!"OFF".equals(pkMode) && !isWater(p) && !isClimbable(p) && Double.isFinite(surfaceY(p))) {
            addParkourSteps(start, p, pkMode, food, next);
          }
          return next;
        },
        c -> {
          BlockPos p = pos(c);
          return Math.sqrt(goals.stream().mapToDouble(p::distSqr).min().orElse(0));
        },
        20000);
  }

  private void addParkourSteps(
      BlockPos start, BlockPos p, String pkMode, int food, List<GridSearch.Step> out) {
    int maxGap = "FULL".equals(pkMode) ? 3 : 2;
    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
      int runup = 0;
      BlockPos b1 = p.offset(-d[0], 0, -d[1]);
      if (standPath(b1) && !isWater(b1) && Double.isFinite(surfaceY(b1))) {
        runup = 1;
        BlockPos b2 = p.offset(-d[0] * 2, 0, -d[1] * 2);
        if (standPath(b2) && !isWater(b2) && Double.isFinite(surfaceY(b2))) runup = 2;
      }
      for (int gap = 1; gap <= maxGap; gap++) {
        // Every intermediate column over the gap must actually be an open void/drop.
        boolean gapOpen = true;
        int deepestDrop = 0;
        boolean arcClear =
            mc().level.noCollision(mc().player, body(foot(p)).move(0, 1.0, 0));
        for (int k = 1; k <= gap; k++) {
          BlockPos mid = p.offset(d[0] * k, 0, d[1] * k);
          if (!mc().level.hasChunkAt(mid)
              || Double.isFinite(surfaceY(mid))
              || isWater(mid)
              || lethalHazard(mid)) {
            gapOpen = false;
            break;
          }
          for (int h = 0; h <= 2; h++) {
            if (!mc().level.getBlockState(mid.above(h)).getCollisionShape(mc().level, mid.above(h)).isEmpty()) {
              arcClear = false;
              break;
            }
          }
          int drop = 12;
          for (int dep = 1; dep <= 11; dep++) {
            BlockPos below = mid.below(dep);
            if (lethalHazard(below)) {
              drop = 99;
              break;
            }
            if (mc().level.hasChunkAt(below)
                && !mc().level.getBlockState(below).getCollisionShape(mc().level, below).isEmpty()) {
              drop = dep;
              break;
            }
          }
          deepestDrop = Math.max(deepestDrop, drop);
        }
        if (!gapOpen || !arcClear) break;
        for (int dy : new int[] {0, -1, 1}) {
          if (!MovementPolicy.parkourAllowed(pkMode, gap, dy, deepestDrop, runup, true, food))
            continue;
          BlockPos landing = p.offset(d[0] * (gap + 1), dy, d[1] * (gap + 1));
          if (dy == 1) {
            boolean riseClear =
                mc().level.getBlockState(landing.above(2)).getCollisionShape(mc().level, landing.above(2)).isEmpty();
            for (int k = 1; k <= gap && riseClear; k++) {
              BlockPos hi = p.offset(d[0] * k, 3, d[1] * k);
              if (!mc().level.getBlockState(hi).getCollisionShape(mc().level, hi).isEmpty())
                riseClear = false;
            }
            if (!riseClear) continue;
          }
          if (landing.distSqr(start) > 96 * 96
              || blocked.contains(new Edge(p, landing))
              || !allowed(landing)) continue;
          if (searchStands.computeIfAbsent(landing, Navigator::standPath) && !isWater(landing)) {
            double cost =
                MovementPolicy.stepCost(
                    gap == 1 ? MovementPolicy.MoveKind.PARKOUR_SHORT : MovementPolicy.MoveKind.PARKOUR_SPRINT);
            out.add(new GridSearch.Step(cell(landing), cost));
          }
        }
      }
    }
  }

  private BlockPos collectFallbackGoals(BlockPos goal, BlockPos start, Set<BlockPos> out) {
    BlockPos best = null;
    double bestGoalSq = Double.MAX_VALUE;
    double bestStartSq = Double.MAX_VALUE;
    for (int dx = -2; dx <= 2; dx++)
      for (int dz = -2; dz <= 2; dz++)
        for (int dy = -1; dy <= 1; dy++) {
          BlockPos at = goal.offset(dx, dy, dz);
          if (!allowed(at) || !stand(at)) continue;
          Vec3 v = foot(at);
          if (!ArrivalGate.fallbackCandidate(
              v.x, v.y, v.z, goal.getX() + .5, goal.getY(), goal.getZ() + .5)) continue;
          out.add(at);
          double goalSq = (double) dx * dx + (double) dz * dz + Math.abs(dy) * 0.25;
          double startSq = at.distSqr(start);
          if (best == null
              || goalSq < bestGoalSq - 1e-6
              || (Math.abs(goalSq - bestGoalSq) <= 1e-6 && startSq < bestStartSq)) {
            best = at;
            bestGoalSq = goalSq;
            bestStartSq = startSq;
          }
        }
    return best;
  }

  private static GridSearch.Cell cell(BlockPos p) {
    return new GridSearch.Cell(p.getX(), p.getY(), p.getZ());
  }

  private static BlockPos pos(GridSearch.Cell p) {
    return new BlockPos(p.x(), p.y(), p.z());
  }
}
