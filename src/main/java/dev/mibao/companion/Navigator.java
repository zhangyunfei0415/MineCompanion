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
  private byte[] pathActions = new byte[0];
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
  private final Map<Long, Boolean> packedStands = new HashMap<>();
  private final Set<BlockPos> ladderTopGaps = new LinkedHashSet<>();
  private double lastDistSqToNext = Double.NaN;
  private final GridSearch.SearchWorkspace searchWorkspace = new GridSearch.SearchWorkspace();
  private final TickProbeCache probeCache = new TickProbeCache();
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
  private int parkourAlignCycles;
  private int parkourTicks;
  private final ClimbRules.ClimbHysteresis climbHysteresis = new ClimbRules.ClimbHysteresis();
  private boolean placement;

  private void resetParkour() {
    parkourPhase = 0;
    parkourIndex = -1;
    parkourTakeoff = null;
    parkourLanding = null;
    parkourAlignTicks = 0;
    parkourAlignCycles = 0;
    parkourTicks = 0;
  }
  private Direction requiredFace, placementFace;
  private java.util.function.Predicate<Vec3> placementStance = eye -> true;
  private java.util.function.Predicate<BlockHitResult> placementHit = hit -> true;
  private java.util.function.BiPredicate<Vec3, BlockHitResult> placementHitEye = (eye, hit) -> true;

  Navigator placementIntent(
      java.util.function.Predicate<Vec3> stance, java.util.function.Predicate<BlockHitResult> hit) {
    placementStance = stance == null ? eye -> true : stance;
    placementHit = hit == null ? h -> true : hit;
    placementHitEye = (eye, h) -> true;
    return this;
  }

  public Navigator placementIntent(
      java.util.function.Predicate<Vec3> stance,
      java.util.function.BiPredicate<Vec3, BlockHitResult> hitWithEye) {
    placementStance = stance == null ? eye -> true : stance;
    placementHit = hit -> true;
    placementHitEye = hitWithEye == null ? (eye, hit) -> true : hitWithEye;
    return this;
  }

  private boolean exactStance;
  private int maxRadius = 3;
  private int minInteractionDy = -4, maxInteractionDy = 3;
  private boolean allowScaffoldingColumnMine;
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

  public Navigator interactionDy(int minDy, int maxDy) {
    minInteractionDy = Math.max(-6, Math.min(0, minDy));
    maxInteractionDy = Math.max(0, Math.min(4, maxDy));
    return this;
  }

  public Navigator allowScaffoldingColumnMine(boolean value) {
    allowScaffoldingColumnMine = value;
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
    allowedCell = null;
    allowedEdge = null;
    strictWalk = false;
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
    allowedCell = null;
    allowedEdge = null;
    strictWalk = false;
    requiredFace = null;
    waterRecoveries = 0;
    placement = false;
    pickupBox = null;
    exact = true;
    topFace = false;
    localOnly = true;
    return begin(goal, false);
  }

  private boolean startConstrainedExact(BlockPos goal) {
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
    allowedEdge = null;
    strictWalk = false;
    return startConstrainedExact(goal);
  }

  private boolean strictWalk;
  private java.util.function.BiPredicate<BlockPos, BlockPos> allowedEdge;

  boolean startKnownWalk(BlockPos goal, java.util.function.Predicate<BlockPos> allowed) {
    return startKnownWalk(goal, allowed, null);
  }

  boolean startKnownWalk(
      BlockPos goal,
      java.util.function.Predicate<BlockPos> allowed,
      java.util.function.BiPredicate<BlockPos, BlockPos> edgeAllowed) {
    allowedCell = allowed;
    allowedEdge = edgeAllowed;
    strictWalk = true;
    return startConstrainedExact(goal);
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
    if (allowScaffoldingColumnMine
        && requiredFace == null
        && !placement
        && !topFace
        && mc().level != null
        && mc().level.hasChunkAt(goal)
        && mc().level.getBlockState(goal).getBlock()
            instanceof net.minecraft.world.level.block.ScaffoldingBlock) {
      return Reach.mineFrom(goal, eye) != null;
    }
    return (requiredFace != null
            ? Reach.faceFrom(goal, eye, requiredFace)
            : placement
                ? Reach.placementFrom(
                    goal, eye, placementFace, hit -> placementHit.test(hit) && placementHitEye.test(eye, hit))
                : topFace ? Reach.topFrom(goal, eye) : Reach.hitFrom(goal, eye))
        != null;
  }

  /** Counts one stance candidate: standability, line of sight and the reason it was rejected. */
  private void scanCandidate(BlockPos goal, BlockPos at, Set<BlockPos> options) {
    var player = mc().player;
    scannedCandidates++;
    boolean unloaded = !mc().level.hasChunkAt(at);
    boolean standable = !unloaded && stand(at);
    if (!standable) {
      if (unloaded) unloadedCandidates++;
      else if (hazard(at) || hazard(at.below())) hazardCandidates++;
      else blockedCandidates++;
      return;
    }
    standCandidates++;
    Vec3 footAt = foot(at);
    boolean seen = visible(goal, footAt.add(0, player.getEyeHeight(), 0));
    double dx = footAt.x - player.getX(), dz = footAt.z - player.getZ();
    double distance = Math.sqrt(dx * dx + dz * dz);
    if (distance < nearestCandidateDistance) {
      nearestCandidateDistance = distance;
      nearestCandidate = at;
    }
    if (seen) {
      visibleCandidates++;
      bothCandidates++;
      if (distance < nearestVisibleDistance) {
        nearestVisibleDistance = distance;
        nearestVisibleCandidate = at;
      }
      options.add(at);
    }
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
    packedStands.clear();
    path = List.of();
    index = 0;
    jump = new JumpCycle();
    arrival = new ArrivalGate();
    passage = new NavigationPassage();
    resetParkour();
    climbHysteresis.reset();
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
      if (options.isEmpty()) {
        for (int dx = -maxRadius; dx <= maxRadius; dx++)
          for (int dz = -maxRadius; dz <= maxRadius; dz++)
            for (int dy = minInteractionDy; dy <= maxInteractionDy; dy++)
              scanCandidate(goal, goal.offset(dx, dy, dz), options);
        if (options.isEmpty()) {
          // Only pay raycast cost on non-standable cells when failure diagnostics actually need visible_only.
          for (int dx = -maxRadius; dx <= maxRadius; dx++)
            for (int dz = -maxRadius; dz <= maxRadius; dz++)
              for (int dy = minInteractionDy; dy <= maxInteractionDy; dy++) {
                BlockPos at = goal.offset(dx, dy, dz);
                if (mc().level.hasChunkAt(at)
                    && !stand(at)
                    && visible(goal, foot(at).add(0, player.getEyeHeight(), 0))) {
                  visibleOnlyCandidates++;
                }
              }
        }
      }
    } else {
      if (stand(start)
          && ArrivalGate.near(
              player.getX(),
              player.getY(),
              player.getZ(),
              goal.getX() + .5,
              exact ? foot(goal).y : goal.getY(),
              goal.getZ() + .5,
              exact)) options.add(start);
      boolean goalPlanning = !strictWalk;
      if (options.isEmpty())
        for (int dx = -2; dx <= 2; dx++)
          for (int dz = -2; dz <= 2; dz++)
            for (int dy = -1; dy <= 1; dy++) {
              BlockPos at = goal.offset(dx, dy, dz);
              if (exact && !at.equals(goal)) continue;
              Vec3 v = foot(at, goalPlanning);
              if (allowed(at) && stand(at, goalPlanning)
                  && ArrivalGate.candidate(
                      v.x,
                      v.y,
                      v.z,
                      goal.getX() + .5,
                      exact ? foot(goal, goalPlanning).y : goal.getY(),
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
    double arrivalY = (exact || fallbackTargetUsed) ? foot(target).y : target.getY();
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
    boolean allowClimbableArrival =
        interaction || pickupBox != null || isClimbable(target, !strictWalk);
    boolean safelyStanding =
        grounded()
            && ClimbRules.canAcceptClimbableArrival(
                Store.config.allowClimb && p.onClimbable(),
                p.onGround(),
                allowClimbableArrival);
    if (arrival.step(range, safelyStanding, p.getDeltaMovement().y)) {
      stop();
      return 1;
    }
    if (range && safelyStanding) {
      if (ClimbRules.shouldSneakToStabilizeClimb(
          Store.config.allowClimb && p.onClimbable(),
          p.onGround(),
          isScaffoldingSupport(p.blockPosition()))) {
        PlayerControls.motionState(false, false, false, true);
      } else {
        PlayerControls.motionState(false, false);
      }
      return 0;
    }
    if (search != null) {
      if (ClimbRules.shouldSneakToStabilizeClimb(
          Store.config.allowClimb && p.onClimbable(),
          p.onGround(),
          isScaffoldingSupport(p.blockPosition()))) {
        PlayerControls.motionState(false, false, false, true);
      } else {
        release();
      }
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
        String gapNote =
            ladderTopGaps.isEmpty()
                ? ""
                : "；[LADDER_TOP_GAP] 梯顶上方纯空气断档="
                    + ladderTopGaps.stream().limit(3).map(BlockPos::toShortString).toList();
        String replanNote =
            replans > 0 ? "；重规划 " + replans + "/3；已屏蔽边 " + blocked.size() : "";
        return fail("搜索预算内未找到安全路线（展开 " + search.expanded() + "/20000；候选站位 " + goals.size() + "）" + gapNote + replanNote);
      }
      int len = search.pathLength();
      var built = new ArrayList<BlockPos>(len);
      byte[] actions = new byte[len];
      int ox = standingOriginX, oy = standingOriginY, oz = standingOriginZ;
      for (int i = 0; i < len; i++) {
        long k = search.pathKey(i);
        built.add(new BlockPos(ox + GridSearch.unpackRelX(k), oy + GridSearch.unpackRelY(k), oz + GridSearch.unpackRelZ(k)));
        actions[i] = search.pathAction(i);
      }
      path = List.copyOf(built);
      pathActions = actions;
      if (path.isEmpty()) {
        path = List.of(standingCell());
        pathActions = new byte[1];
      }
      if (fallbackTargetUsed && !path.isEmpty()) target = path.get(path.size() - 1);
      index = 0;
      resetParkour();
      search = null;
      stuck = 0;
      last = p.position();
      lastDistSqToNext = Double.NaN;
    }
    BlockPos curStand = standingCell();
    BlockPos nextCell = (path != null && index < path.size()) ? path.get(index) : null;
    BlockPos prevStepCell =
        (path != null && index > 0 && index < path.size()) ? path.get(index - 1) : curStand;
    Vec3 nextWaypoint = nextCell != null ? foot(nextCell) : null;
    double curDistSq = nextWaypoint != null ? p.position().distanceToSqr(nextWaypoint) : Double.NaN;
    if (nextWaypoint != null && !Double.isFinite(lastDistSqToNext)) {
      lastDistSqToNext = curDistSq;
    }
    int targetDy = nextCell != null ? Integer.compare(nextCell.getY(), curStand.getY()) : 0;
    boolean climbingTick = Store.config.allowClimb && p.onClimbable();
    boolean verticalClimbStep =
        Store.config.allowClimb
            && nextCell != null
            && nextCell.getX() == prevStepCell.getX()
            && nextCell.getZ() == prevStepCell.getZ()
            && nextCell.getY() != prevStepCell.getY()
            && (p.onClimbable()
                || isClimbable(prevStepCell, !strictWalk)
                || isClimbable(nextCell, !strictWalk));
    boolean horizontalStep =
        !verticalClimbStep
            && nextCell != null
            && (nextCell.getX() != curStand.getX() || nextCell.getZ() != curStand.getZ());
    boolean progressed;
    if (verticalClimbStep && nextWaypoint != null && Double.isFinite(lastDistSqToNext)) {
      progressed = ClimbRules.madeProgress(lastDistSqToNext, curDistSq, last.y, p.getY(), targetDy);
    } else if (horizontalStep) {
      progressed = MovementPolicy.madeHorizontalWalkProgress(last.x, last.z, p.getX(), p.getZ());
    } else if (climbingTick && nextWaypoint != null && Double.isFinite(lastDistSqToNext)) {
      progressed = ClimbRules.madeProgress(lastDistSqToNext, curDistSq, last.y, p.getY(), targetDy);
    } else {
      progressed = p.position().distanceToSqr(last) >= .015;
    }
    if (!progressed) stuck++;
    else {
      stuck = 0;
      last = p.position();
      lastDistSqToNext = curDistSq;
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
            range)) {
      index++;
      lastDistSqToNext = Double.NaN;
      last = p.position();
    }
    if (index >= path.size()) {
      if (range) {
        if (++settling > 40) return fail("已接近但未在安全站位稳定落地");
        if (ClimbRules.shouldSneakToStabilizeClimb(
            Store.config.allowClimb && p.onClimbable(),
            p.onGround(),
            isScaffoldingSupport(p.blockPosition()))) {
          PlayerControls.motionState(false, false, false, true);
        } else {
          PlayerControls.motionState(false, false);
        }
        return 0;
      }
      if (!replan(standingCell())) return -1;
      return 0;
    }
    // Skip only collinear level cells; never skip a rise, corner, water, climb or parkour step.
    while (index + 1 < path.size()) {
      if (index + 1 < pathActions.length
          && !MovementPolicy.canSkipCollinearWaypoint(pathActions[index], pathActions[index + 1])) {
        break;
      }
      var a = path.get(index);
      var b = path.get(index + 1);
      if (a.distManhattan(b) > 1
          || isWater(a)
          || isWater(b)
          || isClimbable(a, !strictWalk)
          || isClimbable(b, !strictWalk)) break;
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
            && (p.onClimbable()
                || isClimbable(prevCell, !strictWalk)
                || isClimbable(next, !strictWalk));
    boolean inShallowWater = isWater(p.blockPosition()) || isWater(next) || p.isInWater();
    boolean highLedge = !inShallowWater && !p.onClimbable() && (exposedLedgeCached(standingCell()) || exposedLedgeCached(next));
    if (!verticalClimb) climbHysteresis.reset();
    if (verticalClimb) {
      if (!strictWalk) {
        var climbCorridor = body(p.position()).minmax(body(destination));
        if (next.getY() > prevCell.getY()) climbCorridor = climbCorridor.expandTowards(0, 0.25, 0);
        int climbGate = passage.prepare(climbCorridor);
        if (climbGate < 0) return fail(passage.error());
        if (climbGate == 0) {
          if (ClimbRules.shouldHoldJumpOnClimbGateWait(
              p.onClimbable(), p.onGround(), next.getY() > prevCell.getY())) {
            PlayerControls.motionState(false, true, false, false);
          } else {
            release();
          }
          stuck = 0;
          last = p.position();
          return 0;
        }
      }
      BlockPos ladderPos =
          isClimbable(p.blockPosition(), !strictWalk)
              ? p.blockPosition()
              : (isClimbable(prevCell, !strictWalk) ? prevCell : next);
      var climbState = mc().level.getBlockState(ladderPos);
      boolean isScaffolding =
          climbState.getBlock() instanceof net.minecraft.world.level.block.ScaffoldingBlock
              || mc().level.getBlockState(p.blockPosition().below()).getBlock()
                  instanceof net.minecraft.world.level.block.ScaffoldingBlock;
      float ladderOppositeYaw;
      if (climbState.hasProperty(net.minecraft.world.level.block.LadderBlock.FACING)) {
        ladderOppositeYaw =
            climbState
                .getValue(net.minecraft.world.level.block.LadderBlock.FACING)
                .getOpposite()
                .toYRot();
      } else if (climbState.hasProperty(net.minecraft.world.level.block.TrapDoorBlock.FACING)) {
        ladderOppositeYaw =
            climbState
                .getValue(net.minecraft.world.level.block.TrapDoorBlock.FACING)
                .getOpposite()
                .toYRot();
      } else if (delta.horizontalDistanceSqr() < 0.04) {
        ladderOppositeYaw = p.getYRot();
      } else {
        ladderOppositeYaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
      }
      ClimbRules.ClimbCommand cmd =
          ClimbRules.guideClimb(
              p.getX(),
              p.getY(),
              p.getZ(),
              p.onClimbable(),
              isScaffolding,
              ladderPos.getX(),
              ladderPos.getY(),
              ladderPos.getZ(),
              next.getX(),
              next.getY(),
              next.getZ(),
              ladderOppositeYaw,
              p.getYRot(),
              climbHysteresis);
      if (cmd.mode() == ClimbRules.ClimbMode.RECOVER_REPLAN) {
        release();
        if (!replan(standingCell())) return -1;
        return 0;
      }
      var steering = MovementSteering.toward(p.getYRot(), cmd.targetYaw());
      p.setYRot(steering.yaw());
      p.setXRot(cmd.targetPitch());
      boolean fwd = cmd.forward() && steering.forward();
      PlayerControls.motionState(fwd, cmd.jump(), false, cmd.sneak());
      return 0;
    }
    double landingDistSq =
        (p.getX() - destination.x) * (p.getX() - destination.x)
            + (p.getZ() - destination.z) * (p.getZ() - destination.z);
    boolean alreadyCrossedToLanding =
        p.onGround()
            && Math.abs(p.getY() - destination.y) < 0.65
            && (standingCell().equals(next) || landingDistSq < 0.85);
    boolean plannedParkour = index < pathActions.length && pathActions[index] == (byte) 3;
    if (horizGap >= 1 && !strictWalk && !plannedParkour) {
      if (!replan(standingCell())) return -1;
      return 0;
    }
    if (MovementPolicy.parkourExecutionAllowed(plannedParkour, strictWalk, horizGap)) {
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
          parkourTicks = 0;
          parkourPhase = (horizGap >= 2 && along > -0.15 && horizSpeed < 0.15) ? 1 : 2;
          parkourAlignCycles = parkourPhase == 1 ? 1 : 0;
        }
        if (MovementPolicy.parkourTimedOut(parkourAlignCycles, ++parkourTicks)) {
          if (parkourTakeoff != null && parkourLanding != null) {
            blocked.add(new Edge(parkourTakeoff, parkourLanding));
          }
          if (!replan(standingCell())) return -1;
          return 0;
        }
        Vec3 targetAim =
            parkourLanding != null ? foot(parkourLanding).subtract(p.position()) : delta;
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-targetAim.x, targetAim.z));
        p.setXRot(p.getXRot() + (8 - p.getXRot()) * .35f);
        if (!p.onGround()) {
          var steering = MovementSteering.toward(p.getYRot(), desiredYaw);
          p.setYRot(steering.yaw());
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
          parkourAlignCycles++;
          if (MovementPolicy.parkourTimedOut(parkourAlignCycles, parkourTicks)) {
            if (parkourTakeoff != null && parkourLanding != null) {
              blocked.add(new Edge(parkourTakeoff, parkourLanding));
            }
            if (!replan(standingCell())) return -1;
            return 0;
          }
          parkourPhase = 1;
          parkourAlignTicks = 0;
          PlayerControls.motionState(false, false, false, true);
          return 0;
        }
        var steering = MovementSteering.toward(p.getYRot(), desiredYaw);
        p.setYRot(steering.yaw());
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
    if (!stand(next, !strictWalk) || (allowedEdge != null && !allowedEdge.test(prevCell, next))) {
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
    boolean upcomingCorner = false;
    if (index + 1 < path.size()) {
      BlockPos after = path.get(index + 1);
      int dx1 = next.getX() - prevCell.getX(), dz1 = next.getZ() - prevCell.getZ();
      int dx2 = after.getX() - next.getX(), dz2 = after.getZ() - next.getZ();
      upcomingCorner = (dx1 * dx2 + dz1 * dz2) <= 0;
    }
    float yawRem = MovementSteering.remainingYaw(p.getYRot(), desiredYaw);
    boolean sprint =
        !highLedge
            && MovementPolicy.shouldSprint(
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
                (stepUp || (inShallowWater && destination.y >= p.getY() + .2))
                    && steering.forward()
                    && (!highLedge || yawRem <= 18f),
                ticks);
    boolean onScaffoldingSupport =
        isScaffoldingSupport(standingCell()) || isScaffoldingSupport(p.blockPosition());
    // On high-altitude narrow ramps/catwalks (exposed >=3-block drop), crouch whenever not actively
    // jumping up a 1-block step so corners and alignment pauses cannot slip off the edge.
    // Never crouch on scaffolding, where sneak drops the player through the scaffolding floor.
    boolean ledgeSneak =
        MovementPolicy.shouldLedgeSneak(highLedge, p.onGround(), jumpBit, onScaffoldingSupport);
    if (highLedge && stepUp && yawRem > 18f) {
      jumpBit = false;
      ledgeSneak = !onScaffoldingSupport;
    }
    PlayerControls.motionState(steering.forward(), jumpBit, sprint, ledgeSneak);
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
    climbHysteresis.reset();
    if (water != null) {
      water.stop();
      water = null;
    }
    if (owned) release();
    if (search != null) {
      search.cancel();
      search = null;
    }
    probeCache.clear();
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

  private static boolean isScaffoldingBlock(BlockPos p) {
    return mc().level != null
        && mc().level.hasChunkAt(p)
        && mc().level.getBlockState(p).getBlock()
            instanceof net.minecraft.world.level.block.ScaffoldingBlock;
  }

  private static boolean isScaffoldingSupport(BlockPos cell) {
    return isScaffoldingBlock(cell) || isScaffoldingBlock(cell.below());
  }

  static boolean isClimbable(BlockPos p) {
    return isClimbable(p, false);
  }

  static boolean isClimbable(BlockPos p, boolean planning) {
    if (mc().level == null || !mc().level.hasChunkAt(p)) return false;
    var state = mc().level.getBlockState(p);
    if (state.is(net.minecraft.tags.BlockTags.CLIMBABLE)) return true;
    if (state.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock
        && mc().level.hasChunkAt(p.below())) {
      BlockPos belowPos = p.below();
      var belowState = mc().level.getBlockState(belowPos);
      boolean belowMakes =
          belowState.getBlock().makesOpenTrapdoorAboveClimbable(belowState, mc().level, belowPos, state);
      boolean open = state.getValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN);
      boolean wooden = state.is(net.minecraft.tags.BlockTags.WOODEN_TRAPDOORS);
      return planning
          ? ClimbRules.isTrapdoorClimbableForPlanning(
              true, wooden, open, belowMakes, Store.config.autoOpenDoors)
          : ClimbRules.isTrapdoorClimbableNow(true, open, belowMakes);
    }
    return false;
  }

  static boolean isClosedTrapdoor(BlockPos p) {
    if (mc().level == null || !mc().level.hasChunkAt(p)) return false;
    var state = mc().level.getBlockState(p);
    return state.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock
        && !state.getValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN);
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

  private static final double[][] SURFACE_PROBES = {
    {.5, .5}, {.3, .3}, {.7, .3}, {.3, .7}, {.7, .7}
  };

  static double surfaceY(BlockPos cell) {
    if (!mc().level.hasChunkAt(cell) || !mc().level.hasChunkAt(cell.below())) return Double.NaN;
    var belowPos = cell.below();
    var belowState = mc().level.getBlockState(belowPos);
    if (belowState.getBlock() instanceof net.minecraft.world.level.block.ScaffoldingBlock) {
      return cell.getY();
    }
    var shape = belowState.getCollisionShape(mc().level, belowPos);
    if (shape.isEmpty()) return Double.NaN;
    var boxes = shape.toAabbs();
    double bestCenter = Double.NaN;
    double bestMulti = Double.NaN;
    int hitProbes = 0;
    for (double[] pt : SURFACE_PROBES) {
      double px = pt[0], pz = pt[1];
      double probeTop = Double.NaN;
      for (int i = 0; i < boxes.size(); i++) {
        AABB b = boxes.get(i);
        // Reject thin vertical strips (<0.2: open trapdoors, panes, iron bars, chains) while keeping carpets/slabs.
        if (b.maxX - b.minX < 0.2 || b.maxZ - b.minZ < 0.2) continue;
        if (b.minX <= px && b.maxX >= px && b.minZ <= pz && b.maxZ >= pz && b.maxY > 0 && b.maxY <= 1.501) {
          if (!Double.isFinite(probeTop) || b.maxY > probeTop) probeTop = b.maxY;
        }
      }
      if (Double.isFinite(probeTop)) {
        if (px == .5 && pz == .5) bestCenter = probeTop;
        else {
          hitProbes++;
          if (!Double.isFinite(bestMulti) || probeTop > bestMulti) bestMulti = probeTop;
        }
      }
    }
    double chosen = Double.isFinite(bestCenter) ? bestCenter : (hitProbes >= 2 ? bestMulti : Double.NaN);
    return Double.isFinite(chosen) ? cell.getY() - 1 + chosen : Double.NaN;
  }

  static Vec3 foot(BlockPos cell) {
    return foot(cell, false);
  }

  static Vec3 foot(BlockPos cell, boolean planning) {
    if (isClimbable(cell, planning)) {
      return new Vec3(cell.getX() + .5, cell.getY(), cell.getZ() + .5);
    }
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
    if (Store.config.allowClimb && isClimbable(cell, planning)) {
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

  private boolean exposedLedgeCached(BlockPos cell) {
    var level = mc().level;
    if (level == null || !level.hasChunkAt(cell)) return false;
    return probeCache.exposedLedge(level, level.getGameTime(), cell);
  }

  private static boolean exposedLedge(BlockPos cell) {
    if (mc().level == null || !mc().level.hasChunkAt(cell)) return false;
    for (Direction d : Direction.Plane.HORIZONTAL) {
      BlockPos side = cell.relative(d);
      if (!mc().level.hasChunkAt(side)) return true;
      boolean hasFloor = false;
      for (int drop = 0; drop <= 2; drop++) {
        BlockPos probe = side.below(drop);
        if (!mc().level.hasChunkAt(probe)) break;
        if (isClimbable(probe)
            || !mc().level.getBlockState(probe).getCollisionShape(mc().level, probe).isEmpty()) {
          hasFloor = true;
          break;
        }
      }
      if (!hasFloor) return true;
    }
    return false;
  }

  private static final class TickProbeCache {
    private java.lang.ref.WeakReference<net.minecraft.world.level.Level> levelRef =
        new java.lang.ref.WeakReference<>(null);
    private long tick = Long.MIN_VALUE;
    private final Map<Long, Boolean> ledge = new HashMap<>(64);

    void clear() {
      levelRef = new java.lang.ref.WeakReference<>(null);
      tick = Long.MIN_VALUE;
      ledge.clear();
    }

    boolean exposedLedge(net.minecraft.world.level.Level currentLevel, long gameTick, BlockPos cell) {
      if (levelRef.get() != currentLevel || tick != gameTick || ledge.size() > 512) {
        levelRef = new java.lang.ref.WeakReference<>(currentLevel);
        tick = gameTick;
        ledge.clear();
      }
      long key = cell.asLong();
      Boolean cached = ledge.get(key);
      if (cached != null) return cached;
      boolean val = Navigator.exposedLedge(cell);
      ledge.put(key, val);
      return val;
    }
  }

  public static boolean grounded() {
    var p = mc().player;
    if (p == null) return false;
    if (Store.config.allowClimb && p.onClimbable()) return true;
    if (!p.onGround()) return false;
    var box = p.getBoundingBox();
    if (mc().level.getBlockState(p.blockPosition().below()).getBlock()
        instanceof net.minecraft.world.level.block.ScaffoldingBlock) return true;
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
    if (allowedEdge != null && !allowedEdge.test(from, to)) return false;
    return traversable(from, to, !strictWalk);
  }

  static boolean openMiningStep(BlockPos from, BlockPos to) {
    return stand(from) && stand(to) && traversable(from, to, false);
  }

  static boolean openExplorationStep(BlockPos from, BlockPos to) {
    return standPath(from) && standPath(to) && traversable(from, to, Store.config.autoOpenDoors);
  }

  static boolean openClimbStep(BlockPos from, BlockPos to) {
    boolean planning = Store.config.autoOpenDoors;
    if (to.getY() < from.getY()
        && to.getX() == from.getX()
        && to.getZ() == from.getZ()
        && !ClimbRules.canDescendVerticallyInto(isClosedTrapdoor(to))) {
      return false;
    }
    return (isClimbable(from, planning) || isClimbable(to, planning))
        && stand(to, planning);
  }

  private static boolean traversable(BlockPos from, BlockPos to, boolean planning) {
    Vec3 a = foot(from, planning), b = foot(to, planning);
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
    var p = mc().player;
    if (p != null
        && ClimbRules.shouldSneakToStabilizeClimb(
            Store.config.allowClimb && p.onClimbable(),
            p.onGround(),
            isScaffoldingSupport(p.blockPosition()))) {
      PlayerControls.motionState(false, false, false, true);
    } else {
      release();
    }
    BlockPos failedTakeoff = parkourTakeoff;
    BlockPos failedLanding = parkourLanding;
    resetParkour();
    climbHysteresis.reset();
    if (++replans > 3) {
      fail("局部重规划三次仍受阻；" + diagnostic());
      return false;
    }
    if (index < path.size()) {
      BlockPos failedNext = path.get(index);
      if (failedTakeoff != null && failedLanding != null && failedNext.equals(failedLanding)) {
        blocked.add(new Edge(failedTakeoff, failedLanding));
      } else if (start.distManhattan(failedNext) <= 1
          && (!isClimbable(failedNext, !strictWalk) || replans >= 2)) {
        blocked.add(new Edge(start, failedNext));
      }
    }
    packedStands.clear();
    search = createSearch(start);
    stuck = settling = 0;
    last = mc().player.position();
    return true;
  }

  private int standingOriginX, standingOriginY, standingOriginZ;
  private static final int[][] CARDINAL_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
  private static final int[] STEP_DYS = {0, 1, -1, 2, -2};
  private final List<GridSearch.Step> parkourScratch = new ArrayList<>(4);

  private GridSearch createSearch(BlockPos start) {
    ladderTopGaps.clear();
    int food = mc().player == null ? 20 : mc().player.getFoodData().getFoodLevel();
    String pkMode = strictWalk ? "OFF" : MovementPolicy.normalizeParkourMode(Store.config.parkourMode);
    standingOriginX = start.getX();
    standingOriginY = start.getY();
    standingOriginZ = start.getZ();
    final int ox = standingOriginX, oy = standingOriginY, oz = standingOriginZ;

    // Pack goal offsets within [-96, 96] into a fast HashSet<Long> (fallback to BlockPos for out-of-range).
    final Set<Long> packedGoals = new HashSet<>(goals.size() * 2 + 1);
    for (BlockPos g : goals) {
      int gx = g.getX() - ox, gy = g.getY() - oy, gz = g.getZ() - oz;
      if (Math.abs(gx) <= 96 && Math.abs(gy) <= 96 && Math.abs(gz) <= 96) {
        packedGoals.add(GridSearch.packRelative(gx, gy, gz));
      }
    }
    final BlockPos.MutableBlockPos pMut = new BlockPos.MutableBlockPos();
    final BlockPos.MutableBlockPos qMut = new BlockPos.MutableBlockPos();
    final boolean hasBlocked = !blocked.isEmpty();

    return GridSearch.weightedPacked(
        GridSearch.packRelative(0, 0, 0),
        packedGoals::contains,
        (fromKey, sink) -> {
          int dx0 = GridSearch.unpackRelX(fromKey);
          int dy0 = GridSearch.unpackRelY(fromKey);
          int dz0 = GridSearch.unpackRelZ(fromKey);
          int px = ox + dx0, py = oy + dy0, pz = oz + dz0;
          pMut.set(px, py, pz);
          for (int[] d : CARDINAL_DIRS) {
            for (int dy : STEP_DYS) {
              int rdx = dx0 + d[0], rdy = dy0 + dy, rdz = dz0 + d[1];
              if (Math.abs(rdx) > 96 || Math.abs(rdy) > 96 || Math.abs(rdz) > 96) continue;
              if (rdx * rdx + rdy * rdy + rdz * rdz > 96 * 96) continue;
              qMut.set(ox + rdx, oy + rdy, oz + rdz);
              if (hasBlocked && blocked.contains(new Edge(pMut, qMut))) continue;
              if (!allowed(qMut)) continue;
              if (packedStands.size() > 16000) packedStands.clear();
              long qAbs = qMut.asLong();
              Boolean okStand = packedStands.get(qAbs);
              if (okStand == null) {
                okStand = standPath(qMut);
                packedStands.put(qAbs, okStand);
              }
              if (okStand && traversable(pMut, qMut)) {
                double cost =
                    MovementPolicy.stepCost(
                            isWater(qMut)
                                ? MovementPolicy.MoveKind.WADE_WATER
                                : MovementPolicy.MoveKind.WALK)
                        + Math.abs(dy) * 0.35;
                sink.accept(GridSearch.packRelative(rdx, rdy, rdz), cost, (byte) 0);
              }
            }
          }
          if (Store.config.allowClimb) {
            boolean pClimbable = isClimbable(pMut, !strictWalk);
            int rdyUp = dy0 + 1;
            if (Math.abs(rdyUp) <= 96 && dx0 * dx0 + rdyUp * rdyUp + dz0 * dz0 <= 96 * 96) {
              qMut.set(px, py + 1, pz);
              boolean qClimbable = isClimbable(qMut, !strictWalk);
              if (allowed(qMut)
                  && (!hasBlocked || !blocked.contains(new Edge(pMut, qMut)))
                  && (pClimbable || qClimbable)
                  && ClimbRules.canClimbOntoAboveCell(isClosedTrapdoor(pMut), qClimbable)) {
                long qAbs = qMut.asLong();
                Boolean okStand = packedStands.get(qAbs);
                if (okStand == null) {
                  okStand = standPath(qMut);
                  packedStands.put(qAbs, okStand);
                }
                if (okStand) {
                  sink.accept(
                      GridSearch.packRelative(dx0, rdyUp, dz0),
                      MovementPolicy.stepCost(MovementPolicy.MoveKind.CLIMB_UP) + 0.35,
                      (byte) 1);
                } else if (pClimbable
                    && !qClimbable
                    && ladderTopGaps.size() < 8
                    && mc().level.hasChunkAt(qMut)
                    && mc().level.getBlockState(qMut).isAir()) {
                  for (int[] d : CARDINAL_DIRS) {
                    BlockPos upperSide = new BlockPos(px + d[0], py + 2, pz + d[1]);
                    if (standPath(upperSide)) {
                      ladderTopGaps.add(qMut.immutable());
                      break;
                    }
                  }
                }
              }
            }
            int rdyDown = dy0 - 1;
            if (Math.abs(rdyDown) <= 96 && dx0 * dx0 + rdyDown * rdyDown + dz0 * dz0 <= 96 * 96) {
              qMut.set(px, py - 1, pz);
              if (allowed(qMut)
                  && (!hasBlocked || !blocked.contains(new Edge(pMut, qMut)))
                  && isClimbable(qMut, !strictWalk)
                  && ClimbRules.canDescendVerticallyInto(isClosedTrapdoor(qMut))) {
                long qAbs = qMut.asLong();
                Boolean okStand = packedStands.get(qAbs);
                if (okStand == null) {
                  okStand = standPath(qMut);
                  packedStands.put(qAbs, okStand);
                }
                if (okStand) {
                  sink.accept(
                      GridSearch.packRelative(dx0, rdyDown, dz0),
                      MovementPolicy.stepCost(MovementPolicy.MoveKind.CLIMB_DOWN) + 0.35,
                      (byte) 2);
                }
              }
            }
          }
          if (!"OFF".equals(pkMode)
              && !isWater(pMut)
              && !isClimbable(pMut)
              && !isScaffoldingSupport(pMut)
              && Double.isFinite(surfaceY(pMut))) {
            parkourScratch.clear();
            addParkourSteps(start, pMut, pkMode, food, parkourScratch);
            for (int i = 0; i < parkourScratch.size(); i++) {
              GridSearch.Step st = parkourScratch.get(i);
              GridSearch.Cell sc = st.cell();
              int pxd = sc.x() - ox, pyd = sc.y() - oy, pzd = sc.z() - oz;
              if (Math.abs(pxd) <= 96 && Math.abs(pyd) <= 96 && Math.abs(pzd) <= 96) {
                sink.accept(
                    GridSearch.packRelative(pxd, pyd, pzd),
                    st.cost() + Math.abs(sc.y() - py) * 0.35,
                    (byte) 3);
              }
            }
          }
        },
        k -> {
          if (goals.isEmpty()) return 0.0;
          int px = ox + GridSearch.unpackRelX(k);
          int py = oy + GridSearch.unpackRelY(k);
          int pz = oz + GridSearch.unpackRelZ(k);
          double bestSq = Double.MAX_VALUE;
          for (BlockPos g : goals) {
            double gx = px - g.getX(), gy = py - g.getY(), gz = pz - g.getZ();
            double dSq = gx * gx + gy * gy + gz * gz;
            if (dSq < bestSq) bestSq = dSq;
          }
          return Math.sqrt(bestSq);
        },
        GridSearch.KeyCodec.relative(ox, oy, oz),
        20000,
        searchWorkspace);
  }

  private void addParkourSteps(
      BlockPos start, BlockPos p, String pkMode, int food, List<GridSearch.Step> out) {
    int maxGap = "FULL".equals(pkMode) ? 3 : 2;
    for (int[] d : CARDINAL_DIRS) {
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
          long landKey = landing.asLong();
          Boolean okLand = packedStands.get(landKey);
          if (okLand == null) {
            okLand = standPath(landing);
            packedStands.put(landKey, okLand);
          }
          if (okLand
              && !isWater(landing)
              && !isClimbable(landing)
              && !isScaffoldingSupport(landing)) {
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
    boolean goalPlanning = !strictWalk;
    for (int dx = -2; dx <= 2; dx++)
      for (int dz = -2; dz <= 2; dz++)
        for (int dy = -1; dy <= 1; dy++) {
          BlockPos at = goal.offset(dx, dy, dz);
          if (!allowed(at) || !stand(at, goalPlanning)) continue;
          Vec3 v = foot(at, goalPlanning);
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
