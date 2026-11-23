package dev.mibao.companion;

import static dev.mibao.companion.ExplorationGraph.*;
import static dev.mibao.companion.ExplorationMemory.*;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/** Follows checked cells through the local movement lane. Never delegates to dig/build navigation. */
final class ExplorationJob extends Actions.Job {
  private final ExplorationGraph map;
  private final Navigator navigator = new Navigator();
  private final Set<Cell> goals;
  private final Set<Cell> visited = new HashSet<>();
  private final Deque<Step> route = new ArrayDeque<>();
  private final int maxSteps;
  private final long deadline, turnBackAt;
  private final boolean branch, stopOnJunction, returnAfter, guidePlayer;
  private final String guidePlayerName, targetLabel;
  private final Cell start;
  private Cell opening, target, from;
  private Step routeStep;
  private int steps, reroutes, lastDx, lastDz, guideWaitTicks, arrivalWaitTicks;
  private long lastGuideNotifyAt;
  private boolean returning, retreating, directFallbackActive, regrouping;
  private String blocked = "";

  static void start(String tool, JsonObject args, Consumer<String> done) {
    if (DutyScheduler.ownsCurrentJob()) {
      DutyScheduler.yieldCurrentJob("显式探索指令抢占：" + tool);
    }
    if (Actions.job != null) throw new IllegalArgumentException("[BUSY] 已有连续作业");
    if (graph() == null) throw new IllegalArgumentException("[MAP_LOADING] 探索记忆未就绪");
    if (current() == null) throw new IllegalArgumentException("[MAP_NO_STANCE] 先回到有支撑的站位");
    String denial = PermissionPolicy.explorationDenial(Agent.str(args, "policy", POLICY));
    if (!denial.isEmpty()) throw new IllegalArgumentException(denial);
    Actions.job = new ExplorationJob(tool, args, done);
  }

  private ExplorationJob(String tool, JsonObject args, Consumer<String> done) {
    super(done);
    map = graph();
    start = current();
    visited.add(start);
    branch = tool.equals("explore_branch");
    returning = !branch;
    guidePlayer = !branch && bool(args, "guide_player", false);
    guidePlayerName = Agent.str(args, "player_name", "");
    stopOnJunction = bool(args, "stop_on_junction", true);
    returnAfter = branch && bool(args, "return_after", false);
    maxSteps = Rules.bounded(Agent.number(args, "max_distance", branch ? 40 : 256), 1, branch ? 96 : 512);
    long duration = 1_000_000_000L *
        Rules.bounded(Agent.number(args, "max_seconds", branch ? 45 : guidePlayer ? 240 : 180), 1, branch ? 120 : 360);
    long begun = System.nanoTime();
    deadline = begun + duration;
    turnBackAt = begun + duration / 2;
    revalidateLocalEdges(start, 8, now());
    if (!branch) {
      targetLabel = Agent.str(args, "target", "nearest_entry");
      goals = goals(targetLabel, false);
      if (goals.isEmpty()) throw new IllegalArgumentException("[MAP_UNKNOWN_NODE] 没有可选的返回目标");
      return;
    }
    targetLabel = "";
    String id = Agent.str(args, "frontier_id", "");
    if (!id.isBlank()) {
      var options = goals(id, true);
      if (options.size() != 1)
        throw new IllegalArgumentException("[MAP_UNKNOWN_NODE] 未探岔口不存在或已探索，请刷新地图");
      opening = options.iterator().next();
      Set<Cell> anchors = map.adjacentObservedAnchors(opening);
      if (anchors.isEmpty()) {
        anchors = new LinkedHashSet<>();
        for (Cell adj : adjacent(opening)) {
          if (map.walked(adj)) anchors.add(adj);
        }
        if (anchors.isEmpty()) anchors.add(opening);
      }
      goals = anchors;
    } else {
      goals = Set.of();
      opening = direction(args);
    }
  }

  private static boolean bool(JsonObject args, String key, boolean fallback) {
    return args.has(key) ? args.get(key).getAsBoolean() : fallback;
  }

  private Cell direction(JsonObject args) {
    String dir = Agent.str(args, "direction", "forward").toLowerCase(Locale.ROOT);
    var player = Minecraft.getInstance().player;
    int heading = switch (dir) {
      case "south" -> 0;
      case "west" -> 1;
      case "north" -> 2;
      case "east" -> 3;
      case "forward", "back", "left", "right" -> Math.floorMod(Math.round(player.getYRot() / 90)
          + switch (dir) { case "left" -> -1; case "right" -> 1; case "back" -> 2; default -> 0; }, 4);
      default -> throw new IllegalArgumentException("direction=forward/back/left/right/north/south/east/west");
    };
    int dx = heading == 1 ? -1 : heading == 3 ? 1 : 0;
    int dz = heading == 0 ? 1 : heading == 2 ? -1 : 0;
    List<Cell> open = adjacent(start);
    if (open.isEmpty()) {
      revalidateLocalEdges(start, 6, now());
      throw new IllegalArgumentException("[MAP_NO_OPENING] 当前位置周围没有可通行的相邻开口");
    }
    return open.stream()
        .filter(c -> c.x() == start.x() + dx && c.z() == start.z() + dz)
        .findFirst()
        .orElseGet(
            () ->
                open.stream()
                    .max(
                        Comparator.comparingInt(
                            c -> (c.x() - start.x()) * dx + (c.z() - start.z()) * dz))
                    .orElse(open.get(0)));
  }

  @Override void tick() {
    if (System.nanoTime() - deadline >= 0) {
      fail("TIMEOUT", "探索等待超过时间预算");
      return;
    }
    var mc = Minecraft.getInstance();
    if (graph() != map || mc.screen != null || mc.player.isPassenger()
        || mc.player.isInWater() || mc.player.getAbilities().flying) {
      fail("MAP_INTERRUPTED", "界面、世界或站位已改变");
      return;
    }

    if (guidePlayer && !branch) {
      Player escorted = OwnerContext.findPlayer(mc, guidePlayerName);
      double pDist = escorted == null ? Double.MAX_VALUE : mc.player.distanceTo(escorted);
      boolean los = escorted != null && mc.player.hasLineOfSight(escorted);
      var ga =
          ExplorationRoamPolicy.guideAction(
              escorted != null && escorted.isAlive(), pDist, los, guideWaitTicks);
      if (ga == ExplorationRoamPolicy.GuideAction.WAIT_FACE_PLAYER
          || ga == ExplorationRoamPolicy.GuideAction.TARGET_LOST) {
        if (target != null || regrouping) {
          navigator.stop();
          target = null;
          routeStep = null;
          regrouping = false;
        }
        Navigator.release();
        if (escorted != null) Actions.look(escorted.getEyePosition());
        guideWaitTicks++;
        String pName = escorted == null ? (guidePlayerName.isBlank() ? "主人" : guidePlayerName) : escorted.getGameProfile().getName();
        Actions.status =
            escorted == null
                ? "带玩家回家 · 暂未看到 " + pName
                : "带玩家回家 · 等 " + pName + " 跟上（" + Math.round(pDist) + "格）";
        long ms = System.currentTimeMillis();
        if (ms - lastGuideNotifyAt >= 12_000L) {
          lastGuideNotifyAt = ms;
          Companion.notifyUser(
              escorted == null
                  ? "我先停下来等你，靠近一点我再继续带路回家~"
                  : "前面拐弯啦，我停在这里等你跟上（当前距离 " + Math.round(pDist) + " 格）~");
        }
        return;
      }
      if (ga == ExplorationRoamPolicy.GuideAction.REGROUP_STEP_BACK && escorted != null) {
        Actions.status = "带玩家回家 · 回头接应 " + escorted.getGameProfile().getName();
        if (!regrouping) {
          if (navigator.startLocal(escorted.blockPosition())) {
            regrouping = true;
            target = null;
          } else {
            Navigator.release();
            Actions.look(escorted.getEyePosition());
          }
        } else {
          int gr = navigator.tick();
          if (gr != 0) regrouping = false;
        }
        return;
      }
      regrouping = false;
      guideWaitTicks = 0;
    }

    if (directFallbackActive) {
      int r = navigator.tick();
      Actions.status = guidePlayer ? "带玩家回家 · 本地绕行回归安全区" : "探索移动 · 本地绕行前往";
      if (r == 0) return;
      directFallbackActive = false;
      Cell nowHere = current();
      if (r > 0 && nowHere != null) {
        steps++;
        visited.add(nowHere);
        if (!returning && opening != null && goals.contains(nowHere)) {
          return;
        }
        if (returning && goals.contains(nowHere)) {
          finish("成功：已到达 " + nowHere.id() + "（本地重连绕行）；实际记录 " + steps + " 步");
          return;
        }
      }
      if (r < 0) {
        fail("MAP_NO_ROUTE", "局部重连绕行仍受阻：" + navigator.error());
        return;
      }
    }

    if (target != null) {
      int result = navigator.tick();
      if (result == 0) return;
      if (result < 0 || !target.equals(current())) {
        obstructed(State.STALE, navigator.error());
        return;
      }
      steps++;
      visited.add(target);
      if (clear(from, target) && clear(target, from)) map.walk(from, target, false, now());
      target = null;
      routeStep = null;
      return;
    }
    Cell here = current();
    if (here == null) {
      fail("MAP_NO_STANCE", "当前站位失去支撑");
      return;
    }
    Set<Cell> destinations = retreating ? goals("nearest_safe", false)
        : returning && branch ? Set.of(start) : goals;
    if (returning && destinations.contains(here)) {
      if (guidePlayer) {
        Player escorted = OwnerContext.findPlayer(mc, guidePlayerName);
        if (escorted != null && mc.player.distanceTo(escorted) > 6.5 && ++arrivalWaitTicks < 120) {
          Actions.status = "带玩家回家 · 已到终点，等玩家靠拢";
          Actions.look(escorted.getEyePosition());
          return;
        }
        String who = escorted == null ? "玩家" : escorted.getGameProfile().getName();
        finish("成功：已带领 " + who + " 安全回到 " + here.id() + "（" + targetLabel + "），共走 " + steps + " 格");
        return;
      }
      if (retreating) fail("MAP_NO_ROUTE", "原目标已不可达，已退守安全点：" + blocked);
      else if (!blocked.isBlank() && branch) fail("MAP_BLOCKED", "支路受阻，已回到起点：" + blocked);
      else finish("成功：已到达 " + here.id() + "；实际通过 " + steps + " 格");
      return;
    }
    if (!returning && (steps >= maxSteps || returnAfter && System.nanoTime() - turnBackAt >= 0)) {
      branchDone(here, "已用完探路预算");
      return;
    }
    if (steps >= maxSteps * (returnAfter ? 2 : 1)) {
      fail("MAP_DISTANCE_LIMIT", "已用完行走距离预算");
      return;
    }
    if (returning || opening != null && !goals.isEmpty() && !goals.contains(here)) {
      if (guidePlayer) {
        Actions.status = "带玩家回家 · 前往 " + targetLabel + "（剩余 " + route.size() + " 格）";
      }
      if (route.isEmpty() || !route.peek().from().equals(here)) {
        route.clear();
        route.addAll(map.route(here, destinations));
      }
      if (route.isEmpty()) {
        int healed = revalidateLocalEdges(here, 10, now());
        if (healed > 0) route.addAll(map.route(here, destinations));
      }
      if (route.isEmpty()) {
        if (!returning && opening != null && loaded(pos(opening))) {
          BlockPos fallback =
              goals.stream()
                  .map(ExplorationMemory::pos)
                  .filter(ExplorationMemory::loaded)
                  .min(Comparator.comparingDouble(p -> p.distSqr(pos(here))))
                  .orElse(pos(opening));
          if (navigator.startLocal(fallback)) {
            directFallbackActive = true;
            return;
          }
        }
        if (returning) {
          BlockPos nearDest =
              destinations.stream()
                  .map(ExplorationMemory::pos)
                  .filter(p -> loaded(p) && p.distSqr(pos(here)) <= 80 * 80)
                  .min(Comparator.comparingDouble(p -> p.distSqr(pos(here))))
                  .orElse(null);
          if (nearDest != null && navigator.startLocal(nearDest)) {
            directFallbackActive = true;
            return;
          }
        }
        if (returning && !retreating && !blocked.isBlank()) {
          retreating = true;
          return;
        }
        fail("MAP_NO_ROUTE", "这里没有到目标的已验证可逆路线 " + blocked);
        return;
      }
      var next = route.remove();
      advance(here, next.to(), next);
      return;
    }

    if (opening != null) {
      Cell next = opening;
      opening = null;
      advance(here, next, null);
      return;
    }
    var candidates = adjacent(here).stream().filter(c -> !visited.contains(c)).toList();
    boolean realJunction =
        steps > 0
            && (candidates.size() >= 3 || (steps >= 2 && candidates.size() > 1));
    if (candidates.isEmpty() || (stopOnJunction && realJunction)) {
      branchDone(here, candidates.isEmpty() ? "前方暂无可继续的开口" : "已到达下一个岔口");
      return;
    }
    Cell nextCell = chooseNext(here, candidates);
    advance(here, nextCell, null);
  }

  private Cell chooseNext(Cell here, List<Cell> candidates) {
    if (candidates.size() == 1) return candidates.get(0);
    return candidates.stream()
        .min(
            Comparator.<Cell>comparingInt(c -> map.walked(c) ? 1 : 0)
                .thenComparingInt(
                    c -> {
                      int dx = Integer.compare(c.x(), here.x());
                      int dz = Integer.compare(c.z(), here.z());
                      return (lastDx != 0 || lastDz != 0) && dx == lastDx && dz == lastDz ? 0 : 1;
                    }))
        .orElse(candidates.get(0));
  }

  private void advance(Cell here, Cell next, Step step) {
    from = here;
    target = next;
    lastDx = Integer.compare(next.x(), here.x());
    lastDz = Integer.compare(next.z(), here.z());
    routeStep = step;
    if (!loaded(pos(next))) {
      obstructed(State.STALE, "目标格未加载");
      return;
    }
    if (!clear(here, next) || !clear(next, here)) {
      obstructed(State.BLOCKED, "下一段支撑、开口或碰撞条件已变化");
      return;
    }
    if (!navigator.startKnownWalk(pos(next), p -> p.equals(pos(here)) || p.equals(pos(next)))) {
      if (!navigator.startForInteraction(pos(next)) && !navigator.startExact(pos(next))) {
        obstructed(State.STALE, navigator.error());
      }
    }
  }

  private void obstructed(State state, String reason) {
    navigator.stop();
    blocked = from.id() + " → " + target.id() + " " + reason;
    target = null;
    route.clear();
    if (routeStep == null) {
      if (returnAfter && !returning && current() != null) returning = true;
      else fail("MAP_BLOCKED", blocked);
      return;
    }
    map.update(routeStep, state, reason, now());
    routeStep = null;
    if (++reroutes > 8 || current() == null) fail("MAP_BLOCKED", blocked);
  }

  private void branchDone(Cell here, String reason) {
    if (returnAfter && steps > 0) {
      returning = true;
      route.clear();
    } else if (steps == 0) {
      fail("EXPLORE_NO_PROGRESS", reason + "（0 步未移动，建议改用 explore_roam 主动向未知推进）");
    } else {
      finish("成功：" + reason + "；当前站位 " + here.id() + "，共走 " + steps + " 格");
    }
  }

  private void fail(String code, String reason) {
    Cell here = current();
    finish("失败：[" + code + "] " + reason
        + (here == null ? "；当前位置支撑未确认" : "；已在 " + here.id() + " 停止"));
  }

  @Override void stop() {
    navigator.stop();
    Navigator.release();
  }
}
