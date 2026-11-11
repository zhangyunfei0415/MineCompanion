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
  private BlockPos target;
  private String error = "";
  private boolean baritone, exact, topFace, localOnly, interaction;
  private GridSearch search;
  private Set<BlockPos> goals = Set.of();
  private AABB pickupBox;

  private record Edge(BlockPos from, BlockPos to) {}

  private final Set<Edge> blocked = new HashSet<>();
  private final Map<BlockPos, Boolean> searchStands = new HashMap<>();
  private SwimRecovery water;
  private int waterRecoveries;
  private final BaritoneNavigation external = new BaritoneNavigation();
  private NavigationPassage passage = new NavigationPassage();
  private JumpCycle jump = new JumpCycle();
  private ArrivalGate arrival = new ArrivalGate();
  private boolean placement;
  private Direction requiredFace, placementFace;
  private boolean exactStance;
  private int maxRadius = 3;
  private int scannedCandidates, standCandidates, visibleCandidates, bothCandidates;
  private int visibleOnlyCandidates, blockedCandidates, unloadedCandidates, hazardCandidates;
  private BlockPos nearestCandidate, nearestVisibleCandidate;
  private double nearestCandidateDistance = Double.MAX_VALUE;
  private double nearestVisibleDistance = Double.MAX_VALUE;

  public boolean startForFace(BlockPos goal, Direction face) {
    requiredFace = face;
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
    placement = false;
    pickupBox = null;
    exact = false;
    topFace = false;
    localOnly = false;
    return begin(goal, false);
  }

  public boolean startLocal(BlockPos goal) {
    requiredFace = null;
    placement = false;
    pickupBox = null;
    exact = false;
    topFace = false;
    localOnly = true;
    return begin(goal, false);
  }

  public boolean startExact(BlockPos goal) {
    requiredFace = null;
    placement = false;
    pickupBox = null;
    exact = true;
    topFace = false;
    localOnly = true;
    return begin(goal, false);
  }

  public boolean startForInteraction(BlockPos goal) {
    requiredFace = null;
    placement = false;
    pickupBox = null;
    exact = false;
    topFace = false;
    localOnly = true;
    return begin(goal, true);
  }

  public boolean startForPlanting(BlockPos goal) {
    requiredFace = null;
    placement = false;
    pickupBox = null;
    exact = false;
    topFace = true;
    localOnly = true;
    return begin(goal, true);
  }

  public boolean startForPickup(AABB item) {
    requiredFace = null;
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
    return PickupGeometry.touches(
        p.getX(), p.getY(), p.getZ(), p.getBbWidth(), p.getBbHeight(), pickup());
  }

  public boolean startForPlacement(BlockPos goal) {
    return startForPlacement(goal, null);
  }

  public boolean startForPlacement(BlockPos goal, Direction face) {
    placementFace = face;
    requiredFace = null;
    pickupBox = null;
    exact = false;
    topFace = false;
    localOnly = true;
    placement = true;
    return begin(goal, true);
  }

  private boolean visible(BlockPos goal, Vec3 eye) {
    return (requiredFace != null
            ? Reach.faceFrom(goal, eye, requiredFace)
            : placement
                ? Reach.placementFrom(goal, eye, placementFace)
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
    error = "";
    target = goal;
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
    if (mc().screen != null || mc().player.containerMenu != mc().player.inventoryMenu) {
      if (!(mc().screen
              instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)
          || !InventorySession.canClose()) {
        error = "移动前需收好鼠标/合成栏物品并正常关闭界面";
        return false;
      }
      mc().player.closeContainer();
    }
    if (!interaction && !exact && !localOnly && external.start(goal)) {
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
    if (mc().player.isInWater()) {
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
    Set<BlockPos> options = new HashSet<>();
    if (pickupBox != null) {
      options.addAll(
          PickupGeometry.stances(
                  pickup(),
                  player.getBbWidth(),
                  player.getBbHeight(),
                  c -> surfaceY(pos(c)),
                  c -> stand(pos(c)))
              .stream()
              .map(Navigator::pos)
              .toList());
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
              if (stand(at)
                  && ArrivalGate.candidate(
                      v.x,
                      v.y,
                      v.z,
                      goal.getX() + .5,
                      exact ? surfaceY(goal) : goal.getY(),
                      goal.getZ() + .5,
                      exact)) options.add(at);
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
    if (water != null) {
      Actions.status = "导航恢复 · 上浮并寻找近岸安全落脚点";
      int r = water.tick();
      if (r < 0) return fail(water.error());
      if (r == 0) return 0;
      water = null;
      return begin(target, interaction) ? 0 : -1;
    }
    if (p.isInWater() && !baritone) {
      if (++waterRecoveries > 2) return fail("[WATER_ESCAPE_FAILED] 重复落水达到恢复上限");
      water = new SwimRecovery(120);
      return 0;
    }
    if (++ticks > (baritone ? 12000 : 2400)) return fail("[NAVIGATION_TIMEOUT] 导航时间预算耗尽");
    double arrivalY = exact ? surfaceY(target) : target.getY();
    if (baritone && !exact && Math.abs(p.getX() - target.getX() - .5) <= 2
        && Math.abs(p.getZ() - target.getZ() - .5) <= 2) {
      BlockPos surface = ColumnProbe.nearest(target.getX(), target.getZ(), target.getY(), 3);
      if (surface != null) arrivalY = foot(surface).y;
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
      if (result != GridSearch.State.FOUND)
        return fail("搜索预算内未找到安全路线（展开 " + search.expanded() + "/20000；候选站位 " + goals.size() + "）");
      path = search.path().stream().map(Navigator::pos).toList();
      if (path.isEmpty()) path = List.of(standingCell());
      index = 0;
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
    if (stuck > 100) return fail("路线受阻或尚未到达目标高度，请检查地形");
    if (baritone) {
      if (stuck > 200 || (ticks > 100 && !external.active()))
        return fail("[NO_PATH] Baritone 已停止或连续十秒没有位移；请观察路径与目标");
      return 0;
    }
    while (index < path.size()
        && ArrivalGate.advanceWaypoint(
            p.distanceToSqr(foot(path.get(index))), p.onGround(), index == path.size() - 1, range))
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
    // Skip only collinear level cells; never skip a rise, corner or unchecked corridor.
    while (index + 1 < path.size()) {
      var a = path.get(index);
      var b = path.get(index + 1);
      var av = foot(a);
      var bv = foot(b);
      var dv = bv.subtract(p.position());
      if (Math.abs(av.y - bv.y) > .01
          || Math.abs(bv.y - p.getY()) > .15
          || dv.horizontalDistanceSqr() > 9) break;
      var axis = b.subtract(a);
      boolean aligned =
          axis.getX() == 0 ? Math.abs(p.getX() - av.x) < .14 : Math.abs(p.getZ() - av.z) < .14;
      if (!aligned || !stand(b) || !traversable(a, b)) break;
      index++;
    }
    BlockPos next = path.get(index);
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
    var corridor = body(p.position()).minmax(body(destination));
    int gate = passage.prepare(corridor);
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
    var steering =
        MovementSteering.toward(p.getYRot(), (float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
    p.setYRot(steering.yaw());
    p.setXRot(p.getXRot() + (10 - p.getXRot()) * .35f);
    boolean stepUp =
        destination.y > p.getY() + .35
            && destination.y <= p.getY() + 1.1
            && delta.horizontalDistanceSqr() < 1.85;
    PlayerControls.motionState(
        steering.forward(), jump.step(p.onGround(), stepUp && steering.forward(), ticks));
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
    if (water != null) {
      water.stop();
      water = null;
    }
    release();
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

  private static boolean hazard(BlockPos p) {
    if (!mc().level.hasChunkAt(p)) return true;
    var s = mc().level.getBlockState(p);
    return !s.getFluidState().isEmpty()
        || s.is(Blocks.FIRE)
        || s.is(Blocks.SOUL_FIRE)
        || s.is(Blocks.SWEET_BERRY_BUSH)
        || s.is(Blocks.POWDER_SNOW)
        || s.is(Blocks.MAGMA_BLOCK)
        || s.is(Blocks.CACTUS)
        || s.is(Blocks.CAMPFIRE)
        || s.is(Blocks.SOUL_CAMPFIRE);
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
    double y = surfaceY(cell);
    if (!Double.isFinite(y) || mc().player == null) return false;
    if (hazard(cell.below())
        || hazard(BlockPos.containing(cell.getX() + .5, y, cell.getZ() + .5))
        || hazard(
            BlockPos.containing(cell.getX() + .5, y + mc().player.getBbHeight(), cell.getZ() + .5)))
      return false;
    return NavigationPassage.clear(body(foot(cell)), planning);
  }

  public static boolean grounded() {
    var p = mc().player;
    if (p == null || !p.onGround()) return false;
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
  private static BlockPos standingCell() {
    var player = mc().player;
    if (player == null) return BlockPos.ZERO;
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
    Vec3 a = foot(from), b = foot(to);
    if (!ArrivalGate.transition(a.y, b.y)) return false;
    double high = Math.max(a.y, b.y);
    for (int i = 0; i <= 4; i++) {
      double t = i / 4.0;
      Vec3 v = new Vec3(a.x + (b.x - a.x) * t, high, a.z + (b.z - a.z) * t);
      if (!NavigationPassage.clear(body(v), true)) return false;
    }
    return true;
  }

  private boolean replan(BlockPos start) {
    release();
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
    return new GridSearch(
        cell(start),
        c -> goals.contains(pos(c)),
        c -> {
          BlockPos p = pos(c);
          List<GridSearch.Cell> next = new ArrayList<>();
          for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
            for (int dy : new int[] {0, 1, -1, 2, -2}) {
              BlockPos q = p.offset(d[0], dy, d[1]);
              if (q.distSqr(start) > 96 * 96 || blocked.contains(new Edge(p, q))) continue;
              if (searchStands.size() > 12000) searchStands.clear();
              if (searchStands.computeIfAbsent(q, Navigator::standPath) && traversable(p, q))
                next.add(cell(q));
            }
          return next;
        },
        c -> {
          BlockPos p = pos(c);
          return Math.sqrt(goals.stream().mapToDouble(p::distSqr).min().orElse(0));
        },
        20000);
  }

  private static GridSearch.Cell cell(BlockPos p) {
    return new GridSearch.Cell(p.getX(), p.getY(), p.getZ());
  }

  private static BlockPos pos(GridSearch.Cell p) {
    return new BlockPos(p.x(), p.y(), p.z());
  }
}
