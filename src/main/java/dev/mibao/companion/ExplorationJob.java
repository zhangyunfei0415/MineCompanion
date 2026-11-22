package dev.mibao.companion;

import static dev.mibao.companion.ExplorationGraph.*;
import static dev.mibao.companion.ExplorationMemory.*;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/** Follows checked cells through the local movement lane. Never delegates to dig/build navigation. */
final class ExplorationJob extends Actions.Job {
  private final ExplorationGraph map;
  private final Navigator navigator = new Navigator();
  private final Set<Cell> goals;
  private final Set<Cell> visited = new HashSet<>();
  private final Deque<Step> route = new ArrayDeque<>();
  private final int maxSteps;
  private final long deadline, turnBackAt;
  private final boolean branch, stopOnJunction, returnAfter;
  private final Cell start;
  private Cell opening, target, from;
  private Step routeStep;
  private int steps, reroutes, lastDx, lastDz;
  private boolean returning, retreating;
  private String blocked = "";

  static void start(String tool, JsonObject args, Consumer<String> done) {
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
    stopOnJunction = bool(args, "stop_on_junction", true);
    returnAfter = branch && bool(args, "return_after", false);
    maxSteps = Rules.bounded(Agent.number(args, "max_distance", branch ? 40 : 256), 1, branch ? 96 : 512);
    long duration = 1_000_000_000L *
        Rules.bounded(Agent.number(args, "max_seconds", branch ? 45 : 180), 1, branch ? 120 : 300);
    long begun = System.nanoTime();
    deadline = begun + duration;
    turnBackAt = begun + duration / 2;
    if (!branch) {
      goals = goals(Agent.str(args, "target", "nearest_entry"), false);
      if (goals.isEmpty()) throw new IllegalArgumentException("[MAP_UNKNOWN_NODE] 没有可选的返回目标");
      return;
    }
    String id = Agent.str(args, "frontier_id", "");
    if (!id.isBlank()) {
      var options = goals(id, true);
      if (options.size() != 1)
        throw new IllegalArgumentException("[MAP_UNKNOWN_NODE] 未探岔口不存在或已探索，请刷新地图");
      opening = options.iterator().next();
      Set<Cell> anchors = map.adjacentObservedAnchors(opening);
      if (anchors.isEmpty())
        throw new IllegalArgumentException("[MAP_NO_ROUTE] 岔口缺少已观察的入口侧");
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
    return adjacent(start).stream()
        .filter(c -> c.x() == start.x() + dx && c.z() == start.z() + dz)
        .findFirst().orElseThrow(() -> new IllegalArgumentException("[MAP_NO_OPENING] 指定方向没有可确认的开口"));
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
      if (route.isEmpty() || !route.peek().from().equals(here)) {
        route.clear();
        route.addAll(map.route(here, destinations));
      }
      if (route.isEmpty()) {
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
    if (candidates.isEmpty() || stopOnJunction && candidates.size() > 1) {
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
    if (!navigator.startKnownWalk(pos(next), p -> p.equals(pos(here)) || p.equals(pos(next))))
      obstructed(State.STALE, navigator.error());
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
    if (returnAfter) {
      returning = true;
      route.clear();
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
