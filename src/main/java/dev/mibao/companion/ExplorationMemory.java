package dev.mibao.companion;

import static dev.mibao.companion.ExplorationGraph.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Client thread owner of the live shard. Samples player travel and local, visible openings. */
final class ExplorationMemory {
  static final String POLICY = "maze_strict_detour";
  static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
  private static final int SAVE_TICKS = 80;
  private static ExplorationStore.Key key;
  private static ClientLevel world;
  private static ExplorationGraph graph;
  private static CompletableFuture<ExplorationStore.Loaded> loading;
  private static CompletableFuture<Void> saving;
  private static boolean writable;
  private static long queuedRevision, tick, lastSave;
  private static Cell last, crossedFrom;
  private static Vec3 previousPosition;
  private static boolean usedClimbable;
  private static double lastGroundY;
  private static int unknownDirections;
  private static long lastObservation, lastExternalModTime, lastExternalPollTick;
  private static long failedModTime = Long.MIN_VALUE;
  private static int pollBackoff = 40;
  private static boolean polling;

  static ExplorationGraph graph() { return graph; }
  static Cell current() {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null || !mc.player.isAlive() || !Navigator.grounded()) return null;
    BlockPos pos = Navigator.standingCell();
    return loaded(pos) && Navigator.stand(pos) ? cell(pos) : null;
  }
  static Cell cell(BlockPos p) { return new Cell(p.getX(), p.getY(), p.getZ()); }
  static BlockPos pos(Cell c) { return new BlockPos(c.x(), c.y(), c.z()); }
  static long now() { return System.currentTimeMillis(); }

  static boolean isVerticalShaftCandidate(BlockPos p) {
    var mc = Minecraft.getInstance();
    if (mc.level == null || !loaded(p)) return false;
    boolean autoDoors = Store.config.autoOpenDoors;
    return Navigator.isClimbable(p, autoDoors)
        || Navigator.isClimbable(p.above(), autoDoors)
        || Navigator.isClimbable(p.below(), autoDoors);
  }

  static Passage verticalPassageType(BlockPos a, BlockPos b) {
    var mc = Minecraft.getInstance();
    if (mc.level == null) return Passage.LADDER;
    if (mc.level.getBlockState(a).getBlock() instanceof net.minecraft.world.level.block.ScaffoldingBlock
        || mc.level.getBlockState(b).getBlock() instanceof net.minecraft.world.level.block.ScaffoldingBlock) {
      return Passage.SCAFFOLD;
    }
    boolean autoDoors = Store.config.autoOpenDoors;
    if (Navigator.isClimbable(a, autoDoors) || Navigator.isClimbable(b, autoDoors)) {
      return Passage.LADDER;
    }
    return Passage.WATER_SHAFT;
  }

  static boolean clearVertical(Cell a, Cell b) {
    BlockPos pa = pos(a), pb = pos(b);
    return loaded(pa) && loaded(pb) && loaded(pa.above()) && loaded(pb.above())
        && !portal(pa) && !portal(pa.above()) && !portal(pb) && !portal(pb.above())
        && Navigator.openClimbStep(pa, pb);
  }

  static void tick() {
    var mc = Minecraft.getInstance();
    tick++;
    if (mc.player == null || mc.level == null || "disconnected".equals(Store.scope())) {
      leave();
      return;
    }
    var next = new ExplorationStore.Key(Store.scope(), mc.level.dimension().location().toString());
    if (!next.equals(key) || mc.level != world) {
      if (key != null && graph != null && last != null && key.scope().equals(next.scope())
          && !key.dimension().equals(next.dimension())) {
        Cell destination = current();
        if (destination == null) destination = cell(mc.player.blockPosition());
        graph.transition(last, destination, next.dimension(), EdgeKind.WARP_TRANSITION, now());
      }
      leave();
      key = next;
      world = mc.level;
      loading =
          ClientRolePolicy.shouldClaimExplorationWriteLock(Agent.enabled, Store.hasControl())
              ? ExplorationStore.load(Store.ROOT, key)
              : ExplorationStore.reloadReadOnly(Store.ROOT, key);
    }
      // A background poll must not pause sampling; only the first load of a shard blocks.
      if (loading != null && !loading.isDone() && graph == null) return;
      if (loading != null && loading.isDone()) {
        var loaded = loading.join();
        boolean polled = polling;
        polling = false;
        loading = null;
        long mod = shardModTime();
        if (loaded == null) {
          // Lock poll: the shard is still held elsewhere; keep the live in-memory graph and back off.
          pollBackoff = Math.min(400, pollBackoff * 2);
        } else if (loaded.failed()) {
          // Remember the unreadable version so it is not re-parsed until the file changes.
          failedModTime = mod;
          if (graph == null) {
            graph = loaded.graph();
            writable = false;
            queuedRevision = graph.revision();
          }
        } else if (polled && graph != null && Actions.job instanceof ExplorationJob) {
          // Never swap the map under a running explore job; hand the lease back and retry later.
          if (loaded.writable()) ExplorationStore.release(Store.ROOT, key);
        } else {
          // Keep local samples when the disk copy has not changed since it was last loaded.
          if (graph == null || !polled || mod != lastExternalModTime) {
            graph = loaded.graph();
            queuedRevision = graph.revision();
          }
          writable = loaded.writable();
          lastExternalModTime = mod;
          failedModTime = Long.MIN_VALUE;
          pollBackoff = 40;
        }
      }
      boolean wantWrite =
          ClientRolePolicy.shouldClaimExplorationWriteLock(Agent.enabled, Store.hasControl());
      if (wantWrite && !writable && key != null && loading == null
          && tick - lastExternalPollTick >= pollBackoff) {
        lastExternalPollTick = tick;
        if (!(Actions.job instanceof ExplorationJob) && shardModTime() != failedModTime) {
          polling = true;
          loading = ExplorationStore.claimAndLoad(Store.ROOT, key);
        }
      } else if (!wantWrite
          && !writable
          && key != null
          && loading == null
          && tick - lastExternalPollTick >= 60) {
        lastExternalPollTick = tick;
        try {
          Path p = ExplorationStore.path(Store.ROOT, key);
          if (Files.isRegularFile(p)) {
            long mod = Files.getLastModifiedTime(p).toMillis();
            if (mod != lastExternalModTime && mod != failedModTime
                && !(Actions.job instanceof ExplorationJob)) {
              polling = true;
              loading = ExplorationStore.reloadReadOnly(Store.ROOT, key);
            }
          }
        } catch (Exception ignored) {
        }
      }
    if (saving != null && saving.isCompletedExceptionally()) {
      queuedRevision = -1;
      saving = null;
    }
    if (tick - lastSave >= SAVE_TICKS) flush(false);
    if (graph == null) return;
    if (!mc.player.isAlive() || mc.player.getAbilities().flying || mc.player.isSpectator()
        || mc.player.isPassenger() || mc.player.isFallFlying() || mc.player.isInWater()) {
      resetTrace();
      return;
    }

    Vec3 position = mc.player.position();
    boolean jumped = previousPosition != null
        && (Math.hypot(position.x - previousPosition.x, position.z - previousPosition.z) > 8
            || Math.abs(position.y - previousPosition.y) > 8);
    previousPosition = position;
    if (jumped) {
      crossedFrom = last;
      last = null;
    }

    BlockPos feet = mc.player.blockPosition();
    boolean climbing = Store.config.allowClimb && mc.player.onClimbable();
    usedClimbable |= climbing;
    if (!mc.player.onGround() && !climbing) return;
    // Local support probes are only needed on movement or when the current evidence is due for refresh.
    if (last != null && last.equals(cell(feet)) && Math.abs(position.y - lastGroundY) < (climbing ? .35 : .05)
        && tick - lastObservation < 20) return;
    Cell here = current();
    if (here == null) {
      resetTrace();
      return;
    }
    long now = now();
    boolean changed = !here.equals(last);
    if (crossedFrom != null) {
      if (!crossedFrom.equals(here))
        graph.transition(crossedFrom, here, "", EdgeKind.WARP_TRANSITION, now);
      crossedFrom = null;
    } else if (changed && last != null) {
      int dx = Math.abs(last.x() - here.x()), dz = Math.abs(last.z() - here.z());
      int dyStep = here.y() - last.y();
      double deltaY = position.y - lastGroundY;
      if (dx == 0 && dz == 0 && Math.abs(dyStep) == 1 && (climbing || isVerticalShaftCandidate(pos(last)) || isVerticalShaftCandidate(pos(here)))) {
        Passage pMode = verticalPassageType(pos(last), pos(here));
        boolean clearUpDown = clearVertical(last, here) && clearVertical(here, last);
        var proof = new ExplorationGraph.PassageProof(pMode, clearUpDown, clearUpDown, dyStep, now);
        if (clearUpDown) {
          graph.walk(last, here, false, pMode, proof, now);
        } else if (loaded(pos(last)) && loaded(pos(here)) && clearVertical(last, here)) {
          graph.transition(last, here, "", EdgeKind.ONE_WAY_MECHANISM, pMode, now);
        }
      } else if (deltaY < -1.25 && dx + dz <= 4) {
        graph.transition(last, here, "",
            usedClimbable ? EdgeKind.ONE_WAY_MECHANISM : EdgeKind.ONE_WAY_DROP, now);
      } else if (usedClimbable && deltaY > 1.25 && dx + dz <= 4) {
        graph.transition(last, here, "", EdgeKind.ONE_WAY_MECHANISM, now);
      } else if (dx + dz == 1 && Math.abs(last.y() - here.y()) <= 1) {
        if (clear(last, here) && clear(here, last)) {
          graph.walk(last, here, false, now);
        } else if (loaded(pos(last)) && loaded(pos(here)) && clear(last, here)) {
          graph.transition(last, here, "", EdgeKind.ONE_WAY_MECHANISM, now);
        }
      }
    }
    if (!graph.walked(here)) graph.mark(here, Kind.PATH, null, List.of(), now);
    if (changed || tick - lastObservation >= 20) observe(here, now);
    last = here;
    lastGroundY = position.y;
    usedClimbable = false;
  }

  private static void resetTrace() {
    last = crossedFrom = null;
    previousPosition = null;
    usedClimbable = false;
    unknownDirections = 0;
  }

  static void leave() {
    flush(true);
    if (key != null) ExplorationStore.release(Store.ROOT, key);
    graph = null;
    loading = null;
    saving = null;
    key = null;
    world = null;
    writable = false;
    lastExternalModTime = 0;
    lastExternalPollTick = 0;
    failedModTime = Long.MIN_VALUE;
    pollBackoff = 40;
    polling = false;
    resetTrace();
  }

  private static void flush(boolean leaving) {
    if (graph == null || !writable
        || graph.revision() == queuedRevision && (saving == null || !saving.isCompletedExceptionally())
        || !leaving && saving != null && !saving.isDone()) return;
    queuedRevision = graph.revision();
    lastSave = tick;
    saving = ExplorationStore.save(Store.ROOT, key, graph.snapshot());
  }

  private static long shardModTime() {
    try {
      Path p = ExplorationStore.path(Store.ROOT, key);
      return Files.isRegularFile(p) ? Files.getLastModifiedTime(p).toMillis() : 0L;
    } catch (Exception ignored) {
      return 0L;
    }
  }

  static boolean loaded(BlockPos at) {
    var level = Minecraft.getInstance().level;
    return level != null && !level.isOutsideBuildHeight(at) && level.hasChunkAt(at)
        && level.getWorldBorder().isWithinBounds(at);
  }

  static boolean clear(Cell from, Cell to) {
    BlockPos a = pos(from), b = pos(to);
    return loaded(a) && loaded(b) && loaded(a.above()) && loaded(b.above())
        && !portal(a) && !portal(a.above()) && !portal(b) && !portal(b.above())
        && Navigator.openExplorationStep(a, b);
  }

  /** Re-evaluates nearby STALE/BLOCKED edges against live world blocks so cleared paths heal automatically. */
  static int revalidateLocalEdges(Cell center, int radius, long now) {
    if (graph == null || center == null) return 0;
    long rSq = (long) radius * radius;
    return graph.revalidateEdges(
        edge -> {
          if (edge.from().distanceSquared(center) > rSq
              && edge.to().distanceSquared(center) > rSq) {
            return false;
          }
          var cells = edge.cells();
          if (cells.size() < 2) return false;
          for (Cell c : cells) {
            if (!loaded(pos(c))) return false;
          }
          boolean verticalEdge = edge.passage() != Passage.GROUND;
          for (int i = 1; i < cells.size(); i++) {
            Cell a = cells.get(i - 1);
            Cell b = cells.get(i);
            boolean ok =
                verticalEdge
                    ? (clearVertical(a, b) && clearVertical(b, a))
                    : (clear(a, b) && clear(b, a));
            if (!ok) return false;
          }
          return true;
        },
        now);
  }

  private static boolean portal(BlockPos at) {
    var state = Minecraft.getInstance().level.getBlockState(at);
    return state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY);
  }

  static void minedStep(BlockPos from, BlockPos to, boolean cleared) {
    if (graph == null || !cell(to).equals(current())) return;
    Cell a = cell(from), b = cell(to);
    if (clear(a, b) && clear(b, a)) graph.walk(a, b, cleared, now());
    if (cleared) graph.mark(b, Kind.WORK_FACE, null, hints(b), now());
  }

  static void hazard(BlockPos at, String hint) {
    if (graph != null && visible(at) && graph.node(cell(at)) == null)
      graph.mark(cell(at), Kind.HAZARD, null, List.of(Rules.clean(hint, 80)), now());
  }

  static List<Cell> adjacent(Cell from) {
    List<Cell> out = new ArrayList<>(6);
    for (int[] d : DIRECTIONS) {
      for (int dy : new int[]{0, 1, -1}) {
        Cell to = new Cell(from.x() + d[0], from.y() + dy, from.z() + d[1]);
        if (clear(from, to) && clear(to, from)) {
          out.add(to);
          break;
        }
      }
    }
    if (Store.config.allowClimb) {
      for (int dy : new int[]{1, -1}) {
        Cell to = new Cell(from.x(), from.y() + dy, from.z());
        if (clearVertical(from, to) && clearVertical(to, from)) {
          out.add(to);
        }
      }
    }
    return out;
  }

  private static boolean visible(BlockPos at) {
    var mc = Minecraft.getInstance();
    return loaded(at) && mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(at)) <= 49
        && visibleRay(at);
  }

  private static boolean visibleRay(BlockPos at) {
    var mc = Minecraft.getInstance();
    var hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), Vec3.atCenterOf(at),
        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
    return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(at);
  }

  private static void observe(Cell here, long now) {
    lastObservation = tick;
    if (writable) revalidateLocalEdges(here, 6, now);
    var open = adjacent(here);
    unknownDirections = 0;
    for (int[] d : DIRECTIONS) {
      BlockPos side = pos(here).offset(d[0], 0, d[1]);
      if (!loaded(side)) {
        unknownDirections++;
        continue;
      }
      if (Minecraft.getInstance().level.getFluidState(side).is(FluidTags.LAVA) && visible(side)
          && graph.node(cell(side)) == null)
        graph.mark(cell(side), Kind.HAZARD, null, List.of("minecraft:lava"), now);
    }
    Node existing = graph.node(here);
    Kind kind = open.size() >= 3 ? Kind.JUNCTION
        : open.stream().anyMatch(c -> c.y() != here.y()) ? Kind.STAIRS : Kind.PATH;
    if (existing != null && existing.kind() != Kind.PATH) kind = existing.kind();
    boolean sparsePoint = kind != Kind.PATH || last == null || !last.equals(here) && tick % 4 == 0;
    if (sparsePoint) {
      List<String> hints = hints(here);
      if (kind != Kind.PATH || !hints.isEmpty()) {
        if (existing == null || existing.kind() != kind || !existing.landmark_hints().equals(hints) && !hints.isEmpty())
          graph.mark(here, kind, null, hints, now);
      }
    }
    for (Cell to : open) {
      if (!graph.walked(to) && visible(pos(to))) {
        boolean vertical =
            to.x() == here.x() && to.z() == here.z() && Math.abs(to.y() - here.y()) == 1;
        Passage mode = vertical ? verticalPassageType(pos(here), pos(to)) : Passage.GROUND;
        graph.opening(here, to, mode, now);
      }
    }
  }

  private static long distSq(Cell at, int x, int y, int z) {
    long dx = (long) at.x() - x, dy = (long) at.y() - y, dz = (long) at.z() - z;
    return dx * dx + dy * dy + dz * dz;
  }

  private static List<String> hints(Cell at) {
    if (key == null) return List.of();
    return Store.data.world.objects.values().stream()
        .filter(p -> p.present && key.dimension().equals(p.dimension)
            && distSq(at, p.x, p.y, p.z) <= 36
            && (p.category.contains("storage") || p.category.contains("sign")
                || p.category.contains("work") || p.block.equals("minecraft:crafting_table"))
            && visible(new BlockPos(p.x, p.y, p.z)))
        .sorted(Comparator.comparingLong(p -> distSq(at, p.x, p.y, p.z)))
        .map(p -> Rules.clean(p.signText.isBlank() ? p.block : "sign:" + p.signText, 80))
        .distinct().limit(4).toList();
  }

  static void minedAt(BlockPos actual, Collection<BlockPos> cleared) {
    Cell here = current();
    if (graph == null || here == null || actual.distSqr(pos(here)) > 4) return;
    long now = now();
    graph.mark(here, Kind.WORK_FACE, null, hints(here), now);
    for (BlockPos cut : cleared) {
      if (here.distanceSquared(cell(cut)) > 64) continue;
      graph.tagCleared(cell(cut), now);
      graph.tagCleared(cell(cut.below()), now);
    }
  }

  static void note(BlockPos p, Kind kind) {
    if (graph == null) return;
    Cell at = cell(p);
    if (at.equals(current()) || graph.walked(at)) graph.mark(at, kind, null, hints(at), now());
  }

  static String mark(JsonObject args) {
    if (graph == null) return "失败：[MAP_LOADING] 探索记忆未就绪";
    Cell at = current();
    boolean coordinates = args.has("x") || args.has("y") || args.has("z");
    String id = Agent.str(args, "node_id", "");
    if (!id.isBlank() && coordinates) throw new IllegalArgumentException("只能指定坐标或已有节点其中一种");
    Node previous = null;
    if (!id.isBlank()) {
      previous = graph.nodes().stream().filter(n -> n.cell().id().equals(id)).findFirst().orElse(null);
      if (previous == null) return "失败：[MAP_UNKNOWN_NODE] 没有这个节点";
      at = previous.cell();
    } else if (coordinates) {
      if (!args.has("x") || !args.has("y") || !args.has("z"))
        throw new IllegalArgumentException("坐标必须提供 x/y/z");
      at = cell(Actions.target(args));
      if (!visible(pos(at))) return "失败：[MAP_NOT_VISIBLE] 标记位置必须在本次可见范围内";
      previous = graph.node(at);
    }
    if (at == null) return "失败：[MAP_NO_STANCE] 请先站在确认安全的支撑面上";
    if (previous == null) previous = graph.node(at);
    String type = Agent.str(args, "kind", previous == null ? "safe_point" : previous.kind().name());
    Kind kind;
    try {
      kind = Kind.valueOf(type.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      throw new IllegalArgumentException(
          "kind=entry/safe_point/junction/stairs/work_face/hazard/path");
    }
    if (kind != Kind.HAZARD && kind != Kind.FRONTIER && previous == null && !graph.walked(at) && !at.equals(current())
        && !Navigator.stand(pos(at)))
      return "失败：[MAP_NO_STANCE] 所选位置无法站立";
    if (kind == Kind.FRONTIER && (previous == null || previous.kind() != Kind.FRONTIER))
      throw new IllegalArgumentException("岔口由可见采样登记");
    String name = args.has("name") ? Rules.clean(Agent.str(args, "name", ""), 64) : null;
    Node n = graph.mark(at, kind, name, hints(at), now());
    return "成功：已登记探索节点 " + n.cell().id() + " " + n.kind() + " " + n.name();
  }

  static Set<Cell> goals(String target, boolean frontier) {
    if (graph == null) return Set.of();
    var matches = new LinkedHashSet<Cell>();
    Node lastFace = null;
    String normalized = target == null ? "" : target.strip();
    if (!frontier && (normalized.equalsIgnoreCase("home") || normalized.equalsIgnoreCase("base"))) {
      var d = DutyScheduler.duty();
      if (d.hasHome && key != null && key.dimension().equals(d.homeDimension)) {
        Cell homeCell = new Cell(d.homeX, d.homeY, d.homeZ);
        if (graph.walked(homeCell)) {
          matches.add(homeCell);
          return matches;
        }
        Cell nearestToHome =
            graph.nodes().stream()
                .filter(n -> n.kind() != Kind.FRONTIER && n.kind() != Kind.HAZARD)
                .min(Comparator.comparingLong(n -> n.cell().distanceSquared(homeCell)))
                .map(Node::cell)
                .orElse(null);
        if (nearestToHome != null) matches.add(nearestToHome);
        return matches;
      }
    }
    for (Node n : graph.nodes()) {
      if (frontier != (n.kind() == Kind.FRONTIER) || n.kind() == Kind.HAZARD) continue;
      if (normalized.equals("last_work_face") && n.kind() == Kind.WORK_FACE
          && (lastFace == null || n.last_seen() > lastFace.last_seen())) lastFace = n;
      if (n.cell().id().equals(normalized) || !n.name().isBlank() && n.name().equalsIgnoreCase(normalized)
          || normalized.equals("nearest_entry") && n.kind() == Kind.ENTRY
          || normalized.equals("nearest_safe") && (n.kind() == Kind.SAFE_POINT || n.kind() == Kind.ENTRY))
        matches.add(n.cell());
    }
    if (lastFace != null) matches.add(lastFace.cell());
    if (!frontier && matches.isEmpty() && (normalized.equals("nearest_entry") || normalized.equals("nearest_safe"))) {
      if (normalized.equals("nearest_entry")) {
        for (Node n : graph.nodes()) {
          if (n.kind() == Kind.SAFE_POINT) matches.add(n.cell());
        }
      }
      if (matches.isEmpty()) {
        var d = DutyScheduler.duty();
        if (d.hasHome && key != null && key.dimension().equals(d.homeDimension)) {
          Cell homeCell = new Cell(d.homeX, d.homeY, d.homeZ);
          if (graph.walked(homeCell)) matches.add(homeCell);
          else {
            graph.nodes().stream()
                .filter(n -> n.kind() != Kind.FRONTIER && n.kind() != Kind.HAZARD)
                .min(Comparator.comparingLong(n -> n.cell().distanceSquared(homeCell)))
                .map(Node::cell)
                .ifPresent(matches::add);
          }
        }
      }
      if (matches.isEmpty()) {
        Cell oldest = graph.oldestWalkedCell();
        if (oldest != null) matches.add(oldest);
      }
    }
    return matches;
  }

  static JsonObject query(JsonObject args) {
    var out = new JsonObject();
    out.addProperty("policy", POLICY);
    out.addProperty("ready", graph != null);
    if (key != null) out.addProperty("dimension", key.dimension());
    if (graph == null) return out;
    out.addProperty("persistence", writable ? "enabled" : "memory_only");
    out.addProperty("nodes", graph.nodes().size());
    out.addProperty("edges", graph.edges().size());
    out.addProperty("unloaded_adjacent_directions", unknownDirections);
    out.addProperty("route_evidence", "historical_walk; rechecked_at_execution");
    Cell current = current();
    if (current != null) out.addProperty("current_node", current.id());
    String filter = Agent.str(args, "query", "").strip().toLowerCase(Locale.ROOT);
    int limit = Rules.bounded(Agent.number(args, "limit", 6), 1, 16);
    var nearest = graph.nodes().stream()
        .sorted(Comparator.comparingLong(n -> current == null ? 0 : n.cell().distanceSquared(current))).toList();
    var matches = new JsonArray();
    var frontiers = new JsonArray();
    boolean entry = false, safe = false;
    for (Node n : nearest) {
      if (!entry && n.kind() == Kind.ENTRY) { out.add("nearest_entry", nodeView(n)); entry = true; }
      if (!safe && (n.kind() == Kind.SAFE_POINT || n.kind() == Kind.ENTRY)) {
        out.add("nearest_safe", nodeView(n)); safe = true;
      }
      if (n.kind() == Kind.FRONTIER && frontiers.size() < limit) frontiers.add(nodeView(n));
      if (!filter.isEmpty() && matches.size() < limit
          && (n.name().toLowerCase(Locale.ROOT).contains(filter)
              || n.kind().name().toLowerCase(Locale.ROOT).contains(filter)
              || n.landmark_hints().stream().anyMatch(h -> h.toLowerCase(Locale.ROOT).contains(filter))))
        matches.add(nodeView(n));
    }
    out.add("frontiers", frontiers);
    out.add("matches", matches);
    var recheck = new JsonArray();
    graph.edges().stream().filter(e -> e.state() == State.BLOCKED || e.state() == State.STALE)
        .sorted(Comparator.comparingLong(e -> current == null ? 0 : e.from().distanceSquared(current)))
        .limit(limit).forEach(e -> {
          var item = new JsonObject();
          item.addProperty("from", e.from().id());
          item.addProperty("to", e.to().id());
          item.addProperty("state", e.state().name());
          item.addProperty("last_seen", e.last_seen());
          item.addProperty("reason", e.reason());
          recheck.add(item);
        });
    out.add("recheck", recheck);
    var transitions = new JsonArray();
    graph.edges().stream().filter(e -> e.kind() != EdgeKind.BIDIRECTIONAL)
        .sorted(Comparator.comparingLong(e -> current == null ? 0 : e.from().distanceSquared(current)))
        .limit(limit).forEach(e -> {
          var item = new JsonObject();
          item.addProperty("from", e.from().id());
          item.addProperty("to", e.to().id());
          item.addProperty("kind", e.kind().name());
          if (!e.destination_dimension().isBlank()) item.addProperty("dimension", e.destination_dimension());
          item.addProperty("automatic_return", false);
          transitions.add(item);
        });
    out.add("directional_transitions", transitions);
    String target = Agent.str(args, "target", "");
    if (!target.isBlank() && current != null) {
      var goals = goals(target, false);
      var path = graph.route(current, goals);
      var steps = new JsonArray();
      path.stream().limit(24).forEach(s -> steps.add(s.to().id()));
      out.addProperty("verified_route", goals.contains(current) || !path.isEmpty());
      out.addProperty("route_length", path.size());
      out.add("route_steps", steps);
      out.addProperty("truncated", path.size() > steps.size());
    }
    return out;
  }

  private static JsonObject nodeView(Node n) {
    JsonObject out = Store.GSON.toJsonTree(n).getAsJsonObject();
    out.addProperty("id", n.cell().id());
    return out;
  }
  private ExplorationMemory() {}
}
