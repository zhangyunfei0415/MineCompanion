package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;

/** One world-local follow session. Navigation only runs while the normal action lane is idle. */
final class FollowControl {
  record Target(UUID id, String name, Player player) {}
  private record Portal(BlockPos cell, String kind, String dimension, int seenTick) {}

  private static FollowControl current;
  private static String last = "未跟随";
  private final Target target;
  private final UUID id = UUID.randomUUID();
  private final long queuedSince = System.currentTimeMillis();
  private final String scope = Store.scope(), profile;
  private final boolean allowEnd;
  private final Navigator nav = new Navigator();
  private final ArrayDeque<BlockPos> waypoints = new ArrayDeque<>();
  private BlockPos seen, goal, failedAt, endEntry;
  private Portal observedPortal, crossing, lastCrossing, lastTargetPortal;
  private String lastTargetDimension;
  private int ticks, lastSeen, retryAfter, portalDeadline, failures, entryTicks;
  private long lastSeenAt;
  private boolean chasing, explicitPortal, rejoining;
  private String phase = "queued", detail = "等待控制空闲";

  private FollowControl(Target target, String profile, boolean allowEnd) {
    // Keep an identity, never a player-entity reference across a world transition.
    this.target = new Target(target.id(), target.name(), null);
    this.profile = profile;
    this.allowEnd = allowEnd;
  }

  static boolean active() { return current != null; }
  static boolean ready() { return active() && mc().player != null && mc().level != null; }

  static UUID currentTargetUuid() {
    if (current != null) return current.target.id();
    var saved = savedTarget();
    return saved == null ? null : saved.id();
  }

  static String currentTargetName() {
    if (current != null) return current.target.name();
    var saved = savedTarget();
    return saved == null ? "" : saved.name();
  }

  static boolean ensureFollowingSaved() {
    if (active()) return true;
    var saved = savedTarget();
    if (saved == null || mc().level == null || mc().player == null) return false;
    try {
      start(new JsonObject(), true);
      return active();
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  static List<Target> candidates() {
    var mc = mc();
    var targets = new LinkedHashMap<UUID, Target>();
    if (mc.player == null || mc.level == null || mc.getConnection() == null) return List.of();
    for (var p : mc.level.players()) {
      if (p != mc.player && p.isAlive() && !p.isSpectator())
        targets.put(p.getUUID(), new Target(p.getUUID(), p.getGameProfile().getName(), p));
    }
    for (var info : mc.getConnection().getOnlinePlayers()) {
      var profile = info.getProfile();
      if (!profile.getId().equals(mc.player.getUUID()) && profile.getName() != null)
        targets.putIfAbsent(profile.getId(), new Target(profile.getId(), profile.getName(), null));
    }
    return targets.values().stream().sorted(Comparator
        .comparing((Target t) -> t.player() == null)
        .thenComparing(Target::name, String.CASE_INSENSITIVE_ORDER)).toList();
  }

  static JsonObject targets() {
    var out = new JsonObject();
    var list = new JsonArray();
    for (var t : candidates().stream().limit(100).toList()) {
      var row = new JsonObject();
      row.addProperty("uuid", t.id().toString());
      row.addProperty("name", t.name());
      row.addProperty("tracked", t.player() != null);
      if (t.player() != null) row.add("position", position(t.player().blockPosition()));
      if (current != null && current.target.id().equals(t.id())) {
        if (current.lastTargetDimension != null)
          row.addProperty("last_known_dimension", current.lastTargetDimension);
        if (current.lastTargetPortal != null)
          row.add("last_known_portal", portalView(current.lastTargetPortal));
      }
      list.add(row);
    }
    out.add("players", list);
    return out;
  }

  static void select(Target target) {
    Store.data.followUuid = target.id().toString();
    Store.data.followName = target.name();
    Store.saveWorld();
  }

  static void toggle() {
    if (active()) {
      stop("已停止跟随");
      return;
    }
    if (mc().level == null || mc().player == null) return;
    if (savedTarget() == null) {
      suspend("选择玩家");
      mc().setScreen(new FollowTargetScreen(mc().screen));
      return;
    }
    try {
      start(new JsonObject(), true);
    } catch (IllegalArgumentException e) {
      Companion.notifyUser(e.getMessage());
    }
  }

  static Target savedTarget() {
    String saved = Store.data.followUuid;
    if (saved == null || saved.isBlank()) return null;
    try {
      return new Target(UUID.fromString(saved), Store.data.followName == null ? "" : Store.data.followName, null);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  static void command(ChatCommand.Follow command, String senderUuid, Consumer<String> reply) {
    boolean local = senderUuid.isEmpty();
    switch (command.action()) {
      case STATUS -> reply.accept(shortStatus());
      case PORTAL -> {
        var follow = current;
        if (!ready() || follow.observedPortal == null
            || !FollowPolicy.freshPortal(follow.ticks - follow.observedPortal.seenTick())) {
          reply.accept("请带小勤到可观察的门口，再说「跟我进门」");
          return;
        }
        var portal = follow.observedPortal;
        if (!command.argument().isEmpty() && !command.argument().equals(portal.kind())) {
          reply.accept("当前观察到的传送门类型和口令不一致");
          return;
        }
        if (!Agent.pendingTool.isBlank()) {
          reply.accept("请先处理面板中的待审批操作");
          return;
        }
        var args = position(portal.cell());
        args.addProperty("follow_id", follow.id.toString());
        args.addProperty("dimension", portal.dimension());
        Agent.execute("follow_enter_portal", args, result ->
            reply.accept(result.startsWith("{") ? shortStatus() : result));
      }
      case STOP -> {
        if ("follow_start".equals(Agent.pendingTool)) Agent.approve(false);
        boolean wasActive = active();
        stop("已停止跟随");
        if (!local || !wasActive) reply.accept("已停止跟随");
      }
      case START -> {
        var args = new JsonObject();
        if (!command.argument().isEmpty()) {
          args.addProperty("player_name", command.argument());
        } else if (!local) {
          args.addProperty("target_uuid", senderUuid);
        } else if (savedTarget() == null) {
          reply.accept("请在本地输入「跟随 玩家名」或先在工作台选择玩家");
          return;
        }
        if (!Agent.pendingTool.isBlank()) {
          reply.accept("请先处理面板中的待审批操作，再开始跟随");
          return;
        }
        if (local && !Agent.enabled) Agent.start();
        args.addProperty("allow_end_portal", Store.data.followEndPortal);
        Agent.execute("follow_start", args, result -> {
          if (!result.startsWith("{")) reply.accept(result);
          else if (!local) reply.accept(shortStatus());
        });
      }
    }
  }

  static JsonObject start(JsonObject args, boolean local) {
    var mc = mc();
    if (mc.player == null || mc.level == null || mc.getConnection() == null)
      throw new IllegalArgumentException("[WORLD_REQUIRED] 请先进入游戏");
    String uuid = Agent.str(args, "target_uuid", ""), name = Agent.str(args, "player_name", "").strip();
    var selected = uuid.isBlank() && name.isBlank() ? savedTarget() : resolve(uuid, name);
    if (selected == null || selected.id().equals(mc.player.getUUID()))
      throw new IllegalArgumentException("[FOLLOW_TARGET_REQUIRED] 请先选择其他玩家");
    var online = candidates().stream().filter(t -> t.id().equals(selected.id())).findFirst();
    if (online.isEmpty())
      throw new IllegalArgumentException("[FOLLOW_TARGET_OFFLINE] 目标不在当前服务器：" + selected.name());
    String profile = Agent.str(args, "profile", Store.data.followProfile).toLowerCase(Locale.ROOT);
    if (!List.of("follow", "nether", "end").contains(profile))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] profile=follow/nether/end");
    boolean end = args.has("allow_end_portal") ? args.get("allow_end_portal").getAsBoolean()
        : local && Store.data.followEndPortal;
    if (current != null && current.target.id().equals(selected.id())
        && current.profile.equals(profile) && current.allowEnd == end) return status();
    if (current != null) stop("切换跟随目标");
    if (local && !Agent.enabled) Agent.start();
    if (!uuid.isBlank() || !name.isBlank()) {
      select(online.get());
      DutyScheduler.bindEscortTarget(online.get().id(), online.get().name());
    }
    current = new FollowControl(online.get(), profile, end);
    Companion.notifyUser("跟随 " + current.target.name() + " · " + profileLabel(profile));
    return status();
  }

  private static Target resolve(String uuid, String name) {
    UUID parsed;
    try {
      parsed = uuid.isBlank() ? null : UUID.fromString(uuid);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("[INVALID_ARGUMENT] target_uuid格式不正确");
    }
    var matches = candidates().stream()
        .filter(t -> parsed == null || parsed.equals(t.id()))
        .filter(t -> name.isBlank() || t.name().equalsIgnoreCase(name)).toList();
    if (matches.size() != 1)
      throw new IllegalArgumentException("[FOLLOW_TARGET_UNKNOWN] 找不到唯一在线目标，请使用实际UUID");
    return matches.get(0);
  }

  static JsonObject stopRequest(JsonObject args) {
    String expected = Agent.str(args, "follow_id", "");
    if (current != null && (expected.isBlank() || !expected.equals(current.id.toString())))
      throw new IllegalArgumentException(
          "[FOLLOW_ID_MISMATCH] 请提供当前 follow_id=" + current.id);
    stop("已停止跟随");
    return status();
  }

  static JsonObject requestPortal(JsonObject args) {
    String expected = Agent.str(args, "follow_id", "");
    if (!ready() || !expected.equals(current.id.toString()))
      throw new IllegalArgumentException("[FOLLOW_ID_MISMATCH] 先查询follow_status并提供当前follow_id");
    if (!(args.has("x") && args.has("y") && args.has("z")))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 需要当前维度内门方块的完整坐标");
    String dimension = mc().level.dimension().location().toString();
    if (!dimension.equals(Agent.str(args, "dimension", dimension)))
      throw new IllegalArgumentException("[PORTAL_DIMENSION_MISMATCH] 请指定当前维度的门：" + dimension);
    var pos = new BlockPos(Agent.number(args, "x", 0), Agent.number(args, "y", 0), Agent.number(args, "z", 0));
    var portal = current.portalAt(pos);
    if (portal == null)
      throw new IllegalArgumentException("[PORTAL_NOT_OBSERVED] 指定位置没有已加载的传送门");
    if (!current.portalAllowed(portal.kind()))
      throw new IllegalArgumentException("[PORTAL_NOT_ALLOWED] 当前陪伴预设不允许进入此门");
    if (!current.portalInRange(portal))
      throw new IllegalArgumentException("[PORTAL_OUT_OF_RANGE] 门超出 " + FollowPolicy.PORTAL_RANGE + " 格");
    current.release();
    current.observedPortal = portal;
    current.explicitPortal = true;
    current.crossing = null;
    current.portalDeadline = current.ticks + FollowPolicy.PORTAL_WALK_TICKS;
    current.phase = "waiting_portal";
    current.detail = "已接受指定门口，等待执行槽";
    return status();
  }

  static void stop(String reason) {
    if (current == null) return;
    current.release();
    current = null;
    last = reason;
    Companion.notifyUser(reason);
  }

  static void suspend(String reason) {
    if (current == null) return;
    current.release();
    current.crossing = current.observedPortal = null;
    current.explicitPortal = false;
    current.phase = "waiting";
    current.detail = reason;
  }

  static void beforeDimensionChange() {
    if (current == null) return;
    if (current.crossing != null) current.lastCrossing = current.crossing;
    suspend("维度切换后重新观察");
    current.resetObservations();
    current.rejoining = true;
  }

  static void beforeDeath() {
    if (Store.config.deathRecovery.equals("RESPAWN_IDLE")) stop("重生后待命");
    else beforeDimensionChange();
  }

  static void tick() {
    if (!ready()) return;
    if (!Store.scope().equals(current.scope)) {
      stop("世界改变，跟随已结束");
      return;
    }
    current.advance();
  }

  private void advance() {
    var mc = mc();
    ticks++;
    if (mc.player.isDeadOrDying() || mc.player.isPassenger() || mc.player.isFallFlying()) {
      suspend("等待角色可以徒步移动");
      return;
    }
    if (rejoining) {
      if (goal != null) {
        walk();
        return;
      }
      if (!Navigator.grounded() || !Navigator.stand(Navigator.standingCell())
          || mc.player.isInWater() || mc.player.isInLava() || mc.player.isOnFire()) {
        suspend("等待核实跨维度落点");
        return;
      }
      var feet = Navigator.standingCell();
      if (portalAt(feet) != null || portalAt(feet.above()) != null) {
        if (ticks < retryAfter || failures >= 2) {
          phase = "waiting";
          detail = "已换维度，等待玩家带离传送门";
          return;
        }
        for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
          var exit = feet.relative(direction);
          if (allowedCell(exit) && Navigator.stand(exit) && nav.startFollow(exit, this::allowedCell)) {
            goal = exit;
            walk();
            return;
          }
        }
        detail = "已换维度，等待玩家带离传送门";
        phase = "waiting";
        return;
      }
      rejoining = false;
      failures = 0;
    }
    var player = mc.level.getPlayerByUUID(target.id());
    if (explicitPortal && observedPortal != null && mc.getConnection().getPlayerInfo(target.id()) != null) {
      stepThroughPortal();
      return;
    }
    if (player == null) {
      if (mc.getConnection().getPlayerInfo(target.id()) == null) {
        stop("跟随目标已离线");
        return;
      }
      if (crossing != null || observedPortal != null
          && FollowPolicy.freshPortal(ticks - observedPortal.seenTick())) {
        stepThroughPortal();
        return;
      }
      if (goal != null && ticks - lastSeen <= 30) {
        walk();
        return;
      }
      suspend("目标在线，等待进入可观察范围");
      return;
    }
    if (crossing != null) {
      release();
      crossing = null;
    }
    if (!player.isAlive() || player.isSpectator()) {
      observedPortal = null;
      suspend("等待目标重生或恢复可跟随状态");
      return;
    }
    lastSeen = ticks;
    lastSeenAt = System.currentTimeMillis();
    seen = player.blockPosition();
    var previousObservation = observedPortal;
    observedPortal = portalAt(seen);
    if (observedPortal == null) {
      var bounds = player.getBoundingBox().inflate(.02);
      for (var cell : BlockPos.betweenClosed(BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ),
          BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ))) {
        observedPortal = portalAt(cell.immutable());
        if (observedPortal != null) break;
      }
    }
    var portal = observedPortal;
    lastTargetDimension = mc.level.dimension().location().toString();
    if (portal != null) {
      if (previousObservation == null || !previousObservation.cell().equals(portal.cell())
          || !previousObservation.dimension().equals(portal.dimension())) {
        release();
        portalDeadline = ticks + FollowPolicy.PORTAL_WALK_TICKS;
      }
      lastTargetPortal = portal;
      if (portalAllowed(portal.kind()) && portalInRange(portal) && ticks < portalDeadline) {
        approachPortal(portal);
        return;
      }
      release();
      phase = "waiting_portal";
      detail = "已观察到目标在门口；当前预设、距离或时间预算不允许跟进";
      return;
    }
    if (phase.equals("approaching_portal") || phase.equals("waiting_portal")) release();
    if (Navigator.stand(seen) && allowedCell(seen)
        && !seen.equals(waypoints.peekLast())) {
      waypoints.addLast(seen);
      while (waypoints.size() > 12) waypoints.removeFirst();
    }
    double distance = mc.player.distanceTo(player);
    String rangeMode = Store.data != null && Store.data.duty != null ? Store.data.duty.followRange : "NORMAL";
    if (!FollowPolicy.chase(distance, chasing, rangeMode)) {
      release();
      chasing = false;
      phase = "near_target";
      detail = "已在目标附近";
      return;
    }
    chasing = true;
    if (failedAt != null && failedAt.distSqr(seen) >= 9) failures = 0;
    if (goal != null) {
      // Refresh on a moving leader, but allow a previous search/step to finish.
      if (ticks >= retryAfter && goal.distSqr(seen) > 100) release();
      else {
        walk();
        return;
      }
    }
    if (failures >= 2 || ticks < retryAfter) {
      phase = "route_blocked";
      return;
    }
    BlockPos next = approach(player);
    if (next == null || !nav.startFollow(next, this::allowedCell)) {
      failedAt = seen;
      failures++;
      retryAfter = ticks + 30;
      phase = "route_blocked";
      detail = "目标附近没有已核实的可走站位";
      return;
    }
    goal = next;
    retryAfter = ticks + 20;
    walk();
  }

  private BlockPos approach(Player player) {
    var feet = mc().player.position();
    var rear = feet.subtract(player.position());
    BlockPos preferred = BlockPos.containing(player.position().add(rear.horizontalDistanceSqr() < 1
        ? net.minecraft.world.phys.Vec3.ZERO
        : new net.minecraft.world.phys.Vec3(rear.x, 0, rear.z).normalize().scale(4)));
    BlockPos best = null;
    double score = Double.MAX_VALUE;
    var points = new ArrayList<BlockPos>();
    for (var p : BlockPos.betweenClosed(preferred.offset(-2, -1, -2), preferred.offset(2, 1, 2)))
      points.add(p.immutable());
    points.addAll(waypoints);
    for (var p : points) {
      var at = Navigator.foot(p);
      double separation = at.distanceToSqr(player.position());
      if (separation < 12 || separation > 100 || at.distanceToSqr(feet) > 96 * 96
          || at.distanceToSqr(feet) < 1 || !allowedCell(p) || !Navigator.stand(p)) continue;
      double cost = Math.abs(separation - 19) + .04 * at.distanceToSqr(feet);
      if (cost < score) {
        score = cost;
        best = p;
      }
    }
    return best;
  }

  private void walk() {
    int state = nav.tick();
    if (state != 0) goal = null;
    if (state < 0) {
      failures++;
      failedAt = seen;
      retryAfter = ticks + 30;
      phase = "route_blocked";
      detail = nav.error();
    } else {
      phase = "following";
      detail = "沿已加载通路跟随";
    }
  }

  private boolean portalInRange(Portal portal) {
    return mc().player.blockPosition().distSqr(portal.cell())
        <= FollowPolicy.PORTAL_RANGE * FollowPolicy.PORTAL_RANGE;
  }

  private void approachPortal(Portal portal) {
    if (goal == null && Navigator.standingCell().distSqr(portal.cell()) <= 4) {
      phase = "waiting_portal";
      detail = "已到门旁安全站位，等待领队过门";
      return;
    }
    if (goal == null && ticks >= retryAfter) {
      var directions = List.of(net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
          net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST);
      var stand = directions.stream().map(portal.cell()::relative)
          .filter(p -> allowedCell(p) && Navigator.stand(p))
          .min(Comparator.comparingDouble(p -> p.distSqr(Navigator.standingCell()))).orElse(null);
      if (stand != null && nav.startFollow(stand, this::allowedCell)) goal = stand;
      retryAfter = ticks + 30;
    }
    if (goal == null) {
      phase = "waiting_portal";
      detail = "等待门旁可确认的安全站位";
      return;
    }
    int result = nav.tick();
    if (result != 0) release();
    phase = "approaching_portal";
    detail = result < 0 ? nav.error() : "沿安全路线靠近领队刚进入的门";
  }

  private void stepThroughPortal() {
    if (crossing == null) {
      var confirmed = portalAt(observedPortal.cell());
      // End portals have no collision and sit over a lava/void well: stand on the frame ring.
      BlockPos stance =
          "end".equals(observedPortal.kind()) ? endStance(observedPortal.cell()) : observedPortal.cell();
      if (confirmed == null || !confirmed.kind().equals(observedPortal.kind())
          || !confirmed.dimension().equals(observedPortal.dimension())
          || !portalAllowed(observedPortal.kind())
          || stance == null || !Navigator.stand(stance)
          || !portalInRange(observedPortal) || ticks >= portalDeadline) {
        observedPortal = null;
        suspend("目标进入门口；等待玩家带领或确认入口");
        return;
      }
      release();
      crossing = observedPortal;
      entryTicks = 0;
      if (!nav.startFollow(stance, this::allowedCell)) {
        crossing = observedPortal = null;
        suspend("传送门入口不可安全到达");
        return;
      }
      goal = stance;
    }
    var confirmed = portalAt(crossing.cell());
    if (ticks >= portalDeadline || confirmed == null || !confirmed.kind().equals(crossing.kind())) {
      release();
      crossing = observedPortal = null;
      explicitPortal = false;
      phase = "portal_blocked";
      detail = "传送门已变化或等待传送超时";
      return;
    }
    if (goal != null) {
      int state = nav.tick();
      if (state != 0) goal = null;
      if (state < 0) {
        release();
        crossing = observedPortal = null;
        explicitPortal = false;
        phase = "portal_blocked";
        detail = nav.error();
        return;
      }
    }
    if (goal == null && "end".equals(crossing.kind())) {
      stepIntoEndPortal();
      return;
    }
    phase = goal == null ? "waiting_portal" : "approaching_portal";
    detail = "沿已观察的门口前进并等待正常传送";
  }

  /** Standable cell on or beside the end portal frame, horizontally next to an end portal block. */
  private BlockPos endStance(BlockPos portal) {
    BlockPos best = null;
    double score = Double.MAX_VALUE;
    for (var c : BlockPos.betweenClosed(portal.offset(-4, -1, -4), portal.offset(4, 2, 4))) {
      var cell = c.immutable();
      if (portalAt(cell) != null || endEntryFrom(cell) == null || !allowedCell(cell)
          || !Navigator.stand(cell)) continue;
      double d = cell.distSqr(mc().player.blockPosition());
      if (d < score) {
        score = d;
        best = cell;
      }
    }
    return best;
  }

  private BlockPos endEntryFrom(BlockPos stance) {
    for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
      var side = stance.relative(direction);
      for (var at : List.of(side, side.below())) {
        var portal = portalAt(at);
        if (portal != null && portal.kind().equals("end")) return at;
      }
    }
    return null;
  }

  /** One controlled step off the frame; done when the body touches the portal block shape. */
  private void stepIntoEndPortal() {
    var p = mc().player;
    if (endEntry == null) endEntry = endEntryFrom(Navigator.standingCell());
    if (endEntry == null || portalAt(endEntry) == null || ++entryTicks > 40
        || Navigator.lethalHazard(endEntry.above())) {
      release();
      crossing = observedPortal = null;
      explicitPortal = false;
      phase = "portal_blocked";
      detail = "末地门受控步入未能接触门方块";
      return;
    }
    var touch = new net.minecraft.world.phys.AABB(endEntry.getX(), endEntry.getY(), endEntry.getZ(),
        endEntry.getX() + 1, endEntry.getY() + .75, endEntry.getZ() + 1);
    if (p.getBoundingBox().intersects(touch)) {
      Navigator.release();
      phase = "waiting_portal";
      detail = "已接触末地门，等待正常传送";
      return;
    }
    double dx = endEntry.getX() + .5 - p.getX(), dz = endEntry.getZ() + .5 - p.getZ();
    p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
    PlayerControls.motionState(true, false);
    phase = "entering_portal";
    detail = "沿门框受控步入末地传送门";
  }

  private boolean portalAllowed(String kind) {
    return FollowPolicy.allowPortal(profile, kind, allowEnd);
  }

  private Portal portalAt(BlockPos p) {
    var level = mc().level;
    if (!level.hasChunkAt(p)) return null;
    var block = level.getBlockState(p);
    String kind = block.is(Blocks.NETHER_PORTAL) ? "nether"
        : block.is(Blocks.END_PORTAL) ? "end" : block.is(Blocks.END_GATEWAY) ? "gateway" : "";
    if (!kind.isEmpty()) return new Portal(p, kind, level.dimension().location().toString(), ticks);
    return null;
  }

  private boolean allowedCell(BlockPos p) {
    if (!mc().level.getWorldBorder().isWithinBounds(p) || !mc().level.hasChunkAt(p)) return false;
    var portal = portalAt(p);
    if (portal == null) portal = portalAt(p.below());
    if (portal == null) portal = portalAt(p.above());
    return portal == null || crossing != null && portal.kind().equals(crossing.kind())
        && p.distSqr(crossing.cell()) <= 2;
  }

  private void release() {
    if (goal != null) nav.stop();
    goal = null;
    if (endEntry != null) {
      endEntry = null;
      Navigator.release();
    }
  }

  private void resetObservations() {
    seen = failedAt = null;
    failures = 0;
    retryAfter = ticks;
    chasing = false;
    waypoints.clear();
    observedPortal = crossing = null;
  }

  static String shortStatus() {
    if (current == null) return last;
    var p = mc().level == null ? null : mc().level.getPlayerByUUID(current.target.id());
    String distance = p != null && mc().player != null ? Math.round(mc().player.distanceTo(p)) + " 格 · " : "";
    return "跟随 " + current.target.name() + " · " + distance + current.detail;
  }

  static String profileLabel(String profile) {
    return switch (profile) {
      case "nether" -> "下界陪伴";
      case "end" -> "末地陪伴";
      default -> "跟随同行";
    };
  }

  static JsonObject status() {
    var out = new JsonObject();
    boolean active = active();
    out.addProperty("active", active);
    out.addProperty("detail", shortStatus());
    if (!active) return out;
    var f = current;
    out.addProperty("follow_id", f.id.toString());
    out.addProperty("target_uuid", f.target.id().toString());
    out.addProperty("target_name", f.target.name());
    out.addProperty("profile", f.profile);
    out.addProperty("phase", f.phase);
    if (f.phase.equals("queued")) out.addProperty("queued_since_ms", f.queuedSince);
    if (f.lastTargetDimension != null) out.addProperty("last_known_dimension", f.lastTargetDimension);
    if (f.lastTargetPortal != null) out.add("last_known_portal", portalView(f.lastTargetPortal));
    if (mc().level != null) out.addProperty("dimension", mc().level.dimension().location().toString());
    if (f.seen != null) {
      out.add("last_seen", position(f.seen));
      out.addProperty("last_seen_age_ticks", f.ticks - f.lastSeen);
      out.addProperty("last_seen_at_ms", f.lastSeenAt);
      var p = mc().level == null ? null : mc().level.getPlayerByUUID(f.target.id());
      out.addProperty("tracked", p != null);
      if (p != null && mc().player != null) out.addProperty("distance", mc().player.distanceTo(p));
    }
    if (f.observedPortal != null) {
      out.add("portal", position(f.observedPortal.cell()));
      out.addProperty("portal_kind", f.observedPortal.kind());
    }
    if (f.lastCrossing != null) {
      var previous = position(f.lastCrossing.cell());
      previous.addProperty("kind", f.lastCrossing.kind());
      previous.addProperty("dimension", f.lastCrossing.dimension());
      out.add("previous_portal", previous);
    }
    return out;
  }

  private static JsonObject position(BlockPos p) {
    var out = new JsonObject();
    out.addProperty("x", p.getX());
    out.addProperty("y", p.getY());
    out.addProperty("z", p.getZ());
    return out;
  }

  private static JsonObject portalView(Portal portal) {
    var out = position(portal.cell());
    out.addProperty("kind", portal.kind());
    out.addProperty("dimension", portal.dimension());
    out.addProperty("age_ticks", current.ticks - portal.seenTick());
    return out;
  }

  private static Minecraft mc() { return Minecraft.getInstance(); }
}
