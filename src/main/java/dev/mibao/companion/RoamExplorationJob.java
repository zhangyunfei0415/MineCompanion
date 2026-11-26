package dev.mibao.companion;

import static dev.mibao.companion.ExplorationGraph.*;
import static dev.mibao.companion.ExplorationMemory.*;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;

/**
 * Active outbound exploration engine ("explore_roam"): moves toward unvisited chunks/sectors using
 * Navigator runtime guardrails (hostiles, low HP, lava/fire avoidance, 5s stuck ladder + 5-min
 * negative cache) without requiring a pre-verified return-graph route on outbound steps.
 */
public final class RoamExplorationJob extends Actions.Job {
  private static final Map<Long, Long> NEGATIVE_SECTORS = new HashMap<>();
  private static final Map<BlockPos, Long> FAILED_TARGETS = new HashMap<>();
  private static final long[] NEGATIVE_HEADINGS = new long[8];
  private static final Set<Long> SESSION_SEEN_CHUNKS = new HashSet<>();
  private static final Set<Cell> SESSION_SEEN_CELLS = new HashSet<>();
  private static String cacheScopeDim = "";
  private static final int ESCAPE_MAX_TICKS = 100, RETREAT_MAX_TICKS = 100;

  private record CorridorPick(BlockPos pos, boolean unvisited, double score) {}

  private final Navigator nav = new Navigator();
  private final int maxDistance, preferredHeading;
  private final long startedMs, deadlineMs, turnBackAtMs;
  private final boolean returnAfter, guardHostiles;
  private final Vec3 startPos;
  private final Set<Long> chunksVisitedThisRun = new HashSet<>();
  private final Set<Cell> visitedThisRun = new HashSet<>();
  private Vec3 lastProgressPos, commitStartPos;
  private BlockPos activeWaypoint, lastSafePos;
  private Cell lastCell;
  private int currentHeading = -1;
  private long committedSinceMs;
  private int stuckTicks,
      totalStuckTicks,
      stuckLevel,
      consecutiveHeadingFailures,
      stepsWalked,
      newCells,
      newChunks;
  private boolean returning, localMode;
  private boolean retreating;
  private int escapeTicks, retreatTicks;
  private String finishNote = "";

  static void start(JsonObject args, Consumer<String> done) {
    if (DutyScheduler.ownsCurrentJob()) {
      DutyScheduler.yieldCurrentJob("显式探图指令抢占：explore_roam");
    }
    if (Actions.job != null) throw new IllegalArgumentException("[BUSY] 已有连续作业");
    if (graph() == null) throw new IllegalArgumentException("[MAP_LOADING] 探索记忆未就绪");
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null)
      throw new IllegalArgumentException("[WRONG_CONTEXT] 世界未就绪");
    if (!Navigator.grounded())
      throw new IllegalArgumentException("[MAP_NO_STANCE] 先回到稳定落脚点再开始探路");
    String denial = PermissionPolicy.explorationDenial(Agent.str(args, "policy", POLICY));
    if (!denial.isEmpty()) throw new IllegalArgumentException(denial);
    Actions.job = new RoamExplorationJob(args, done);
  }

  private RoamExplorationJob(JsonObject args, Consumer<String> done) {
    super(done);
    var mc = Minecraft.getInstance();
    String key = Store.scope() + "|" + mc.level.dimension().location();
    if (!key.equals(cacheScopeDim)) {
      cacheScopeDim = key;
      NEGATIVE_SECTORS.clear();
      FAILED_TARGETS.clear();
      Arrays.fill(NEGATIVE_HEADINGS, 0L);
      SESSION_SEEN_CHUNKS.clear();
      SESSION_SEEN_CELLS.clear();
      var existing = graph();
      if (existing != null) {
        SESSION_SEEN_CHUNKS.addAll(existing.chunkVisitCounts().keySet());
        for (Node n : existing.nodes()) {
          if (n.kind() != Kind.FRONTIER) SESSION_SEEN_CELLS.add(n.cell());
        }
        for (Edge e : existing.edges()) {
          if (e.kind() == EdgeKind.BIDIRECTIONAL) SESSION_SEEN_CELLS.addAll(e.cells());
        }
      }
    }
    int maxSec = Rules.bounded(Agent.number(args, "max_seconds", 60), 10, 300);
    this.maxDistance = Rules.bounded(Agent.number(args, "max_distance", 96), 12, 256);
    this.returnAfter = args.has("return_after") && args.get("return_after").getAsBoolean();
    this.guardHostiles =
        !args.has("guard_hostiles") || args.get("guard_hostiles").getAsBoolean();
    this.startedMs = System.currentTimeMillis();
    this.deadlineMs = startedMs + maxSec * 1000L;
    this.turnBackAtMs = startedMs + (maxSec * 1000L) / 2;
    this.startPos = mc.player.position();
    this.lastProgressPos = startPos;
    this.commitStartPos = startPos;
    this.lastSafePos = mc.player.blockPosition();
    this.lastCell = current();
    if (this.lastCell != null) {
      this.visitedThisRun.add(this.lastCell);
      SESSION_SEEN_CELLS.add(this.lastCell);
    }
    long initialChunk =
        ExplorationRoamPolicy.chunkKey(lastSafePos.getX(), lastSafePos.getZ());
    SESSION_SEEN_CHUNKS.add(initialChunk);
    this.chunksVisitedThisRun.add(initialChunk);
    String rawPref = Agent.str(args, "prefer_heading", "auto");
    this.preferredHeading = parseExplicitHeading(rawPref);
    this.currentHeading =
        this.preferredHeading >= 0 ? this.preferredHeading : parsePreferredHeading(mc, rawPref);
    this.committedSinceMs = startedMs;
  }

  private static int parseExplicitHeading(String pref) {
    if (pref == null || pref.isBlank() || pref.equalsIgnoreCase("auto")) return -1;
    return switch (pref.strip().toLowerCase(Locale.ROOT)) {
      case "north", "n" -> 0;
      case "northeast", "ne" -> 1;
      case "east", "e" -> 2;
      case "southeast", "se" -> 3;
      case "south", "s" -> 4;
      case "southwest", "sw" -> 5;
      case "west", "w" -> 6;
      case "northwest", "nw" -> 7;
      default -> -1;
    };
  }

  private static int parsePreferredHeading(Minecraft mc, String pref) {
    int explicit = parseExplicitHeading(pref);
    if (explicit >= 0) return explicit;
    double rad = Math.toRadians(mc.player.getYRot());
    return ExplorationRoamPolicy.headingIndexFromVector(-Math.sin(rad), Math.cos(rad));
  }

  @Override
  void tick() {
    ticks++;
    var mc = Minecraft.getInstance();
    long nowMs = System.currentTimeMillis();
    if (mc.player == null || mc.level == null) {
      finishProgress("世界未就绪");
      return;
    }
    if (mc.screen != null
        || mc.player.containerMenu != mc.player.inventoryMenu
        || mc.player.isPassenger()
        || mc.player.getAbilities().flying) {
      finishProgress("界面或角色状态变化，停止探图");
      return;
    }
    if (escapeTicks > 0 || mc.player.isInLava() || mc.player.isOnFire()) {
      BlockPos feetNow = mc.player.blockPosition();
      boolean inHazard =
          mc.player.isInLava()
              || Navigator.isLavaOrFire(feetNow)
              || Navigator.isLavaOrFire(feetNow.below());
      if (escapeTicks == 0) {
        nav.stop();
        retreating = false;
        activeWaypoint = null;
        if (!inHazard) {
          finishProgress("检测到岩浆/火焰边缘，停止当前探测");
          return;
        }
        BlockPos escape = findLocalExit(feetNow, 6, 128, -1);
        if (escape == null && !lastSafePos.equals(feetNow)) escape = lastSafePos;
        if (escape != null && nav.startLocal(escape)) activeWaypoint = escape;
      }
      escapeTicks++;
      Actions.status = "主动探图 · 正在脱离岩浆/火焰";
      int r = activeWaypoint == null ? -1 : nav.tick();
      if (r != 0) activeWaypoint = null;
      if (!inHazard && r != 0 || escapeTicks >= ESCAPE_MAX_TICKS) {
        finishProgress(
            inHazard ? "脱离岩浆/火焰超时，停止当前探测" : "已脱离岩浆/火焰边缘，停止当前探测");
        return;
      }
      if (r < 0 && inHazard) {
        // No usable exit route: keep pushing back toward the last safe cell (or opposite heading).
        double dx, dz;
        if (!lastSafePos.equals(feetNow)) {
          dx = lastSafePos.getX() + .5 - mc.player.getX();
          dz = lastSafePos.getZ() + .5 - mc.player.getZ();
        } else if (currentHeading >= 0 && currentHeading < 8) {
          dx = -ExplorationRoamPolicy.HEADING_DX[currentHeading];
          dz = -ExplorationRoamPolicy.HEADING_DZ[currentHeading];
        } else {
          dx = 0;
          dz = 0;
        }
        if (dx != 0 || dz != 0) mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        PlayerControls.motionState(true, true);
      }
      return;
    }
    if (nowMs >= deadlineMs) {
      finishProgress("达到探图时间预算");
      return;
    }

    // Track real movement, newly walked cells & new chunks (independent of ExplorationMemory auto-recording)
    BlockPos feet = Navigator.standingCell();
    if (Navigator.stand(feet) && !Navigator.isNarrowHazardAdjacent(feet)) {
      lastSafePos = feet;
    }
    Cell here = current();
    if (here != null && !here.equals(lastCell)) {
      stepsWalked++;
      visitedThisRun.add(here);
      if (SESSION_SEEN_CELLS.size() < 16384 && SESSION_SEEN_CELLS.add(here)) newCells++;
      lastCell = here;
    }
    long cKey = ExplorationRoamPolicy.chunkKey(feet.getX(), feet.getZ());
    chunksVisitedThisRun.add(cKey);
    if (SESSION_SEEN_CHUNKS.size() < 4096 && SESSION_SEEN_CHUNKS.add(cKey)) {
      newChunks++;
    }

    // Runtime hostile guardrail
    if (guardHostiles && ticks % 5 == 0) {
      boolean allowHunt =
          Store.config.fullGameControl
              || "FULL".equals(Store.config.permission)
              || Store.config.allowHunt;
      var threats =
          mc.level.getEntitiesOfClass(
              LivingEntity.class,
              mc.player.getBoundingBox().inflate(8.0),
              e ->
                  e != mc.player
                      && e.isAlive()
                      && e instanceof Monster
                      && !(e instanceof WitherBoss)
                      && !(e instanceof EnderDragon)
                      && mc.player.hasLineOfSight(e));
      if (!threats.isEmpty() && (!allowHunt || threats.size() >= 2 || mc.player.getHealth() < 12.0f)) {
        LivingEntity threat = threats.get(0);
        int threatHeading =
            ExplorationRoamPolicy.headingIndexFromVector(
                threat.getX() - mc.player.getX(), threat.getZ() - mc.player.getZ());
        NEGATIVE_HEADINGS[threatHeading] = nowMs + 60_000L;
        NEGATIVE_SECTORS.put(
            ExplorationRoamPolicy.sectorKey(
                threat.blockPosition().getX(), threat.blockPosition().getZ()),
            nowMs + 60_000L);
        if (!returning && returnAfter) {
          nav.stop();
          activeWaypoint = null;
          retreating = false;
          returning = true;
        } else if (!retreating
            || activeWaypoint == null
            || activeWaypoint.distSqr(threat.blockPosition()) <= feet.distSqr(threat.blockPosition())) {
          // Re-plan only when no retreat is active or the current one no longer leads away.
          nav.stop();
          activeWaypoint = null;
          retreating = false;
          int awayHeading = (threatHeading + 4) % 8;
          currentHeading = awayHeading;
          committedSinceMs = nowMs;
          commitStartPos = mc.player.position();
          BlockPos retreat = findLocalExit(feet, 10, 256, awayHeading);
          if (retreat != null && nav.startLocal(retreat)) {
            activeWaypoint = retreat;
            retreating = true;
            retreatTicks = 0;
          } else {
            finishProgress("前方发现敌对威胁，安全停步");
            return;
          }
        }
      }
    }

    if (retreating) {
      Actions.status = "主动探图 · 避开敌对威胁撤退中";
      int r = activeWaypoint == null ? -1 : nav.tick();
      if (r == 0 && ++retreatTicks < RETREAT_MAX_TICKS) return;
      if (r == 0) nav.stop();
      activeWaypoint = null;
      retreating = false;
    }

    if (returnAfter && !returning && (nowMs >= turnBackAtMs || stepsWalked >= maxDistance)) {
      returning = true;
      nav.stop();
      activeWaypoint = null;
    }

    if (returning) {
      Actions.status = "主动探图 · 沿记录安全回程（已行 " + stepsWalked + " 格）";
      if (startPos.distanceToSqr(mc.player.position()) <= 9.0) {
        finishProgress("探图并已安全回到出发点");
        return;
      }
      if (activeWaypoint == null) {
        activeWaypoint = BlockPos.containing(startPos);
        if (!nav.startLocal(activeWaypoint)) {
          finishProgress("探图结束（回程请用 explore_return）");
          return;
        }
      }
      int r = nav.tick();
      if (r != 0) {
        activeWaypoint = null;
        finishProgress(r > 0 ? "探图并已安全返回" : "探图结束，已记录回程节点");
      }
      return;
    }

    Actions.status =
        "主动探图开荒 · +"
            + newChunks
            + " 区块 / "
            + stepsWalked
            + " 步"
            + (stuckLevel > 0 ? "（脱困L" + stuckLevel + "）" : "");

    // Stuck detection: < 1.0 block displacement; only count toward stuck_ms after normal step grace
    if (mc.player.position().distanceToSqr(lastProgressPos) < 1.0) {
      stuckTicks++;
      if (ExplorationRoamPolicy.isStuckTick(stuckTicks)) totalStuckTicks++;
    } else {
      stuckTicks = 0;
      stuckLevel = 0;
      lastProgressPos = mc.player.position();
    }

    if (stuckTicks >= ExplorationRoamPolicy.STUCK_WINDOW_TICKS) {
      if (activeWaypoint != null) markTargetFailed(activeWaypoint, nowMs);
      nav.stop();
      activeWaypoint = null;
      stuckTicks = 0;
      stuckLevel++;
      if (stuckLevel == 1) {
        int leftSide = currentHeading >= 0 ? (currentHeading + 6) % 8 : 0;
        int rightSide = currentHeading >= 0 ? (currentHeading + 2) % 8 : 4;
        CorridorPick leftPick =
            findCorridorTarget(mc, feet, 14, 512, leftSide, preferredHeading, nowMs);
        CorridorPick rightPick =
            findCorridorTarget(mc, feet, 14, 512, rightSide, preferredHeading, nowMs);
        CorridorPick chosen =
            leftPick != null && (rightPick == null || leftPick.score() >= rightPick.score())
                ? leftPick
                : rightPick;
        if (chosen != null && startNav(chosen.pos())) return;
        CorridorPick fallback =
            findCorridorTarget(mc, feet, 14, 512, currentHeading, preferredHeading, nowMs);
        if (fallback != null && startNav(fallback.pos())) return;
      }
      if (currentHeading >= 0 && currentHeading < 8 && stuckLevel == 2) {
        NEGATIVE_HEADINGS[currentHeading] = nowMs + 60_000L;
      }
      if (currentHeading >= 0 && currentHeading < 8 && stuckLevel >= 3) {
        NEGATIVE_HEADINGS[currentHeading] = nowMs + ExplorationRoamPolicy.NEGATIVE_CACHE_MS;
        NEGATIVE_SECTORS.put(
            ExplorationRoamPolicy.sectorKey(feet.getX(), feet.getZ()),
            nowMs + ExplorationRoamPolicy.NEGATIVE_CACHE_MS);
      }
      if (stuckLevel >= 4) {
        if (returnAfter) {
          returning = true;
          return;
        }
        finishProgress("四周路线均暂时受阻，已触发 5 分钟负缓存");
        return;
      }
      // Force pick a fresh heading with side-turn preference instead of U-turn
      int nextHeading = pickBestHeading(mc, nowMs, false);
      if (nextHeading >= 0) currentHeading = nextHeading;
      committedSinceMs = nowMs;
      commitStartPos = mc.player.position();
    }

    if (activeWaypoint != null) {
      int r = nav.tick();
      if (r == 0) return;
      if (r > 0) {
        consecutiveHeadingFailures = 0;
      } else {
        markTargetFailed(activeWaypoint, nowMs);
        if (++consecutiveHeadingFailures >= 2 && currentHeading >= 0 && currentHeading < 8) {
          NEGATIVE_HEADINGS[currentHeading] = nowMs + 45_000L;
        }
      }
      nav.stop();
      activeWaypoint = null;
    }

    if (stepsWalked >= maxDistance) {
      if (returnAfter) {
        returning = true;
        return;
      }
      finishProgress("达到单次探图里程上限");
      return;
    }

    // Choose next outbound waypoint without requiring a pre-verified graph route
    BlockPos target = chooseNextWaypoint(mc, feet, nowMs);
    if (target == null) {
      if (currentHeading >= 0 && currentHeading < 8) {
        NEGATIVE_HEADINGS[currentHeading] = nowMs + ExplorationRoamPolicy.NEGATIVE_CACHE_MS;
      }
      currentHeading = pickBestHeading(mc, nowMs, false);
      committedSinceMs = nowMs;
      commitStartPos = mc.player.position();
      target = chooseNextWaypoint(mc, feet, nowMs);
      if (target == null) {
        finishProgress("附近未找到安全可探的未走目标");
        return;
      }
    }
    if (!startNav(target)) {
      markTargetFailed(target, nowMs);
      if (++consecutiveHeadingFailures >= 2 && currentHeading >= 0 && currentHeading < 8) {
        NEGATIVE_HEADINGS[currentHeading] = nowMs + 45_000L;
      }
    }
  }

  private boolean startNav(BlockPos target) {
    if (!nav.startLocal(target)) return false;
    activeWaypoint = target;
    return true;
  }

  private static boolean isTargetFailed(BlockPos pos, long nowMs) {
    if (pos == null) return false;
    Long until = FAILED_TARGETS.get(pos);
    if (until == null) return false;
    if (nowMs >= until) {
      FAILED_TARGETS.remove(pos);
      return false;
    }
    return true;
  }

  private static void markTargetFailed(BlockPos pos, long nowMs) {
    if (pos == null) return;
    if (FAILED_TARGETS.size() > 512) {
      FAILED_TARGETS.entrySet().removeIf(e -> nowMs >= e.getValue());
    }
    FAILED_TARGETS.put(pos.immutable(), nowMs + ExplorationRoamPolicy.NEGATIVE_CACHE_MS);
  }

  private int pickBestHeading(Minecraft mc, long nowMs, boolean allowCommit) {
    Map<Long, Integer> visits =
        graph() == null ? Map.of() : graph().sectorVisitCounts();
    BlockPos feet = mc.player.blockPosition();
    int[] sectorVisits = new int[8];
    for (int h = 0; h < 8; h++) {
      int sx = feet.getX() + ExplorationRoamPolicy.HEADING_DX[h] * 16;
      int sz = feet.getZ() + ExplorationRoamPolicy.HEADING_DZ[h] * 16;
      long sk = ExplorationRoamPolicy.sectorKey(sx, sz);
      int v = visits.getOrDefault(sk, 0);
      if (NEGATIVE_SECTORS.getOrDefault(sk, 0L) > nowMs) v += 500;
      sectorVisits[h] = v;
    }
    double displaced =
        commitStartPos == null ? 0.0 : mc.player.position().distanceTo(commitStartPos);
    return ExplorationRoamPolicy.pickHeadingGuided(
        currentHeading,
        preferredHeading,
        sectorVisits,
        NEGATIVE_HEADINGS,
        nowMs,
        allowCommit,
        committedSinceMs,
        displaced);
  }

  private BlockPos chooseNextWaypoint(Minecraft mc, BlockPos feet, long nowMs) {
    int chosen = pickBestHeading(mc, nowMs, true);
    if (chosen < 0) return null;
    if (chosen != currentHeading) {
      currentHeading = chosen;
      committedSinceMs = nowMs;
      commitStartPos = mc.player.position();
    }

    // 1. Open-plains fast path: only project far ahead when near ground ahead is genuinely open.
    BlockPos openAhead = tryOpenForwardProjection(mc, feet, currentHeading, nowMs);
    if (openAhead != null) return openAhead;

    // 2. Indoor / maze traversable corridor BFS (guarantees physical passability around walls & doors).
    CorridorPick corridor =
        findCorridorTarget(mc, feet, 16, 768, currentHeading, preferredHeading, nowMs);

    // 3. Score nearby frontier nodes with heading alignment and visit history (never hijack backwards).
    var g = graph();
    BlockPos bestFrontier = null;
    double bestFrontierScore = Double.MAX_VALUE;
    double bestFrontierAlign = -1.0;
    if (g != null) {
      Map<Long, Integer> chunkVisits = g.chunkVisitCounts();
      for (Node n : g.nodes()) {
        if (n.kind() != Kind.FRONTIER) continue;
        BlockPos fp = ExplorationMemory.pos(n.cell());
        if (isTargetFailed(fp, nowMs)) continue;
        double dSq = fp.distSqr(feet);
        double dyAbs = Math.abs(fp.getY() - feet.getY());
        boolean verticalConnector = dyAbs >= 1.0 && ExplorationMemory.isVerticalShaftCandidate(fp);
        if ((!verticalConnector && dSq < 12.0) || dSq < 1.0 || dSq > 44 * 44) continue;
        long sk = ExplorationRoamPolicy.sectorKey(fp.getX(), fp.getZ());
        if (NEGATIVE_SECTORS.getOrDefault(sk, 0L) > nowMs) continue;
        if (!mc.level.hasChunkAt(fp) || !Navigator.stand(fp) || Navigator.isNarrowHazardAdjacent(fp))
          continue;
        long ck = ExplorationRoamPolicy.chunkKey(fp.getX(), fp.getZ());
        double dx = fp.getX() - feet.getX();
        double dy = fp.getY() - feet.getY();
        double dz = fp.getZ() - feet.getZ();
        double score =
            ExplorationRoamPolicy.scoreFrontier3d(
                dx,
                dy,
                dz,
                currentHeading,
                preferredHeading,
                chunkVisits.getOrDefault(ck, 0),
                SESSION_SEEN_CELLS.contains(n.cell()),
                visitedThisRun.contains(n.cell()),
                verticalConnector);
        if (score < bestFrontierScore) {
          bestFrontierScore = score;
          bestFrontier = fp;
          bestFrontierAlign =
              verticalConnector && dx == 0 && dz == 0
                  ? 0.0
                  : ExplorationRoamPolicy.headingAlignment(dx, dz, currentHeading);
        }
      }
    }

    if (corridor != null && corridor.unvisited()) return corridor.pos();
    if (bestFrontier != null && bestFrontierAlign >= -0.1) return bestFrontier;
    if (corridor != null) return corridor.pos();
    return bestFrontier;
  }

  private BlockPos tryOpenForwardProjection(
      Minecraft mc, BlockPos feet, int heading, long nowMs) {
    if (heading < 0 || heading >= 8) return null;
    int dx = ExplorationRoamPolicy.HEADING_DX[heading];
    int dz = ExplorationRoamPolicy.HEADING_DZ[heading];
    for (int step : new int[] {2, 4, 6}) {
      BlockPos near = ColumnProbe.nearest(feet.getX() + dx * step, feet.getZ() + dz * step, feet.getY(), 2);
      if (near == null
          || Math.abs(near.getY() - feet.getY()) > 1
          || !Navigator.stand(near)
          || Navigator.isNarrowHazardAdjacent(near)) {
        return null;
      }
    }
    for (int dist : new int[] {18, 14, 10}) {
      for (int lateral : new int[] {0, 3, -3}) {
        int px = feet.getX() + dx * dist + (dz != 0 ? lateral : 0);
        int pz = feet.getZ() + dz * dist + (dx != 0 ? lateral : 0);
        BlockPos probe = ColumnProbe.nearest(px, pz, feet.getY(), 5);
        if (probe == null || isTargetFailed(probe, nowMs)) continue;
        if (Math.abs(probe.getY() - feet.getY()) > 4) continue;
        if (!Navigator.stand(probe) || Navigator.isNarrowHazardAdjacent(probe)) continue;
        if (visitedThisRun.contains(ExplorationMemory.cell(probe))) continue;
        return probe;
      }
    }
    return null;
  }

  private CorridorPick findCorridorTarget(
      Minecraft mc,
      BlockPos origin,
      int maxDist,
      int maxExpand,
      int heading,
      int prefHeading,
      long nowMs) {
    if (mc.level == null) return null;
    var g = graph();
    Map<Long, Integer> chunkVisits = g == null ? Map.of() : g.chunkVisitCounts();
    Queue<BlockPos> queue = new ArrayDeque<>();
    Map<BlockPos, Integer> distMap = new HashMap<>();
    queue.add(origin);
    distMap.put(origin, 0);
    CorridorPick bestDeep = null;
    CorridorPick bestNear = null;
    int maxDistSq = maxDist * maxDist;
    int expanded = 0;

    while (!queue.isEmpty() && ++expanded <= maxExpand) {
      BlockPos cur = queue.remove();
      int curSteps = distMap.getOrDefault(cur, 0);
      int openNeighbors = 0;
      for (int[] d : ExplorationMemory.DIRECTIONS) {
        for (int dy : new int[] {0, 1, -1}) {
          BlockPos next = cur.offset(d[0], dy, d[1]);
          if (next.distSqr(origin) > maxDistSq || !mc.level.hasChunkAt(next)) continue;
          if (!Navigator.openExplorationStep(cur, next) || Navigator.isNarrowHazardAdjacent(next))
            continue;
          openNeighbors++;
          if (!distMap.containsKey(next)) {
            distMap.put(next, curSteps + 1);
            queue.add(next);
          }
          break;
        }
      }
      if (!cur.equals(origin) && curSteps >= 2 && !isTargetFailed(cur, nowMs)) {
        Cell curCell = ExplorationMemory.cell(cur);
        boolean walkedBefore = SESSION_SEEN_CELLS.contains(curCell);
        boolean seenRun = visitedThisRun.contains(curCell);
        int cVisits =
            chunkVisits.getOrDefault(ExplorationRoamPolicy.chunkKey(cur.getX(), cur.getZ()), 0);
        double score =
            ExplorationRoamPolicy.scoreCorridorCell(
                cur.getX() - origin.getX(),
                cur.getZ() - origin.getZ(),
                curSteps,
                openNeighbors,
                heading,
                prefHeading,
                cVisits,
                walkedBefore,
                seenRun);
        CorridorPick pick = new CorridorPick(cur, !walkedBefore, score);
        if (curSteps >= 4) {
          if (bestDeep == null || pick.score() > bestDeep.score()) bestDeep = pick;
        } else if (bestNear == null || pick.score() > bestNear.score()) {
          bestNear = pick;
        }
      }
    }
    return bestDeep != null ? bestDeep : bestNear;
  }

  /**
   * Bounded BFS used by lava escape and hostile retreat to find a physically reachable exit cell
   * while strictly avoiding cells adjacent to lava or fire.
   */
  private BlockPos findLocalExit(BlockPos origin, int maxDist, int maxExpand, int biasHeading) {
    var mc = Minecraft.getInstance();
    if (mc.level == null) return null;
    Queue<BlockPos> queue = new ArrayDeque<>();
    Set<BlockPos> seen = new HashSet<>();
    queue.add(origin);
    seen.add(origin);
    BlockPos best = null;
    double bestScore = -Double.MAX_VALUE;
    int maxDistSq = maxDist * maxDist;
    int expanded = 0;
    double bx = biasHeading >= 0 && biasHeading < 8 ? ExplorationRoamPolicy.HEADING_DX[biasHeading] : 0;
    double bz = biasHeading >= 0 && biasHeading < 8 ? ExplorationRoamPolicy.HEADING_DZ[biasHeading] : 0;

    while (!queue.isEmpty() && ++expanded <= maxExpand) {
      BlockPos cur = queue.remove();
      boolean curInHazard = cur.equals(origin) && !Navigator.stand(origin);
      int openNeighbors = 0;
      for (int[] d : ExplorationMemory.DIRECTIONS) {
        for (int dy : new int[] {0, 1, -1}) {
          BlockPos next = cur.offset(d[0], dy, d[1]);
          if (next.distSqr(origin) > maxDistSq || !mc.level.hasChunkAt(next)) continue;
          boolean stepOk =
              curInHazard
                  ? Navigator.stand(next) && !Navigator.isNarrowHazardAdjacent(next)
                  : Navigator.openExplorationStep(cur, next)
                      && !Navigator.isNarrowHazardAdjacent(next);
          if (!stepOk) continue;
          openNeighbors++;
          if (seen.add(next)) queue.add(next);
          break;
        }
      }
      if (!cur.equals(origin) && cur.distSqr(origin) >= 9.0) {
        double d = Math.sqrt(cur.distSqr(origin));
        double align =
            bx == 0 && bz == 0
                ? 0.0
                : ((cur.getX() - origin.getX()) * bx + (cur.getZ() - origin.getZ()) * bz)
                    / Math.max(1.0, d);
        double score = openNeighbors * 3.0 + d + align * 4.0;
        if (score > bestScore) {
          bestScore = score;
          best = cur;
        }
      }
    }
    return best;
  }

  private void finishProgress(String note) {
    var mc = Minecraft.getInstance();
    long elapsed = Math.max(1L, System.currentTimeMillis() - startedMs);
    long stuckMs = totalStuckTicks * 50L;
    double netDisp = mc.player == null ? 0.0 : mc.player.position().distanceTo(startPos);
    var p =
        new ExplorationRoamPolicy.Progress(
            stepsWalked, netDisp, newCells, newChunks, elapsed, stuckMs);
    var json = new JsonObject();
    json.addProperty("steps_walked", p.stepsWalked());
    json.addProperty("net_displacement", Math.round(p.netDisplacement() * 100.0) / 100.0);
    json.addProperty("new_cells", p.newCells());
    json.addProperty("new_chunks", p.newChunks());
    json.addProperty("chunks_per_minute", Math.round(p.chunksPerMinute() * 100.0) / 100.0);
    json.addProperty("stuck_ms", p.stuckMs());
    json.addProperty("elapsed_ms", p.elapsedMs());
    if (mc.player != null) {
      BlockPos pos = mc.player.blockPosition();
      json.addProperty("current_pos", pos.getX() + "," + pos.getY() + "," + pos.getZ());
    }
    if (!p.madeProgress()) {
      finish("失败：[EXPLORE_NO_PROGRESS] " + note + "（0 步无有效前进）；" + json);
    } else {
      finish(
          "成功：主动探图完成（"
              + note
              + "）：行走 "
              + p.stepsWalked()
              + " 步，净位移 "
              + json.get("net_displacement").getAsDouble()
              + " 格，探索新格 "
              + p.newCells()
              + "、新区块 "
              + p.newChunks()
              + "；"
              + json);
    }
  }

  @Override
  void stop() {
    nav.stop();
    Navigator.release();
  }
}
