package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;

/** Executes one explicitly bounded mine route. Every break uses the normal verified mining job. */
final class MiningRouteJob extends Actions.Job {
  private record Request(MiningRoute route, MiningRoute.Preset preset, BlockPos entry,
      int maxBreak, int seconds, boolean returnHome) {}

  private record Blockage(String kind, BlockPos position, String block) {
    JsonObject json() {
      var out = new JsonObject();
      out.addProperty("kind", kind);
      out.add("position", coords(position));
      if (!block.isBlank()) out.addProperty("block", block);
      return out;
    }
  }

  private final Request request;
  private final long deadline;
  private final Set<BlockPos> confirmed = new HashSet<>();
  private final List<BlockPos> visited = new ArrayList<>();
  private MiningJob breaker;
  private BlockPos breaking, blockedAt, from, to, lastStance;
  private Blockage blockage;
  private String breakResult;
  private int step, back = -1, moveTicks;
  private boolean returning;
  private ArrivalGate arrival = new ArrivalGate();
  private JumpCycle jump = new JumpCycle();

  MiningRouteJob(JsonObject args, Consumer<String> done) {
    super(done);
    request = parse(args);
    if (!NavigationPermissions.canBreak())
      throw new IllegalArgumentException("[NAVIGATION_BREAK_DISABLED] 开矿道需要允许导航破坏");
    if (mc().gameMode == null || mc().gameMode.getPlayerMode() != GameType.SURVIVAL)
      throw new IllegalArgumentException("[MODE_REQUIRED] 矿道施工需要生存模式");
    if (!entryReady(request.entry()))
      throw new IllegalArgumentException("[ROUTE_ENTRY] 请站在入口目标格中心附近的完整地板上");
    deadline = System.currentTimeMillis() + request.seconds() * 1000L;
    lastStance = request.entry();
    visited.add(request.entry());
  }

  static JsonObject preview(JsonObject args) {
    return preview(parse(args));
  }

  private static JsonObject preview(Request r) {
    var value = new JsonObject();
    value.addProperty("preset", r.preset().id());
    value.addProperty("label", r.preset().label);
    value.addProperty("dimension", mc().level.dimension().location().toString());
    value.add("entry", coords(r.entry()));
    value.add("return_anchor", coords(r.entry()));
    value.addProperty("planned_clearance", r.route().plannedClearance());
    value.addProperty("max_break", r.maxBreak());
    value.addProperty("break_authorized", NavigationPermissions.canBreak());
    value.addProperty("entry_ready", entryReady(r.entry()));
    value.addProperty("return_home", r.returnHome());
    value.addProperty("placement_required", false);
    var steps = new JsonArray();
    for (var segment : r.route().steps()) {
      var row = new JsonObject();
      row.add("stance", coords(pos(segment.to())));
      row.addProperty("clearance", segment.clearance().size());
      steps.add(row);
    }
    value.add("segments", steps);
    addBranches(value, r.route());
    value.addProperty("route_note", "入口和站位采用脚底Y；逐段现场核实后开挖。3×3请从有三格高净空的入口开始");
    return value;
  }

  static JsonObject openingPreview(JsonObject args) {
    var r = parse(args);
    var out = preview(r);
    var first = r.route().steps().get(0);
    String check = "READY";
    Blockage problem = null;
    if (!out.get("entry_ready").getAsBoolean()) {
      check = "ROUTE_ENTRY";
    } else if ((problem = floorBlockage(first)) != null) {
      check = "ROUTE_SUPPORT_REQUIRED";
    } else {
      BlockPos firstSolid = null;
      for (var point : first.clearance()) {
        var target = pos(point);
        if (!loaded(target)) {
          check = "UNLOADED_TARGET";
          problem = blockageAt("UNLOADED_TARGET", target);
          break;
        }
        if (mc().level.getBlockState(target).isAir()) continue;
        problem = materialBlockage(target);
        if (problem != null) {
          check = "ROUTE_BREAK_BLOCKED";
          break;
        }
        if (firstSolid == null) firstSolid = target;
      }
      if (check.equals("READY") && firstSolid != null && Reach.hit(firstSolid) == null) {
        check = "ROUTE_FIRST_BLOCK_UNREACHABLE";
        problem = blockageAt("UNREACHABLE", firstSolid);
      }
      if (check.equals("READY") && firstSolid == null
          && !Navigator.openMiningStep(r.entry(), pos(first.to()))) {
        check = "ROUTE_STEP_BLOCKED";
      }
    }
    out.addProperty("first_step_check", check);
    if (problem != null) out.add("first_step_detail", problem.json());
    return out;
  }

  private static Request parse(JsonObject args) {
    var mc = mc();
    if (mc.player == null || mc.level == null)
      throw new IllegalArgumentException("[WORLD_REQUIRED] 请先进入世界");
    String name = Agent.str(args, "preset", "tunnel_1x2");
    var preset = Arrays.stream(MiningRoute.Preset.values()).filter(p -> p.id().equals(name))
        .findFirst().orElseThrow(() -> new IllegalArgumentException("[INVALID_PRESET] 矿道预设不可用：" + name));
    var direction = Direction.byName(Agent.str(args, "direction", mc.player.getDirection().getName()));
    if (direction == null || direction.getAxis().isVertical())
      throw new IllegalArgumentException("[INVALID_DIRECTION] 请选择 north/east/south/west");
    var entry = requestedEntry(args);
    if (entry.distSqr(mc.player.blockPosition()) > 32 * 32)
      throw new IllegalArgumentException("[ROUTE_ENTRY] 入口须在玩家32格内");
    int length = SkillCatalog.bounded(args, "length", preset.length, 1, 24);
    var route = MiningRoute.plan(cell(entry), MiningRoute.Heading.valueOf(direction.name()), preset, length,
        SkillCatalog.bounded(args, "branch_spacing", 4, 3, 7),
        SkillCatalog.bounded(args, "branch_length", 6, 1, 8),
        branchLimits(args), floor -> supportProblem(pos(floor)));
    if (!valid(entry)
        || route.floors().stream().map(MiningRouteJob::pos).anyMatch(p -> !valid(p))
        || route.steps().stream().flatMap(s -> s.clearance().stream()).map(MiningRouteJob::pos)
            .anyMatch(p -> !valid(p)))
      throw new IllegalArgumentException("[ROUTE_BOUNDS] 计划截面超过建筑高度或世界边界");
    return new Request(route, preset, entry,
        SkillCatalog.bounded(args, "max_break", route.plannedClearance(), 1, 384),
        SkillCatalog.bounded(args, "seconds", 600, 30, 1800),
        !args.has("return_home") || args.get("return_home").getAsBoolean());
  }

  static boolean hasEntry(JsonObject args) {
    return args.has("x") || args.has("y") || args.has("z");
  }

  static BlockPos requestedEntry(JsonObject args) {
    if (!hasEntry(args)) return Navigator.standingCell();
    if (!(args.has("x") && args.has("y") && args.has("z")))
      throw new IllegalArgumentException("[ROUTE_ENTRY] 自定义入口需要完整 x/y/z");
    var level = mc().level;
    var entry = new BlockPos(
        SkillCatalog.bounded(args, "x", 0, -30_000_000, 30_000_000),
        SkillCatalog.bounded(args, "y", 0, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1),
        SkillCatalog.bounded(args, "z", 0, -30_000_000, 30_000_000));
    if (!valid(entry))
      throw new IllegalArgumentException("[ROUTE_BOUNDS] 指定入口超出建筑高度或世界边界");
    return entry;
  }

  private static List<Integer> branchLimits(JsonObject args) {
    if (!args.has("branch_limits")) return List.of();
    if (!args.get("branch_limits").isJsonArray() || args.getAsJsonArray("branch_limits").size() > 8)
      throw new IllegalArgumentException("[INVALID_ARGUMENT] branch_limits须为最多8项的数组");
    var limits = new ArrayList<Integer>();
    for (var value : args.getAsJsonArray("branch_limits")) {
      try {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
          throw new IllegalArgumentException();
        limits.add(value.getAsBigDecimal().intValueExact());
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("[INVALID_ARGUMENT] branch_limits须为整数数组");
      }
    }
    return limits;
  }

  private static void addBranches(JsonObject out, MiningRoute route) {
    if (route.branches().isEmpty()) return;
    var branches = new JsonArray();
    var limits = new JsonArray();
    int shortened = 0, skipped = 0;
    for (var branch : route.branches()) {
      limits.add(branch.planned());
      var item = new JsonObject();
      item.add("junction", coords(pos(branch.junction())));
      item.addProperty("direction", branch.heading().name().toLowerCase(Locale.ROOT));
      item.addProperty("requested_length", branch.requested());
      item.addProperty("preview_limit", branch.previewLimit());
      item.addProperty("planned_length", branch.planned());
      item.addProperty("skipped_by_request", branch.previewLimit() == 0);
      item.addProperty("support_changed_plan", branch.planned() < branch.previewLimit());
      if (branch.planned() == 0) skipped++;
      else if (branch.planned() < branch.requested()) shortened++;
      if (!branch.limitReason().isEmpty()) item.addProperty("reason_code", branch.limitReason());
      if (branch.blockedFloor() != null) item.add("blocked_floor", coords(pos(branch.blockedFloor())));
      branches.add(item);
    }
    out.add("branches", branches);
    out.add("branch_limits", limits);
    out.addProperty("branches_shortened", shortened);
    out.addProperty("branches_skipped", skipped);
  }

  @Override void tick() {
    Actions.status = request.preset().label + " · " + (returning ? "返程" : "截面")
        + " · 已通过 " + step + "/" + request.route().steps().size();
    if (System.currentTimeMillis() >= deadline) {
      finishResult("TIMEOUT", "达到本次任务时限");
      return;
    }
    if (!NavigationPermissions.canBreak() || mc().screen != null || mc().player.isInWater()
        || mc().player.isInLava() || mc().player.isOnFire()) {
      finishResult("ROUTE_INTERRUPTED", "施工权限、界面或当前环境发生变化");
      return;
    }
    if (to != null) {
      move();
      return;
    }
    if (breaker != null) {
      mineTick();
      return;
    }
    if (step == request.route().steps().size() || returning) {
      if (!returning) {
        returning = request.returnHome();
        back = visited.size() - 2;
      }
      if (!returning || back < 0) finishResult("NONE", "矿道已分段核验");
      else startMove(visited.get(back + 1), visited.get(back));
      return;
    }
    var segment = request.route().steps().get(step);
    var stance = pos(segment.from());
    if (!atDeparture(stance)) {
      finishResult("ROUTE_STANCE_CHANGED", "玩家离开本段已确认站位");
      return;
    }
    blockage = floorBlockage(segment);
    if (blockage != null) {
      blockedAt = blockage.position();
      finishResult("ROUTE_SUPPORT_REQUIRED", "下一截面缺少可核实的完整支撑");
      return;
    }
    for (var point : segment.clearance()) {
      var target = pos(point);
      if (!loaded(target)) {
        blockedAt = target;
        finishResult("UNLOADED_TARGET", "矿道前方尚不可核对");
        return;
      }
      if (world.getBlockState(target).isAir()) continue;
      blockedAt = target;
      if (confirmed.contains(target) || confirmed.size() >= request.maxBreak()) {
        finishResult("ROUTE_BREAK_BUDGET", "目标复原或清理预算已用完");
        return;
      }
      blockage = breakProblem(target, stance);
      if (blockage != null) {
        stopForBlockage();
        return;
      }
      var id = BuiltInRegistries.BLOCK.getKey(world.getBlockState(target).getBlock()).toString();
      breaking = target;
      breaker = new MiningJob(id, 1, 4, target, false, 1, r -> breakResult = r)
          .fromRoute(p -> (blockage = breakProblem(p, stance)) == null);
      return;
    }
    startMove(stance, pos(segment.to()));
  }

  private void mineTick() {
    // The nested mining job uses the same lane and completes synchronously back into this job.
    var active = breaker;
    Actions.job = active;
    try {
      active.tick();
    } finally {
      Actions.job = this;
    }
    if (breakResult == null) return;
    if (Rules.actionFailed(breakResult) || !loaded(breaking) || !world.getBlockState(breaking).isAir()) {
      if (blockage != null) {
        stopForBlockage();
        return;
      }
      String code = ReasonCodes.describe(breakResult).get("reason_code").getAsString();
      finishResult(switch (code) {
        case "NO_CAPACITY", "TOOL_UNAVAILABLE" -> code;
        default -> "ROUTE_BREAK_UNCONFIRMED";
      }, Rules.clean(breakResult, 480));
      return;
    }
    confirmed.add(breaking);
    breaker = null;
    breaking = null;
    blockedAt = null;
    blockage = null;
    breakResult = null;
  }

  private void stopForBlockage() {
    finishResult("ROUTE_BREAK_BLOCKED", "矿道停止：" + blockage.kind()
        + " @ " + blockage.position().toShortString()
        + (blockage.block().isBlank() ? "" : "（" + blockage.block() + "）"));
  }

  private Blockage breakProblem(BlockPos target, BlockPos stance) {
    if (!NavigationPermissions.canBreak()) return blockageAt("NAVIGATION_BREAK_DISABLED", target);
    if (!atDeparture(stance)) return blockageAt("ROUTE_STANCE_CHANGED", stance);
    if (!loaded(target)) return blockageAt("UNLOADED_TARGET", target);
    if (request.route().floors().contains(cell(target))) return blockageAt("PROTECTED_SUPPORT", target);
    var problem = materialBlockage(target);
    if (problem != null) return problem;
    return Reach.hit(target) == null ? blockageAt("UNREACHABLE", target) : null;
  }

  private static Blockage blockageAt(String kind, BlockPos p) {
    return new Blockage(kind, p, loaded(p)
        ? BuiltInRegistries.BLOCK.getKey(mc().level.getBlockState(p).getBlock()).toString() : "");
  }

  private static Blockage materialBlockage(BlockPos target) {
    var world = mc().level;
    var state = world.getBlockState(target);
    var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
    boolean natural = state.is(BlockTags.BASE_STONE_OVERWORLD)
        || state.is(BlockTags.BASE_STONE_NETHER)
        || state.is(BlockTags.DIRT)
        || id.getNamespace().equals("minecraft") && id.getPath().endsWith("_ore");
    if (!state.getFluidState().isEmpty()) return blockageAt("FLUID_TARGET", target);
    if (state.getBlock() instanceof FallingBlock) return blockageAt("FALLING_TARGET", target);
    if (!natural || state.hasBlockEntity()) return blockageAt("NON_NATURAL_BLOCK", target);
    for (var direction : Direction.values()) {
      var neighbor = target.relative(direction);
      if (!loaded(neighbor)) return blockageAt("UNLOADED_NEIGHBOR", neighbor);
      var next = world.getBlockState(neighbor);
      if (!next.getFluidState().isEmpty()) return blockageAt("NEIGHBOR_FLUID", neighbor);
      if (next.is(Blocks.FIRE) || next.is(Blocks.SOUL_FIRE)) return blockageAt("NEIGHBOR_FIRE", neighbor);
      if (direction == Direction.UP && next.getBlock() instanceof FallingBlock)
        return blockageAt("FALLING_ABOVE", neighbor);
    }
    return null;
  }

  private void startMove(BlockPos start, BlockPos end) {
    if (!atDeparture(start)) {
      finishResult("ROUTE_STANCE_CHANGED", "无法从已确认路线站位移动");
      return;
    }
    from = start;
    to = end;
    moveTicks = 0;
    arrival = new ArrivalGate();
    jump = new JumpCycle();
    move();
  }

  private void move() {
    var player = mc().player;
    var here = player.blockPosition();
    boolean nearFrom = here.getX() == from.getX() && here.getZ() == from.getZ();
    boolean nearTo = here.getX() == to.getX() && here.getZ() == to.getZ();
    if (++moveTicks > 75 || !nearFrom && !nearTo
        || Math.abs(player.getY() - from.getY()) > 1.6
        || !safeFloor(from.below()) || !safeFloor(to.below())
        || !Navigator.openMiningStep(from, to)) {
      blockedAt = to;
      finishResult("ROUTE_STEP_BLOCKED", "下一步的净空、支撑或到达状态未通过核对");
      return;
    }
    boolean atTarget = at(to);
    if (arrival.step(atTarget, player.onGround(), player.getDeltaMovement().y)) {
      PlayerControls.release();
      lastStance = to;
      if (returning) back--;
      else {
        int previous = visited.size() - 2;
        if (previous >= 0 && visited.get(previous).equals(to)) {
          visited.remove(previous + 1);
        } else {
          visited.add(to);
        }
        step++;
      }
      to = null;
      from = null;
      blockedAt = null;
      return;
    }
    double dx = to.getX() + .5 - player.getX(), dz = to.getZ() + .5 - player.getZ();
    var turn = MovementSteering.toward(player.getYRot(), (float) Math.toDegrees(Math.atan2(-dx, dz)));
    player.setYRot(turn.yaw());
    boolean forward = !atTarget && turn.forward() && dx * dx + dz * dz > .04;
    PlayerControls.motionState(forward, jump.step(player.onGround(),
        forward && to.getY() > player.getY() + .35, moveTicks));
  }

  private void finishResult(String code, String detail) {
    finish(result(code.equals("NONE") ? "verified" : "partial", code, detail).toString());
  }

  void suspendInput(String detail) {
    if (breaker != null || to != null) finishResult("ROUTE_INTERRUPTED", detail);
  }

  private JsonObject result(String status, String code, String detail) {
    var out = new JsonObject();
    out.addProperty("status", status);
    out.addProperty("business_success", status.equals("verified"));
    out.addProperty("reason_code", code);
    out.addProperty("detail", detail);
    out.addProperty("preset", request.preset().id());
    out.addProperty("dimension", dimension);
    out.addProperty("segments_confirmed", step);
    out.addProperty("segments_total", request.route().steps().size());
    out.addProperty("planned_clearance", request.route().plannedClearance());
    out.addProperty("cleared_by_job", confirmed.size());
    out.add("return_anchor", coords(request.entry()));
    out.add("last_confirmed_stance", coords(lastStance));
    out.addProperty("returned_home", returning && back < 0);
    var pending = blockedAt == null ? to : blockedAt;
    if (pending != null) out.add("blocked_at", coords(pending));
    if (blockage != null) out.add("reason_detail", blockage.json());
    addBranches(out, request.route());
    out.addProperty("needs_reobserve", !status.equals("verified"));
    return out;
  }

  @Override String interrupted() {
    return interruptionEvidence().toString();
  }

  JsonObject interruptionEvidence() {
    return result("cancelled", "ROUTE_INTERRUPTED", "已停止输入，未确认的方块请先观察");
  }

  @Override void stop() {
    if (breaker != null) breaker.stop();
    super.stop();
  }

  private static Minecraft mc() { return Minecraft.getInstance(); }
  private static BlockPos pos(MiningRoute.Cell c) { return new BlockPos(c.x(), c.y(), c.z()); }
  private static MiningRoute.Cell cell(BlockPos p) { return new MiningRoute.Cell(p.getX(), p.getY(), p.getZ()); }

  private static JsonObject coords(BlockPos p) {
    var out = new JsonObject();
    out.addProperty("x", p.getX());
    out.addProperty("y", p.getY());
    out.addProperty("z", p.getZ());
    return out;
  }

  private static boolean valid(BlockPos p) {
    var level = mc().level;
    return p.getY() >= level.getMinBuildHeight() && p.getY() < level.getMaxBuildHeight()
        && level.getWorldBorder().isWithinBounds(p);
  }

  private static boolean loaded(BlockPos p) { return valid(p) && mc().level.hasChunkAt(p); }

  private static String supportProblem(BlockPos p) {
    if (!valid(p)) return "ROUTE_BOUNDS";
    if (!loaded(p)) return "UNLOADED_TARGET";
    return safeFloor(p) ? "" : "ROUTE_SUPPORT_REQUIRED";
  }

  private static Blockage floorBlockage(MiningRoute.Step step) {
    for (var floor : step.floor()) {
      var p = pos(floor);
      String problem = supportProblem(p);
      if (!problem.isEmpty()) return blockageAt(problem, p);
    }
    return null;
  }

  private static boolean safeFloor(BlockPos p) {
    if (!loaded(p)) return false;
    var level = mc().level;
    var floor = level.getBlockState(p);
    return floor.getFluidState().isEmpty() && !(floor.getBlock() instanceof FallingBlock)
        && !floor.is(Blocks.MAGMA_BLOCK) && !floor.is(Blocks.SLIME_BLOCK)
        && !floor.is(Blocks.HONEY_BLOCK) && !floor.is(Blocks.ICE) && !floor.is(Blocks.PACKED_ICE)
        && !floor.is(Blocks.BLUE_ICE) && !floor.is(Blocks.FROSTED_ICE)
        && floor.isCollisionShapeFullBlock(level, p);
  }

  private static boolean at(BlockPos p) {
    return at(p, .055);
  }

  private boolean atDeparture(BlockPos p) {
    return p.equals(request.entry()) ? entryReady(p) : at(p);
  }

  static boolean entryReady(BlockPos p) {
    return at(p, .16) && safeFloor(p.below());
  }

  private static boolean at(BlockPos p, double radiusSquared) {
    var player = mc().player;
    if (player == null || !Navigator.grounded()) return false;
    double dx = player.getX() - p.getX() - .5;
    double dz = player.getZ() - p.getZ() - .5;
    return dx * dx + dz * dz <= radiusSquared && Math.abs(player.getY() - p.getY()) <= .15;
  }
}
