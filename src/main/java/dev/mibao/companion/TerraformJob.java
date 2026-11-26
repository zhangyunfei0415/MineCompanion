package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Foreground 3D terraforming and irrigated farm reclamation job owning ONE {@link Navigator} and at
 * most ONE in-flight {@link FixedStanceOp} at a time. Supports both Creative and Survival modes,
 * including modded/{@code /fly} flight landing safety.
 */
public final class TerraformJob extends Actions.Job {
  private enum EnginePhase {
    LAND,
    SAMPLE,
    WORK,
    REFILL_WAIT
  }

  private static final int CHECKPOINT_SCHEMA = 1;
  private static final int MAX_SAMPLE_PER_TICK = 256;
  private static final int MAX_REACH_CHECKS_PER_TICK = 24;
  private static final int LAND_MAX_DROP = 8;
  private static final int LAND_MAX_TICKS = 60;

  private static JsonObject lastSnapshot = new JsonObject();

  private final String jobId;
  private final TerraformSpec spec;
  private final String worldDimKey;
  private final Navigator nav = new Navigator();
  private final Set<Integer> doneOps = new LinkedHashSet<>();
  private final Set<Integer> skippedOps = new LinkedHashSet<>();
  private final Set<Integer> rejectedAtStance = new HashSet<>();
  private final Set<Long> unreachableStances = new HashSet<>();
  private final Set<Long> noProgressStances = new HashSet<>();
  private final Map<Integer, Integer> stanceRetries = new HashMap<>();
  private final Map<String, Integer> skipCounts = new LinkedHashMap<>();
  private final List<JsonObject> skipSamples = new ArrayList<>();
  private final Map<TerraformPlanner.OpPhase, Integer> verifiedByPhase =
      new EnumMap<>(TerraformPlanner.OpPhase.class);
  private final Map<Long, TerraformPlanner.CellState> liveCache = new HashMap<>();

  private EnginePhase phase;
  private TerraformPlanner.Plan plan;
  private FixedStanceOp activeOp;
  private TerraformPlanner.WorkOp currentOpSpec;
  private BlockPos currentGoalStance;
  private BlockPos currentTargetForStance;
  private BlockPos lastArrivedStance;
  private int opsAtLastArrival = -1;
  private int lastStanceTargetOpId = -1;
  private BlockPos pendingSeedPos;
  private int landTicks;
  private int groundedStableTicks;
  private int sampleX, sampleY, sampleZ;
  private boolean samplingInit;
  private int sampleMinX, sampleMaxX, sampleMinY, sampleMaxY, sampleMinZ, sampleMaxZ;
  private int verifiedOps;
  private int priorVerifiedOps;
  private int confirmedJournalable;
  private int priorConfirmedJournalable;
  private int stanceMoves;
  private int currentPhaseIdx;
  private int currentLayerY;
  private int refillWaitTicks;
  private BlockPos refillCell;
  private BlockPos refillStandGoal;
  private boolean flightClearedOnce;
  private boolean finalized;
  private String terminalState = "running";
  private String terminalReason = "";
  private final long startedMs = System.currentTimeMillis();

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  private TerraformJob(
      String jobId,
      TerraformSpec spec,
      TerraformPlanner.Plan prePlan,
      Set<Integer> preDone,
      BlockPos pendingSeed,
      Consumer<String> done) {
    super(done);
    this.jobId = jobId;
    this.spec = spec;
    this.worldDimKey = Store.worldDimKey();
    if (preDone != null) this.doneOps.addAll(preDone);
    this.pendingSeedPos = pendingSeed;
    this.plan = prePlan;

    var player = mc().player;
    boolean isFlying = player != null && player.getAbilities().flying;
    this.phase = isFlying ? EnginePhase.LAND : EnginePhase.SAMPLE;
    if (!isFlying) this.flightClearedOnce = true;
    initSamplingBounds();
    saveCheckpoint("running");
  }

  public static JsonObject preview(JsonObject rawArgs) {
    var mc = mc();
    if (mc.level == null || mc.player == null) {
      throw new IllegalStateException("[WRONG_CONTEXT] 世界未就绪，无法预览地形工程");
    }
    TerraformSpec spec = TerraformSpec.parse(rawArgs, false);
    spec.box()
        .validateWorldHeight(mc.level.getMinBuildHeight(), mc.level.getMaxBuildHeight() - 1);
    if (spec instanceof TerraformSpec.FarmGrid fg && fg.poolMin() != null) {
      validatePoolHeight(fg.poolMin(), mc.level);
    }

    Map<Long, TerraformPlanner.CellState> snap = new HashMap<>();
    var box = spec.box();
    for (int y = box.minY() - 1; y <= box.maxY() + 1; y++) {
      for (int z = box.minZ() - 1; z <= box.maxZ() + 1; z++) {
        for (int x = box.minX() - 1; x <= box.maxX() + 1; x++) {
          snap.put(pack3D(x, y, z), readLiveCell(mc.level, new BlockPos(x, y, z), spec));
        }
      }
    }
    TerraformPlanner.Plan plan =
        TerraformPlanner.plan(
            spec, (x, y, z) -> snap.getOrDefault(pack3D(x, y, z), TerraformPlanner.CellState.unknown()), playerStandPos());
    JsonObject out = plan.toPreviewJson(Store.worldDimKey());
    out.addProperty("preview", true);
    out.addProperty("hard_blocked", plan.hasHardBlockers());
    return out;
  }

  public static TerraformJob start(JsonObject rawArgs, Consumer<String> done) {
    var mc = mc();
    if (mc.level == null || mc.player == null) {
      throw new IllegalStateException("[WRONG_CONTEXT] 世界未就绪");
    }
    TerraformSpec spec = TerraformSpec.parse(rawArgs, true);
    spec.box()
        .validateWorldHeight(mc.level.getMinBuildHeight(), mc.level.getMaxBuildHeight() - 1);
    if (spec instanceof TerraformSpec.FarmGrid fg && fg.poolMin() != null) {
      validatePoolHeight(fg.poolMin(), mc.level);
    }
    enforcePermission(spec);

    Map<Long, TerraformPlanner.CellState> snap = new HashMap<>();
    var box = spec.box();
    for (int y = box.minY() - 1; y <= box.maxY() + 1; y++) {
      for (int z = box.minZ() - 1; z <= box.maxZ() + 1; z++) {
        for (int x = box.minX() - 1; x <= box.maxX() + 1; x++) {
          snap.put(pack3D(x, y, z), readLiveCell(mc.level, new BlockPos(x, y, z), spec));
        }
      }
    }
    TerraformPlanner.Plan pre =
        TerraformPlanner.plan(
            spec, (x, y, z) -> snap.getOrDefault(pack3D(x, y, z), TerraformPlanner.CellState.unknown()), playerStandPos());
    if (!spec.expectedPlanHash().isBlank() && !pre.planHash().equals(spec.expectedPlanHash())) {
      throw new IllegalArgumentException(
          "[PLAN_CHANGED] 现场地形与 preview 时相比已变化（expected="
              + spec.expectedPlanHash()
              + ", actual="
              + pre.planHash()
              + "），请重新调用 terraform_preview");
    }
    if (pre.hasHardBlockers()) {
      throw new IllegalArgumentException(
          "[TERRAFORM_BLOCKED] 存在阻断性几何或边界问题：" + pre.blockerTotals());
    }
    String id = UUID.randomUUID().toString();
    return new TerraformJob(id, spec, pre, Set.of(), null, done);
  }

  public static JsonObject control(JsonObject args, Consumer<String> done) {
    String action = Agent.str(args, "action", "status").strip().toLowerCase(Locale.ROOT);
    String reqJobId = Agent.str(args, "job_id", "");
    return switch (action) {
      case "", "status" -> statusSnapshot(reqJobId);
      case "pause" -> {
        if (reqJobId.isBlank()) throw new IllegalArgumentException("[ARGUMENT_REQUIRED] pause 需要 job_id");
        if (!(Actions.job instanceof TerraformJob tf) || !tf.jobId.equals(reqJobId)) {
          throw new IllegalArgumentException("[JOB_NOT_ACTIVE] 当前没有运行中的匹配 job_id：" + reqJobId);
        }
        tf.pauseWithCode("TERRAFORM_PAUSED", "收到显式暂停指令，已保存断点");
        yield tf.buildStatusJson("paused");
      }
      case "abort" -> {
        if (reqJobId.isBlank()) throw new IllegalArgumentException("[ARGUMENT_REQUIRED] abort 需要 job_id");
        if (Actions.job instanceof TerraformJob tf && tf.jobId.equals(reqJobId)) {
          tf.abortWithCode("TERRAFORM_ABORTED", "已中止地形作业，保留已生效方块与日志");
          yield tf.buildStatusJson("aborted");
        }
        String key = Store.worldDimKey() + "|terraform|" + reqJobId;
        JsonObject cp =
            Store.data != null && Store.data.checkpoints != null
                ? Store.data.checkpoints.get(key)
                : null;
        if (cp == null) {
          throw new IllegalArgumentException("[JOB_NOT_FOUND] 未找到当前作用域的地形作业：" + reqJobId);
        }
        cp.addProperty("state", "aborted");
        Store.saveSoon();
        yield cp.deepCopy();
      }
      case "resume" -> {
        if (reqJobId.isBlank()) throw new IllegalArgumentException("[ARGUMENT_REQUIRED] resume 需要 job_id");
        if (Actions.job != null) {
          throw new IllegalStateException("[BUSY] 当前已有前景作业运行中，不能覆盖恢复");
        }
        if (Agent.paused) {
          throw new IllegalStateException("[AGENT_PAUSED] 全局暂停中，请先恢复AI接管");
        }
        TaskReadiness.requireWorld(false);
        TerraformJob resumed = resumeFromCheckpoint(reqJobId, done);
        Actions.job = resumed;
        var out = resumed.buildStatusJson("running");
        out.addProperty("resumed", true);
        yield out;
      }
      default ->
          throw new IllegalArgumentException(
              "[ARGUMENT_INVALID] action 必须为 status / pause / resume / abort");
    };
  }

  private static TerraformJob resumeFromCheckpoint(String jobId, Consumer<String> done) {
    if (Store.data == null || Store.data.checkpoints == null) {
      throw new IllegalArgumentException("[CHECKPOINT_NOT_FOUND] 无可用检查点");
    }
    String key = Store.worldDimKey() + "|terraform|" + jobId;
    JsonObject cp = Store.data.checkpoints.get(key);
    if (cp == null) {
      throw new IllegalArgumentException("[CHECKPOINT_NOT_FOUND] 当前世界/维度无匹配检查点：" + jobId);
    }
    if (!"paused".equals(Agent.str(cp, "state", ""))) {
      throw new IllegalStateException(
          "[INVALID_STATE] 仅 paused 状态可 resume（当前=" + Agent.str(cp, "state", "") + "）");
    }
    TerraformSpec spec = TerraformSpec.parse(cp.getAsJsonObject("spec"), true);
    spec.box()
        .validateWorldHeight(mc().level.getMinBuildHeight(), mc().level.getMaxBuildHeight() - 1);
    enforcePermission(spec);

    int savedCompletedCount =
        cp.has("completed_ops") && cp.get("completed_ops").isJsonArray()
            ? cp.getAsJsonArray("completed_ops").size()
            : 0;
    BlockPos pendingSeed = null;
    if (cp.has("pending_seed_pos") && cp.get("pending_seed_pos").isJsonObject()) {
      var ps = cp.getAsJsonObject("pending_seed_pos");
      pendingSeed = new BlockPos(ps.get("x").getAsInt(), ps.get("y").getAsInt(), ps.get("z").getAsInt());
    }
    if (mc().player != null && mc().player.getAbilities().flying) {
      throw new IllegalStateException("[LANDING_REQUIRED] 玩家仍在飞行中，请先安全落地后再 resume");
    }
    TerraformJob job = new TerraformJob(jobId, spec, null, Set.of(), pendingSeed, done);
    job.verifiedOps = cp.has("verified_ops") ? cp.get("verified_ops").getAsInt() : savedCompletedCount;
    job.priorVerifiedOps = job.verifiedOps;
    job.confirmedJournalable =
        cp.has("confirmed_journalable") ? cp.get("confirmed_journalable").getAsInt() : 0;
    job.priorConfirmedJournalable = job.confirmedJournalable;
    job.stanceMoves = cp.has("stance_moves") ? cp.get("stance_moves").getAsInt() : 0;
    if (cp.has("verified_by_phase") && cp.get("verified_by_phase").isJsonObject()) {
      var vp = cp.getAsJsonObject("verified_by_phase");
      for (var ph : TerraformPlanner.OpPhase.values()) {
        String phKey = ph.name().toLowerCase(Locale.ROOT);
        if (vp.has(phKey) && vp.get(phKey).isJsonPrimitive()) {
          int c = vp.get(phKey).getAsInt();
          if (c > 0) job.verifiedByPhase.put(ph, c);
        }
      }
    }
    job.saveCheckpoint("running");
    return job;
  }

  private static JsonObject statusSnapshot(String reqJobId) {
    if (Actions.job instanceof TerraformJob tf
        && (reqJobId.isBlank() || tf.jobId.equals(reqJobId))) {
      return tf.buildStatusJson("running");
    }
    String dimScope = Store.worldDimKey();
    if (Store.data != null && Store.data.checkpoints != null) {
      if (!reqJobId.isBlank()) {
        JsonObject cp = Store.data.checkpoints.get(dimScope + "|terraform|" + reqJobId);
        if (cp != null) return cp.deepCopy();
      } else {
        JsonObject latest = Store.data.checkpoints.get(dimScope + "|terraform|latest");
        if (latest != null) return latest.deepCopy();
      }
    }
    if (lastSnapshot.size() > 0) {
      var copy = lastSnapshot.deepCopy();
      copy.addProperty("historical_context", !dimScope.equals(Agent.str(copy, "scope", "")));
      return copy;
    }
    var empty = new JsonObject();
    empty.addProperty("state", "none");
    empty.addProperty("active_now", false);
    empty.addProperty("scope", dimScope);
    return empty;
  }

  private static void enforcePermission(TerraformSpec spec) {
    if (!"LIMITED".equals(Store.config.permission) || Store.config.fullGameControl) return;
    var req = spec.requiredPermissions();
    if (req.mine() && !Store.config.allowMine) {
      throw new IllegalArgumentException("[PERMISSION_REQUIRED] 有限模式未允许采集/切方权限");
    }
    if (req.place() && !Store.config.allowPlace) {
      throw new IllegalArgumentException("[PERMISSION_REQUIRED] 有限模式未允许放置/造田权限");
    }
  }

  private static void validatePoolHeight(TerraformSpec.Pos poolMin, Level level) {
    if (poolMin.y() < level.getMinBuildHeight() || poolMin.y() >= level.getMaxBuildHeight()) {
      throw new IllegalArgumentException("[WORLD_BOUNDS] pool_y 超出世界高度范围");
    }
  }

  void onExternalPause() {
    pauseWithCode("TERRAFORM_PAUSED", "AI 外部暂停，已保存断点并释放控制");
  }

  void onHumanIntervention(String why) {
    pauseWithCode("MANUAL_INTERVENTION", "检测到人工接管（" + why + "），已暂停地形作业");
  }

  @Override
  void tick() {
    if (finalized) return;
    var mc = mc();
    if (mc.player == null || mc.level == null) {
      pauseWithCode("WRONG_CONTEXT", "世界未就绪");
      return;
    }
    if (!worldDimKey.equals(Store.worldDimKey())) {
      abortWithCode("WORLD_CHANGED", "世界/维度已变更");
      return;
    }
    boolean isInvScreen =
        mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen;
    boolean managingScreen = activeOp != null && activeOp.isManagingScreen();
    if (TerraformPlanner.shouldPauseForScreen(mc.screen != null, isInvScreen, managingScreen)) {
      pauseWithCode("SCREEN_OPEN", "检测到界面打开，已暂停地形作业");
      return;
    }
    if (Cooperation.yielding()) {
      pauseWithCode("MANUAL_INTERVENTION", "协同模式下人工优先，已暂停");
      return;
    }
    if (SurvivalPolicy.shouldPauseBuildWork(
        mc.player.getHealth(),
        mc.player.getMaxHealth(),
        mc.player.getFoodData().getFoodLevel(),
        mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.CREATIVE,
        Store.config.testIgnoreLowHealth)) {
      pauseWithCode(
          "SURVIVAL_PAUSED",
          "生命或饱食不足（生命 "
              + Math.round(mc.player.getHealth())
              + "，饱食 "
              + mc.player.getFoodData().getFoodLevel()
              + "），已暂停等待补给");
      return;
    }

    if (flightClearedOnce && mc.player.getAbilities().flying) {
      pauseWithCode("MANUAL_FLIGHT_INTERRUPTED", "检测到手动重新起飞，已保存断点；落地后可 resume");
      return;
    }

    switch (phase) {
      case LAND -> tickLand();
      case SAMPLE -> tickSample();
      case WORK -> tickWork();
      case REFILL_WAIT -> tickRefillWait();
    }
  }

  private void tickLand() {
    var mc = mc();
    var player = mc.player;
    Actions.status = "地形施工 · 安全降落准备中";

    if (!player.getAbilities().flying && player.onGround() && Navigator.grounded()) {
      if (++groundedStableTicks >= 2) {
        PlayerControls.release();
        flightClearedOnce = true;
        phase = EnginePhase.SAMPLE;
      }
      return;
    }
    groundedStableTicks = 0;

    if (!verifyLandingCorridorSafe()) {
      PlayerControls.release();
      finishTerminal("blocked", "LANDING_REQUIRED", "脚下8格内没有已加载且干燥平稳的落脚实面，请人工落地后重试");
      return;
    }
    if (++landTicks > LAND_MAX_TICKS) {
      PlayerControls.release();
      finishTerminal("blocked", "LANDING_REQUIRED", "降落超时（60tick内未能退出飞行触地），请人工落地后重试");
      return;
    }
    PlayerControls.motionState(false, false, false, true);
  }

  private boolean verifyLandingCorridorSafe() {
    var level = mc().level;
    var player = mc().player;
    AABB box = player.getBoundingBox().deflate(0.05, 0, 0.05);
    int feetFloor = (int) Math.floor(box.minY);
    BlockPos solidFloor = null;
    for (int dy = 1; dy <= LAND_MAX_DROP; dy++) {
      int y = feetFloor - dy;
      if (y < level.getMinBuildHeight()) return false;
      int minX = (int) Math.floor(box.minX), maxX = (int) Math.floor(box.maxX);
      int minZ = (int) Math.floor(box.minZ), maxZ = (int) Math.floor(box.maxZ);
      boolean allEmpty = true;
      boolean anySolid = false;
      for (int x = minX; x <= maxX; x++) {
        for (int z = minZ; z <= maxZ; z++) {
          BlockPos p = new BlockPos(x, y, z);
          if (!level.hasChunkAt(p)) return false;
          var st = level.getBlockState(p);
          if (!st.getFluidState().isEmpty()
              || st.is(Blocks.FIRE)
              || st.is(Blocks.SOUL_FIRE)
              || st.getBlock() instanceof MagmaBlock
              || st.getBlock() instanceof CactusBlock) return false;
          if (!st.getCollisionShape(level, p).isEmpty()) {
            anySolid = true;
            allEmpty = false;
            if (st.getBlock() instanceof FarmBlock || st.getBlock() instanceof ScaffoldingBlock)
              return false;
            if (solidFloor == null) solidFloor = p;
          }
        }
      }
      AABB slice = new AABB(box.minX, y, box.minZ, box.maxX, y + 1.0, box.maxZ);
      if (!level.getEntities(player, slice, e -> e.isAlive() && e.isPickable()).isEmpty()) {
        return false;
      }
      if (anySolid) {
        return solidFloor != null && Navigator.stand(solidFloor.above());
      }
      if (!allEmpty) return false;
    }
    return false;
  }

  private void initSamplingBounds() {
    var box = spec.box();
    sampleMinX = box.minX() - 1;
    sampleMaxX = box.maxX() + 1;
    sampleMinY = box.minY() - 1;
    sampleMaxY = box.maxY() + 1;
    sampleMinZ = box.minZ() - 1;
    sampleMaxZ = box.maxZ() + 1;
    sampleX = sampleMinX;
    sampleY = sampleMinY;
    sampleZ = sampleMinZ;
    samplingInit = true;
    liveCache.clear();
  }

  private void tickSample() {
    if (!samplingInit) initSamplingBounds();
    var level = mc().level;
    int budget = MAX_SAMPLE_PER_TICK;
    Actions.status = "地形施工 · 分帧扫描现场";
    while (budget-- > 0 && sampleY <= sampleMaxY) {
      BlockPos p = new BlockPos(sampleX, sampleY, sampleZ);
      liveCache.put(pack3D(sampleX, sampleY, sampleZ), readLiveCell(level, p, spec));
      sampleX++;
      if (sampleX > sampleMaxX) {
        sampleX = sampleMinX;
        sampleZ++;
        if (sampleZ > sampleMaxZ) {
          sampleZ = sampleMinZ;
          sampleY++;
        }
      }
    }
    if (sampleY <= sampleMaxY) return;

    TerraformPlanner.Plan computed =
        TerraformPlanner.plan(
            spec,
            (x, y, z) ->
                liveCache.getOrDefault(pack3D(x, y, z), TerraformPlanner.CellState.unknown()),
            playerStandPos());
    if (this.plan == null) {
      this.plan = computed;
      doneOps.clear();
    } else if (!doneOps.isEmpty()) {
      this.plan = computed;
      doneOps.clear();
    }
    if (this.plan.hasHardBlockers()) {
      finishTerminal("blocked", "TERRAFORM_BLOCKED", "地形不满足施工安全前置条件：" + this.plan.blockerTotals());
      return;
    }
    advancePhaseCursor();
    phase = EnginePhase.WORK;
  }

  private void tickWork() {
    if (verifiedOps >= TerraformSpec.MAX_OPS) {
      finishTerminal("partial", "OPS_LIMIT_REACHED", "已达单次最大变更上限 " + TerraformSpec.MAX_OPS);
      return;
    }
    var mc = mc();
    int totalEstOps = plan == null ? priorVerifiedOps : priorVerifiedOps + plan.ops().size();
    Actions.status =
        "地形施工("
            + spec.mode().name().toLowerCase(Locale.ROOT)
            + ") · 已核实 "
            + verifiedOps
            + "/"
            + totalEstOps;

    // Handle active micro-op in flight (max 1 op in flight at any tick)
    if (activeOp != null) {
      FixedStanceOp.Status st = activeOp.tick();
      if (st == FixedStanceOp.Status.RUNNING) return;
      if (activeOp.isManagingScreen()) activeOp.cancel();
      if (st == FixedStanceOp.Status.VERIFIED) {
        onOpVerified(currentOpSpec, activeOp);
        activeOp = null;
        currentOpSpec = null;
        if (pendingSeedPos != null) {
          startImmediateSeed(pendingSeedPos);
        }
        return;
      }
      if (st == FixedStanceOp.Status.NEED_STANCE) {
        if (currentOpSpec != null) rejectedAtStance.add(currentOpSpec.id());
        activeOp = null;
        currentOpSpec = null;
        return;
      }
      if (st == FixedStanceOp.Status.UNCERTAIN) {
        String code = activeOp.reasonCode();
        String msg = activeOp.detail();
        activeOp = null;
        pauseWithCode(code, msg + "（已保留断点，不盲目重发）");
        return;
      }
      // DENIED
      String code = activeOp.reasonCode();
      String msg = activeOp.detail();
      if (currentOpSpec != null
          && currentOpSpec.kind() == TerraformPlanner.OpKind.PLACE_WATER
          && "MISSING_ITEM".equals(code)
          && spec instanceof TerraformSpec.FarmGrid fg) {
        activeOp = null;
        if (fg.waterSource() == TerraformSpec.WaterSource.POOL_2X2) {
          startRefillFromPool(fg.poolMin());
          return;
        }
        if (fg.onWaterShortage() == TerraformSpec.WaterShortagePolicy.PREPARE_DIRT) {
          finishTerminal("partial", "PARTIAL_DRY_PREPARED", "缺少水桶，已按 prepare_dirt 交付平地");
          return;
        }
        pauseWithCode("INSUFFICIENT_WATER_BUCKETS", "背包缺少满水桶：" + msg);
        return;
      }
      if (currentOpSpec != null
          && currentOpSpec.kind() == TerraformPlanner.OpKind.SEED
          && "MISSING_ITEM".equals(code)) {
        activeOp = null;
        pauseWithCode("MISSING_SEEDS", "缺少作物种子，已停在播种格前（恢复后不再重复锄地）");
        return;
      }
      if (currentOpSpec != null) {
        recordSkip(currentOpSpec, code, msg);
        skippedOps.add(currentOpSpec.id());
      }
      activeOp = null;
      currentOpSpec = null;
      return;
    }

    // Handle active navigation to next stance
    if (currentGoalStance != null) {
      int r = nav.tick();
      if (r == 0) return;
      if (r < 0) {
        unreachableStances.add(pack3D(currentGoalStance.getX(), currentGoalStance.getY(), currentGoalStance.getZ()));
        if (currentTargetForStance != null) {
          for (var op : plan.ops()) {
            if (!doneOps.contains(op.id())
                && !skippedOps.contains(op.id())
                && op.x() == currentTargetForStance.getX()
                && op.y() == currentTargetForStance.getY()
                && op.z() == currentTargetForStance.getZ()) {
              int rc = stanceRetries.merge(op.id(), 1, Integer::sum);
              if (rc > 2) {
                recordSkip(op, "STANCE_UNREACHABLE", "连续尝试多个站位均不可达：" + nav.error());
                skippedOps.add(op.id());
              }
            }
          }
        }
        currentGoalStance = null;
        currentTargetForStance = null;
        rejectedAtStance.clear();
        return;
      }
      // Arrived cleanly
      nav.stop();
      lastArrivedStance = currentGoalStance;
      opsAtLastArrival = verifiedOps;
      currentGoalStance = null;
      currentTargetForStance = null;
      rejectedAtStance.clear();
    }

    if (pendingSeedPos != null) {
      startImmediateSeed(pendingSeedPos);
      return;
    }

    // Collect ready ops in the current active phase and layer
    advancePhaseCursor();
    List<TerraformPlanner.WorkOp> ready = collectCurrentLayerReadyOps();
    if (ready.isEmpty()) {
      String finalState = skippedOps.isEmpty() ? "complete" : "partial";
      finishTerminal(
          finalState,
          skippedOps.isEmpty() ? "NONE" : "PARTIAL_SKIPPED",
          skippedOps.isEmpty() ? "地形作业全部核实完成" : "地形作业完成，部分目标因约束跳过");
      return;
    }

    // Priority 1: Exhaust current stance without walking (up to MAX_REACH_CHECKS_PER_TICK checks)
    Vec3 eye = mc.player.getEyePosition();
    BlockPos feet = Navigator.standingCell();
    TerraformPlanner.WorkOp bestLocal = null;
    double bestDistSq = Double.MAX_VALUE;
    int checked = 0;
    for (TerraformPlanner.WorkOp op : ready) {
      if (rejectedAtStance.contains(op.id())) continue;
      if (checked >= MAX_REACH_CHECKS_PER_TICK) break;
      BlockPos tp = new BlockPos(op.x(), op.y(), op.z());
      if (op.kind() == TerraformPlanner.OpKind.SEED
          && !mc.level.getBlockState(tp.below()).is(Blocks.FARMLAND)) {
        continue;
      }
      if (tp.distSqr(feet) > 36) continue;
      checked++;
      if (!canActFromEye(op, tp, eye)) continue;
      if (op.phase() == TerraformPlanner.OpPhase.HOE_AND_SEED
          && op.kind() == TerraformPlanner.OpKind.HOE
          && !preservesFarmExitOnHoe(op)) {
        continue;
      }
      double d2 = eye.distanceToSqr(tp.getX() + 0.5, tp.getY() + 0.5, tp.getZ() + 0.5);
      if (op.phase() == TerraformPlanner.OpPhase.HOE_AND_SEED) {
        bestLocal = op;
        break; // Strictly follow backing-out BFS leaf order within reach!
      }
      if (d2 < bestDistSq) {
        bestDistSq = d2;
        bestLocal = op;
      }
    }

    if (bestLocal != null) {
      launchOp(bestLocal);
      return;
    }

    // Break zero-progress arrival loops when LOS or placement fails after moving to a candidate stance
    if (lastArrivedStance != null && verifiedOps == opsAtLastArrival) {
      noProgressStances.add(
          pack3D(lastArrivedStance.getX(), lastArrivedStance.getY(), lastArrivedStance.getZ()));
      noProgressStances.add(pack3D(feet.getX(), feet.getY(), feet.getZ()));
      if (lastStanceTargetOpId > 0) {
        int retries = stanceRetries.merge(lastStanceTargetOpId, 1, Integer::sum);
        if (retries > 3) {
          for (var op : ready) {
            if (op.id() == lastStanceTargetOpId) {
              recordSkip(op, "STANCE_NO_PROGRESS", "到达候选站位后仍无法执行目标");
              skippedOps.add(op.id());
              break;
            }
          }
          lastArrivedStance = null;
          opsAtLastArrival = -1;
          lastStanceTargetOpId = -1;
          return;
        }
      }
      lastArrivedStance = null;
      opsAtLastArrival = -1;
      lastStanceTargetOpId = -1;
    }

    // Priority 2: Walk to best next stance for current ready ops using actual reach/LOS checks
    boolean isFarmHoePhase =
        spec instanceof TerraformSpec.FarmGrid
            && currentPhaseIdx < TerraformPlanner.OpPhase.values().length
            && TerraformPlanner.OpPhase.values()[currentPhaseIdx]
                == TerraformPlanner.OpPhase.HOE_AND_SEED;

    List<TerraformSpec.Pos> targets = new ArrayList<>(ready.size());
    Map<Long, TerraformPlanner.WorkOp> readyByPos = new HashMap<>(ready.size() * 2);
    for (var op : ready) {
      if (isFarmHoePhase) {
        if (op.kind() == TerraformPlanner.OpKind.SEED) {
          if (!mc.level.getBlockState(new BlockPos(op.x(), op.y() - 1, op.z())).is(Blocks.FARMLAND)) {
            continue;
          }
        } else if (op.kind() == TerraformPlanner.OpKind.HOE && !preservesFarmExitOnHoeFrom(op, null)) {
          continue;
        }
      }
      targets.add(op.pos());
      readyByPos.putIfAbsent(pack3D(op.x(), op.y(), op.z()), op);
    }
    if (isFarmHoePhase && targets.isEmpty()) {
      for (var op : ready) {
        if (op.kind() == TerraformPlanner.OpKind.HOE
            || (op.kind() == TerraformPlanner.OpKind.SEED
                && mc.level
                    .getBlockState(new BlockPos(op.x(), op.y() - 1, op.z()))
                    .is(Blocks.FARMLAND))) {
          targets.add(op.pos());
          readyByPos.putIfAbsent(pack3D(op.x(), op.y(), op.z()), op);
        }
      }
    }
    if (targets.isEmpty()) return;

    TerraformSpec.Pos curStand = playerStandPos();
    Predicate<TerraformSpec.Pos> walkable =
        p -> {
          BlockPos bp = new BlockPos(p.x(), p.y(), p.z());
          if (unreachableStances.contains(pack3D(p.x(), p.y(), p.z()))) return false;
          if (!mc.level.hasChunkAt(bp)) return false;
          if (!Navigator.stand(bp)) return false;
          if (isFarmHoePhase && !isSafeFarmStanceCell(bp)) return false;
          return true;
        };
    BiPredicate<TerraformSpec.Pos, TerraformSpec.Pos> edge =
        (a, b) -> !isFarmHoePhase || isSafeFarmEdge(new BlockPos(a.x(), a.y(), a.z()), new BlockPos(b.x(), b.y(), b.z()));
    BiPredicate<TerraformSpec.Pos, TerraformSpec.Pos> canAct =
        (stPos, tgtPos) -> {
          if (stPos.equals(curStand)) return false;
          if (noProgressStances.contains(pack3D(stPos.x(), stPos.y(), stPos.z()))) return false;
          if (stPos.x() == tgtPos.x()
              && stPos.z() == tgtPos.z()
              && (stPos.y() == tgtPos.y() + 1 || stPos.y() == tgtPos.y())) {
            return false;
          }
          Vec3 hypEye = new Vec3(stPos.x() + 0.5, stPos.y() + mc.player.getEyeHeight(), stPos.z() + 0.5);
          BlockPos targetBp = new BlockPos(tgtPos.x(), tgtPos.y(), tgtPos.z());
          if (hypEye.distanceToSqr(targetBp.getX() + 0.5, targetBp.getY() + 0.5, targetBp.getZ() + 0.5)
              > 20.5) {
            return false;
          }
          TerraformPlanner.WorkOp op = readyByPos.get(pack3D(tgtPos.x(), tgtPos.y(), tgtPos.z()));
          return op != null && canActFromEye(op, targetBp, hypEye);
        };

    var choiceOpt =
        TerraformPlanner.selectNextStance(
            curStand, walkable, edge, targets, canAct, 512);
    if (choiceOpt.isEmpty()) {
      for (var op : ready) {
        recordSkip(op, "NO_REACHABLE_STANCE", "周围找不到安全且可达的站位");
        skippedOps.add(op.id());
      }
      return;
    }

    var choice = choiceOpt.get();
    BlockPos goal = new BlockPos(choice.stanceX(), choice.stanceY(), choice.stanceZ());
    if (goal.equals(feet)) {
      noProgressStances.add(pack3D(goal.getX(), goal.getY(), goal.getZ()));
      return;
    }
    var firstTgt = targets.get(choice.firstOpIndex());
    BlockPos tgtBp = new BlockPos(firstTgt.x(), firstTgt.y(), firstTgt.z());
    TerraformPlanner.WorkOp firstOp = readyByPos.get(pack3D(firstTgt.x(), firstTgt.y(), firstTgt.z()));
    lastStanceTargetOpId = firstOp != null ? firstOp.id() : -1;

    boolean started =
        isFarmHoePhase
            ? nav.startKnownWalk(
                goal,
                bp -> !mc.level.getBlockState(bp.below()).is(Blocks.FARMLAND) && Navigator.stand(bp),
                this::isSafeFarmEdge)
            : nav.startExact(goal);
    if (!started) {
      unreachableStances.add(pack3D(goal.getX(), goal.getY(), goal.getZ()));
      return;
    }
    currentGoalStance = goal;
    currentTargetForStance = tgtBp;
    stanceMoves++;
  }

  private void startRefillFromPool(TerraformSpec.Pos poolMin) {
    var level = mc().level;
    BlockPos standCell = Navigator.standingCell();
    BlockPos found = null;
    BlockPos standNextToPool = null;
    double bestDistSq = Double.MAX_VALUE;
    for (int dx = 0; dx <= 1; dx++) {
      for (int dz = 0; dz <= 1; dz++) {
        BlockPos cand = new BlockPos(poolMin.x() + dx, poolMin.y(), poolMin.z() + dz);
        if (!level.hasChunkAt(cand)
            || !level.getFluidState(cand).isSource()
            || !level.getFluidState(cand).is(FluidTags.WATER)) {
          continue;
        }
        if (found == null) found = cand;
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
          BlockPos s = cand.relative(d).above();
          if (Navigator.stand(s) && !level.getBlockState(s.below()).is(Blocks.FARMLAND)) {
            double dSq = standCell.distSqr(s);
            if (dSq < bestDistSq) {
              bestDistSq = dSq;
              standNextToPool = s;
              found = cand;
            }
          }
        }
      }
    }
    if (found == null) {
      pauseWithCode("WATER_POOL_DEPLETED", "指定的 2x2 取水池当前无可用源水，请补充后恢复");
      return;
    }
    phase = EnginePhase.REFILL_WAIT;
    refillCell = found;
    refillWaitTicks = 0;
    if (standNextToPool != null && standCell.distSqr(found) > 4) {
      if (!nav.startExact(standNextToPool)) {
        pauseWithCode("WATER_POOL_UNREACHABLE", "无法规划前往 2x2 取水池的路径");
        return;
      }
      refillStandGoal = standNextToPool;
      activeOp = null;
      stanceMoves++;
      return;
    }
    refillStandGoal = null;
    activeOp =
        new FixedStanceOp.Use(
            FixedStanceOp.Use.Kind.REFILL_BUCKET, found, "minecraft:bucket", null);
  }

  private void tickRefillWait() {
    if (refillStandGoal != null) {
      int r = nav.tick();
      if (r == 0) return;
      nav.stop();
      BlockPos arrived = refillStandGoal;
      refillStandGoal = null;
      if (r < 0) {
        pauseWithCode("WATER_POOL_UNREACHABLE", "无法走到 2x2 取水池旁(" + arrived.toShortString() + ")：" + nav.error());
        return;
      }
      activeOp =
          new FixedStanceOp.Use(
              FixedStanceOp.Use.Kind.REFILL_BUCKET, refillCell, "minecraft:bucket", null);
      return;
    }
    if (activeOp != null) {
      var st = activeOp.tick();
      if (st == FixedStanceOp.Status.RUNNING) return;
      if (activeOp.isManagingScreen()) activeOp.cancel();
      if (st == FixedStanceOp.Status.NEED_STANCE && refillCell != null) {
        var level = mc().level;
        BlockPos standCell = Navigator.standingCell();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
          BlockPos s = refillCell.relative(d).above();
          if (!s.equals(standCell) && Navigator.stand(s) && !level.getBlockState(s.below()).is(Blocks.FARMLAND)) {
            double dSq = standCell.distSqr(s);
            if (dSq < bestDistSq) {
              bestDistSq = dSq;
              best = s;
            }
          }
        }
        activeOp = null;
        if (best != null && nav.startExact(best)) {
          refillStandGoal = best;
          stanceMoves++;
          return;
        }
        pauseWithCode("WATER_POOL_UNREACHABLE", "无法贴近 2x2 取水池取水");
        return;
      }
      if (st != FixedStanceOp.Status.VERIFIED) {
        String code = activeOp.reasonCode();
        String msg = activeOp.detail();
        activeOp = null;
        if ("INVENTORY_FULL".equals(code)) {
          pauseWithCode("INVENTORY_FULL", msg);
        } else if ("MISSING_ITEM".equals(code)) {
          pauseWithCode("MISSING_EMPTY_BUCKET", "背包缺少空桶，无法从 2x2 水池取水：" + msg);
        } else {
          pauseWithCode("WATER_POOL_DEPLETED", "水池取水失败(" + code + ")：" + msg);
        }
        return;
      }
      activeOp = null;
      refillWaitTicks = 0;
      return;
    }
    // Wait up to 20 ticks for 2x2 pool infinite regeneration check before returning to WORK
    if (refillCell != null) {
      var fs = mc().level.getFluidState(refillCell);
      if (fs.is(FluidTags.WATER) && fs.isSource()) {
        refillCell = null;
        phase = EnginePhase.WORK;
        return;
      }
      if (++refillWaitTicks > 20) {
        pauseWithCode("WATER_POOL_DEPLETED", "2x2水池未能重新再生为源水（可能规则禁用或池底受损）");
        return;
      }
    } else {
      phase = EnginePhase.WORK;
    }
  }

  private void startImmediateSeed(BlockPos soilPos) {
    if (!(spec instanceof TerraformSpec.FarmGrid fg) || fg.crop() == null) {
      pendingSeedPos = null;
      return;
    }
    Block cropBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(fg.crop().block()));
    TerraformPlanner.WorkOp matched = null;
    int cropY = soilPos.getY() + 1;
    for (var candidate : plan.ops()) {
      if (candidate.kind() == TerraformPlanner.OpKind.SEED
          && candidate.x() == soilPos.getX()
          && candidate.y() == cropY
          && candidate.z() == soilPos.getZ()
          && !doneOps.contains(candidate.id())
          && !skippedOps.contains(candidate.id())) {
        matched = candidate;
        break;
      }
    }
    currentOpSpec =
        matched != null
            ? matched
            : new TerraformPlanner.WorkOp(
                -1,
                TerraformPlanner.OpPhase.HOE_AND_SEED,
                TerraformPlanner.OpKind.SEED,
                soilPos.getX(),
                cropY,
                soilPos.getZ(),
                fg.crop().seed(),
                "minecraft:air");
    activeOp =
        new FixedStanceOp.Use(
            FixedStanceOp.Use.Kind.SEED, soilPos, fg.crop().seed(), cropBlock);
    pendingSeedPos = null;
  }

  private void launchOp(TerraformPlanner.WorkOp op) {
    BlockPos tp = new BlockPos(op.x(), op.y(), op.z());
    currentOpSpec = op;
    activeOp =
        switch (op.kind()) {
          case BREAK -> new FixedStanceOp.Break(tp, FixedStanceOp.BreakAuthority.BULK);
          case PLACE -> new FixedStanceOp.Place(tp, op.itemId(), null, false);
          case PLACE_WATER ->
              new FixedStanceOp.Use(
                  FixedStanceOp.Use.Kind.PLACE_WATER, tp, "minecraft:water_bucket", null);
          case PLACE_SLAB -> {
            var slabProps = new JsonObject();
            slabProps.addProperty("type", "top");
            yield new FixedStanceOp.Place(tp, op.itemId(), slabProps, true);
          }
          case HOE ->
              new FixedStanceOp.Use(
                  FixedStanceOp.Use.Kind.HOE, tp, "hoe", null);
          case SEED -> {
            Block cropBlk =
                (spec instanceof TerraformSpec.FarmGrid fg && fg.crop() != null)
                    ? BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(fg.crop().block()))
                    : Blocks.WHEAT;
            yield new FixedStanceOp.Use(
                FixedStanceOp.Use.Kind.SEED, tp.below(), op.itemId(), cropBlk);
          }
        };
  }

  private void onOpVerified(TerraformPlanner.WorkOp op, FixedStanceOp doneOp) {
    verifiedOps++;
    noProgressStances.clear();
    unreachableStances.clear();
    lastArrivedStance = null;
    opsAtLastArrival = -1;
    lastStanceTargetOpId = -1;
    if (op != null) {
      if (op.id() > 0) doneOps.add(op.id());
      verifiedByPhase.merge(op.phase(), 1, Integer::sum);
      if (op.isJournalable()) {
        if (doneOp instanceof FixedStanceOp.Break brk && brk.beforeState() != null) {
          RollbackJob.recordBreak(
              new BlockPos(op.x(), op.y(), op.z()), brk.beforeState(), "terraform_run", jobId);
          confirmedJournalable++;
        } else if (doneOp instanceof FixedStanceOp.Place plc && plc.afterState() != null) {
          RollbackJob.recordPlace(
              new BlockPos(op.x(), op.y(), op.z()),
              plc.beforeState(),
              plc.afterState(),
              "terraform_run",
              jobId);
          confirmedJournalable++;
        }
      }
      if (op.kind() == TerraformPlanner.OpKind.HOE
          && spec instanceof TerraformSpec.FarmGrid fg
          && fg.crop() != null) {
        pendingSeedPos = new BlockPos(op.x(), op.y(), op.z());
      } else if (op.kind() == TerraformPlanner.OpKind.SEED) {
        pendingSeedPos = null;
      }
      if (op.id() > 0 && verifiedOps % 16 == 0) saveCheckpoint("running");
    }
  }

  private boolean canActFromEye(TerraformPlanner.WorkOp op, BlockPos tp, Vec3 eye) {
    return switch (op.kind()) {
      case BREAK -> Reach.mineFrom(tp, eye) != null;
      case PLACE -> Reach.placementFrom(tp, eye) != null;
      case PLACE_SLAB -> {
        var props = new JsonObject();
        props.addProperty("type", "top");
        yield Reach.placementFrom(tp, eye, null, hit -> {
          boolean top = hit.getDirection() == net.minecraft.core.Direction.DOWN
              || (hit.getDirection().getAxis().isHorizontal()
                  && hit.getLocation().y - hit.getBlockPos().getY() > 0.5);
          return top;
        }) != null;
      }
      case PLACE_WATER -> Reach.topFrom(tp.below(), eye) != null;
      case HOE -> Reach.topFrom(tp, eye) != null;
      case SEED ->
          mc().level != null
              && mc().level.getBlockState(tp.below()).is(Blocks.FARMLAND)
              && Reach.topFrom(tp.below(), eye) != null;
    };
  }

  private boolean preservesFarmExitOnHoe(TerraformPlanner.WorkOp hoeOp) {
    return preservesFarmExitOnHoeFrom(hoeOp, Navigator.standingCell());
  }

  private boolean preservesFarmExitOnHoeFrom(TerraformPlanner.WorkOp hoeOp, BlockPos feetCell) {
    if (plan == null || plan.farmLayout() == null) return true;
    var layout = plan.farmLayout();
    TerraformSpec.Pos exit = layout.exitCell();
    if (exit == null) return true;
    int playerGroundX = feetCell != null ? feetCell.getX() : Integer.MIN_VALUE;
    int playerGroundZ = feetCell != null ? feetCell.getZ() : Integer.MIN_VALUE;
    if (feetCell != null && hoeOp.x() == playerGroundX && hoeOp.z() == playerGroundZ) return false;

    int ty = ((TerraformSpec.FarmGrid) spec).targetY();
    var level = mc().level;
    Set<Long> passable = new HashSet<>();
    Set<Long> needed = new HashSet<>();
    for (var op : plan.ops()) {
      if (op.phase() != TerraformPlanner.OpPhase.HOE_AND_SEED
          || op.kind() != TerraformPlanner.OpKind.HOE) continue;
      if (doneOps.contains(op.id()) || skippedOps.contains(op.id())) continue;
      if (op.id() == hoeOp.id()) continue;
      long k = TerraformPlanner.packXZ(op.x(), op.z());
      passable.add(k);
      needed.add(k);
    }
    for (var w : layout.waterCells()) {
      BlockPos wp = new BlockPos(w.x(), ty, w.z());
      if (level.getBlockState(wp).getBlock() instanceof SlabBlock) {
        passable.add(TerraformPlanner.packXZ(w.x(), w.z()));
      }
    }
    for (var rp : layout.reservedPaths()) {
      passable.add(TerraformPlanner.packXZ(rp.x(), rp.z()));
    }
    if (feetCell != null && spec.box().contains(playerGroundX, ty, playerGroundZ)) {
      long pk = TerraformPlanner.packXZ(playerGroundX, playerGroundZ);
      passable.add(pk);
      needed.add(pk);
    }
    passable.add(TerraformPlanner.packXZ(exit.x(), exit.z()));

    // Check exit reachable from player's current foot cell if on field, plus all remaining soil
    ArrayDeque<Long> q = new ArrayDeque<>();
    Set<Long> seen = new HashSet<>();
    long root = TerraformPlanner.packXZ(exit.x(), exit.z());
    q.add(root);
    seen.add(root);
    while (!q.isEmpty()) {
      long c = q.removeFirst();
      int cx = TerraformPlanner.unpackX(c), cz = TerraformPlanner.unpackZ(c);
      for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
        long nk = TerraformPlanner.packXZ(cx + d[0], cz + d[1]);
        if (!seen.contains(nk) && passable.contains(nk)) {
          seen.add(nk);
          q.addLast(nk);
        }
      }
    }
    for (long req : needed) {
      if (!seen.contains(req)) return false;
    }
    return true;
  }

  private boolean isSafeFarmStanceCell(BlockPos standCell) {
    var level = mc().level;
    BlockPos below = standCell.below();
    if (!level.hasChunkAt(below)) return false;
    BlockState st = level.getBlockState(below);
    if (st.getBlock() instanceof FarmBlock) return false;
    if (!st.getFluidState().isEmpty() && !(st.getBlock() instanceof SlabBlock)) return false;
    return true;
  }

  private boolean isSafeFarmEdge(BlockPos from, BlockPos to) {
    if (from.getY() != to.getY()) return false;
    if (!isSafeFarmStanceCell(from) || !isSafeFarmStanceCell(to)) return false;
    var level = mc().level;
    // Check swept AABB footprint between from and to so player shoulders cannot clip neighboring farmland
    double minX = Math.min(from.getX(), to.getX()) + 0.2;
    double maxX = Math.max(from.getX(), to.getX()) + 0.8;
    double minZ = Math.min(from.getZ(), to.getZ()) + 0.2;
    double maxZ = Math.max(from.getZ(), to.getZ()) + 0.8;
    int bx0 = (int) Math.floor(minX), bx1 = (int) Math.floor(maxX);
    int bz0 = (int) Math.floor(minZ), bz1 = (int) Math.floor(maxZ);
    int fy = from.getY() - 1;
    for (int x = bx0; x <= bx1; x++) {
      for (int z = bz0; z <= bz1; z++) {
        BlockPos p = new BlockPos(x, fy, z);
        if (!level.hasChunkAt(p)) return false;
        BlockState st = level.getBlockState(p);
        if (st.getBlock() instanceof FarmBlock) return false;
        if (!st.getFluidState().isEmpty() && !(st.getBlock() instanceof SlabBlock)) return false;
      }
    }
    return true;
  }

  private void advancePhaseCursor() {
    if (plan == null) return;
    var phases = TerraformPlanner.OpPhase.values();
    while (currentPhaseIdx < phases.length) {
      TerraformPlanner.OpPhase ph = phases[currentPhaseIdx];
      List<TerraformPlanner.WorkOp> pendingInPhase = new ArrayList<>();
      for (var op : plan.ops()) {
        if (op.phase() == ph && !doneOps.contains(op.id()) && !skippedOps.contains(op.id())) {
          pendingInPhase.add(op);
        }
      }
      if (pendingInPhase.isEmpty()) {
        currentPhaseIdx++;
        continue;
      }
      if (ph == TerraformPlanner.OpPhase.CUT) {
        currentLayerY = pendingInPhase.stream().mapToInt(TerraformPlanner.WorkOp::y).max().orElse(0);
      } else if (ph == TerraformPlanner.OpPhase.FILL) {
        currentLayerY = pendingInPhase.stream().mapToInt(TerraformPlanner.WorkOp::y).min().orElse(0);
      } else {
        currentLayerY = pendingInPhase.get(0).y();
      }
      return;
    }
  }

  private List<TerraformPlanner.WorkOp> collectCurrentLayerReadyOps() {
    if (plan == null) return List.of();
    var phases = TerraformPlanner.OpPhase.values();
    if (currentPhaseIdx >= phases.length) return List.of();
    TerraformPlanner.OpPhase ph = phases[currentPhaseIdx];
    List<TerraformPlanner.WorkOp> out = new ArrayList<>();
    for (var op : plan.ops()) {
      if (op.phase() != ph) continue;
      if (doneOps.contains(op.id()) || skippedOps.contains(op.id())) continue;
      if ((ph == TerraformPlanner.OpPhase.CUT || ph == TerraformPlanner.OpPhase.FILL)
          && op.y() != currentLayerY) continue;
      out.add(op);
    }
    return out;
  }

  private void recordSkip(TerraformPlanner.WorkOp op, String code, String msg) {
    int c = skipCounts.merge(code, 1, Integer::sum);
    if (c <= 16 && skipSamples.size() < 48) {
      var o = new JsonObject();
      o.addProperty("x", op.x());
      o.addProperty("y", op.y());
      o.addProperty("z", op.z());
      o.addProperty("code", code);
      o.addProperty("detail", msg);
      skipSamples.add(o);
    }
  }

  private void pauseWithCode(String code, String message) {
    if (finalized) return;
    finalized = true;
    cancelSubOps();
    terminalState = "paused";
    terminalReason = code;
    saveCheckpoint("paused");
    var summary = buildStatusJson("paused");
    summary.addProperty("reason_code", code);
    lastSnapshot = summary.deepCopy();
    finish("部分完成：[" + code + "] " + message + summary);
  }

  private void abortWithCode(String code, String message) {
    if (finalized) return;
    finalized = true;
    cancelSubOps();
    terminalState = "aborted";
    terminalReason = code;
    saveCheckpoint("aborted");
    var summary = buildStatusJson("aborted");
    summary.addProperty("reason_code", code);
    lastSnapshot = summary.deepCopy();
    finish("部分完成：[" + code + "] " + message + summary);
  }

  private void finishTerminal(String state, String code, String message) {
    if (finalized) return;
    finalized = true;
    cancelSubOps();
    terminalState = state;
    terminalReason = code;
    saveCheckpoint(state);
    var summary = buildStatusJson(state);
    summary.addProperty("reason_code", code);
    lastSnapshot = summary.deepCopy();
    if ("complete".equals(state)) {
      finish("成功：" + message + "。" + summary);
    } else if ("partial".equals(state)) {
      finish("部分完成：[" + code + "] " + message + summary);
    } else {
      finish("失败：[" + code + "] " + message + summary);
    }
  }

  private void cancelSubOps() {
    PlayerControls.release();
    nav.stop();
    if (activeOp != null) {
      activeOp.cancel();
      activeOp = null;
    }
    currentGoalStance = null;
    refillStandGoal = null;
  }

  @Override
  void stop() {
    cancelSubOps();
    super.stop();
  }

  @Override
  String interrupted() {
    if (!finalized) {
      terminalState = "paused";
      saveCheckpoint("paused");
      return "地形施工已中断并保存断点(job_id=" + jobId + ")。";
    }
    return "";
  }

  private void saveCheckpoint(String state) {
    if (Store.data == null) return;
    if (Store.data.checkpoints == null) Store.data.checkpoints = new LinkedHashMap<>();
    JsonObject cp = buildStatusJson(state);
    var completedArr = new JsonArray();
    for (int id : doneOps) completedArr.add(id);
    cp.add("completed_ops", completedArr);
    cp.add("spec", spec.toJson());
    if (pendingSeedPos != null) {
      var ps = new JsonObject();
      ps.addProperty("x", pendingSeedPos.getX());
      ps.addProperty("y", pendingSeedPos.getY());
      ps.addProperty("z", pendingSeedPos.getZ());
      cp.add("pending_seed_pos", ps);
    }
    Store.data.checkpoints.put(worldDimKey + "|terraform|" + jobId, cp);
    Store.data.checkpoints.put(worldDimKey + "|terraform|latest", cp.deepCopy());
    Store.saveSoon();
  }

  private JsonObject buildStatusJson(String state) {
    var o = new JsonObject();
    o.addProperty("schema_version", CHECKPOINT_SCHEMA);
    o.addProperty("job_id", jobId);
    o.addProperty("scope", worldDimKey);
    o.addProperty("state", state);
    o.addProperty("mode", spec.mode().name().toLowerCase(Locale.ROOT));
    o.addProperty("engine_phase", phase.name().toLowerCase(Locale.ROOT));
    if (plan != null) o.addProperty("plan_hash", plan.planHash());
    o.add("bounds", spec.box().toJson());
    int totalEstOps = plan == null ? priorVerifiedOps : priorVerifiedOps + plan.ops().size();
    o.addProperty("verified_ops", verifiedOps);
    o.addProperty("estimated_ops", totalEstOps);
    o.addProperty("stance_moves", stanceMoves);
    o.addProperty("elapsed_ms", System.currentTimeMillis() - startedMs);

    var vp = new JsonObject();
    for (var e : verifiedByPhase.entrySet()) {
      vp.addProperty(e.getKey().name().toLowerCase(Locale.ROOT), e.getValue());
    }
    o.add("verified_by_phase", vp);

    String dim = mc().level != null ? mc().level.dimension().location().toString() : "";
    int retained =
        Store.data != null
            ? RollbackJournal.countRetained(Store.rollbackLog(), dim, jobId)
            : 0;
    int estJourn =
        plan == null
            ? confirmedJournalable
            : priorConfirmedJournalable + plan.rollbackCoverage().estimatedJournalable();
    int nonRev = plan == null ? Math.max(0, verifiedOps - confirmedJournalable) : plan.rollbackCoverage().notReversible();
    int evicted = Math.max(0, confirmedJournalable - retained);
    var rb =
        new TerraformPlanner.RollbackCoverage(
            RollbackJournal.MAX_ENTRIES,
            plan == null ? verifiedOps : totalEstOps,
            estJourn,
            confirmedJournalable,
            retained,
            evicted,
            nonRev);
    o.add("rollback_coverage", rb.toJson());

    if (!skipCounts.isEmpty()) {
      var sc = new JsonObject();
      for (var e : skipCounts.entrySet()) sc.addProperty(e.getKey(), e.getValue());
      o.add("skipped_totals", sc);
      var sa = new JsonArray();
      for (var s : skipSamples) sa.add(s);
      o.add("skipped_samples", sa);
    }
    if (plan != null && plan.farmLayout() != null) {
      o.add("irrigation_coverage", plan.farmLayout().irrigationJson());
    }
    o.addProperty("active_now", Actions.job == this && !finalized);
    return o;
  }

  private static TerraformSpec.Pos playerStandPos() {
    BlockPos c = Navigator.standingCell();
    return new TerraformSpec.Pos(c.getX(), c.getY(), c.getZ());
  }

  private static TerraformPlanner.CellState readLiveCell(
      Level level, BlockPos pos, TerraformSpec spec) {
    if (!level.hasChunkAt(pos)) return TerraformPlanner.CellState.unknown();
    BlockState st = level.getBlockState(pos);
    String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
    var fluid = st.getFluidState();

    TerraformPlanner.BlockKind kind;
    if (st.isAir()) {
      kind = TerraformPlanner.BlockKind.AIR;
    } else if (fluid.is(FluidTags.LAVA)) {
      kind = TerraformPlanner.BlockKind.LAVA;
    } else if (fluid.is(FluidTags.WATER)) {
      kind =
          fluid.isSource()
              ? TerraformPlanner.BlockKind.WATER_SOURCE
              : TerraformPlanner.BlockKind.WATER_FLOW;
    } else if (!fluid.isEmpty()) {
      kind = TerraformPlanner.BlockKind.LAVA;
    } else if (st.getDestroySpeed(level, pos) < 0
        || st.hasBlockEntity()
        || st.getBlock() instanceof BedBlock
        || st.is(Blocks.NETHER_PORTAL)
        || st.is(Blocks.END_PORTAL)
        || !BreakPolicy.denial(id, st.hasBlockEntity(), false, false, false, false).isBlank()) {
      kind = TerraformPlanner.BlockKind.PROTECTED;
    } else if (st.getBlock() instanceof FarmBlock) {
      kind = TerraformPlanner.BlockKind.FARMLAND;
    } else if (st.getBlock() instanceof FallingBlock) {
      kind = TerraformPlanner.BlockKind.FALLING_SOLID;
    } else if (st.canBeReplaced() && st.getCollisionShape(level, pos).isEmpty()) {
      kind = TerraformPlanner.BlockKind.REPLACEABLE_PLANT;
    } else if (spec.cutBlocks().contains(id)) {
      kind = TerraformPlanner.BlockKind.NATURAL_SOLID;
    } else {
      kind = TerraformPlanner.BlockKind.OTHER_SOLID;
    }

    boolean waterlogged =
        st.hasProperty(BlockStateProperties.WATERLOGGED)
            && Boolean.TRUE.equals(st.getValue(BlockStateProperties.WATERLOGGED));
    boolean solidSupport =
        !st.getCollisionShape(level, pos).isEmpty()
            && (fluid.isEmpty() || (st.getBlock() instanceof SlabBlock && st.getValue(SlabBlock.TYPE) == SlabType.TOP))
            && !(st.getBlock() instanceof ScaffoldingBlock);
    boolean walkableTop = solidSupport && kind != TerraformPlanner.BlockKind.FARMLAND;

    return new TerraformPlanner.CellState(
        true, id, kind, st.hasBlockEntity(), waterlogged, solidSupport, walkableTop);
  }

  private static long pack3D(int x, int y, int z) {
    return TerraformPlanner.packXYZ(x, y, z);
  }
}
