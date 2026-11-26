package dev.mibao.companion;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Layer-by-layer client blueprint construction job owning a managed {@link PlaceJob} / {@link
 * MiningJob} directly (so {@code Actions.job} always stays {@code BlueprintBuildJob}), with 3+1
 * wave dependency ordering, 3D multi-mode automatic scaffolding + temp-support micro-cycles,
 * structured failure diagnostics, SHA-256 checkpoint isolation, and LIFO safe teardown.
 */
public final class BlueprintBuildJob extends Actions.Job {
  private static final String CHECKPOINT_KEY = "blueprint_active";
  private static final int MAX_RECORDED_FAILURES = 16;

  private record ScaffoldEntry(int wx, int wy, int wz, String blockId, String kind) {
    JsonObject toJson() {
      var o = new JsonObject();
      o.addProperty("wx", wx);
      o.addProperty("wy", wy);
      o.addProperty("wz", wz);
      o.addProperty("block", blockId);
      o.addProperty("kind", kind);
      return o;
    }

    static ScaffoldEntry fromJson(JsonObject o) {
      if (o == null || !o.has("wx") || !o.has("wy") || !o.has("wz") || !o.has("block")) return null;
      return new ScaffoldEntry(
          o.get("wx").getAsInt(),
          o.get("wy").getAsInt(),
          o.get("wz").getAsInt(),
          o.get("block").getAsString(),
          o.has("kind") ? o.get("kind").getAsString() : "RAMP_STEP");
    }
  }

  private record PendingScaffold(
      int wx,
      int wy,
      int wz,
      String blockId,
      String kind,
      boolean isTempSupport,
      int retryStepIdx,
      String bandKey) {}

  private final String fileName;
  private final String displayName;
  private final String blueprintSha256;
  private final String worldDim;
  private final int anchorX, anchorY, anchorZ, rotation, yMin, yMax, maxBlocks;
  private final String mirror;
  private final BlueprintScaffoldPlanner.Mode requestedScaffoldMode;
  private final String requestedScaffoldBlock;
  private final boolean keepScaffold;
  private final int bpMinX, bpMaxX, bpMinZ, bpMaxZ, baseGroundY;

  private final List<BlueprintPlanner.BuildStep> steps;
  private final Set<String> blueprintSolidKeys = new HashSet<>();
  private final Map<String, Integer> missingCounts = new LinkedHashMap<>();
  private final List<JsonObject> failures = new ArrayList<>();
  private final List<JsonObject> stateMismatchSamples = new ArrayList<>();
  private final List<String> unloadedSamples = new ArrayList<>();
  private final ArrayDeque<Integer> deferredRetryQueue = new ArrayDeque<>();
  private final Set<Integer> retriedSteps = new HashSet<>();
  private final Set<Integer> tempTriedSteps = new HashSet<>();
  private final Set<String> elevationPlannedBands = new HashSet<>();
  private final Map<Integer, Integer> stepScaffoldAttempts = new HashMap<>();
  private final Set<String> solidFallbackBands = new HashSet<>();
  private final ArrayDeque<PendingScaffold> pendingScaffoldPlacements = new ArrayDeque<>();
  private final List<ScaffoldEntry> scaffoldStack = new ArrayList<>();
  private Navigator teardownApproachNav;
  private ScaffoldEntry teardownApproachEntry;
  private Consumer<Boolean> teardownApproachCallback;
  private boolean teardownApproachIsStepOff;
  private boolean teardownStepOffTried;
  private final Set<String> scaffoldKeys = new HashSet<>();
  private final Set<String> removedScaffoldItemIds = new LinkedHashSet<>();
  private final Deque<String> pendingDropCollectIds = new ArrayDeque<>();

  private int cursor,
      placed,
      alreadyMatched,
      stateMismatch,
      skippedMissingItem,
      skippedUnsupported,
      blockedExisting,
      unloaded,
      failed,
      scaffoldsPlaced,
      scaffoldsRemoved,
      scaffoldsCollected,
      teardownFailed;
  private PlaceJob activePlace;
  private MiningJob activeMine;
  private CollectionJob activeCollect;
  private BlueprintPlanner.BuildStep activeStep;
  private int activeStepIndex = -1;
  private final ArrayDeque<ScaffoldEntry> pendingTempCleanups = new ArrayDeque<>();
  private int tempCleanupTargetStepIdx = -1;
  private boolean tearingDown;
  private boolean teardownClimbCleared;
  private boolean dropCollectStarted;
  private final ScaffoldJob.ClimbExit climbExit = new ScaffoldJob.ClimbExit();
  private String needScaffoldError = "";
  private boolean finishedNormally;
  private boolean checkpointCleared;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public BlueprintBuildJob(
      String name,
      int anchorX,
      int anchorY,
      int anchorZ,
      int rotation,
      String mirror,
      int yMin,
      int yMax,
      Consumer<String> done)
      throws IOException {
    this(name, anchorX, anchorY, anchorZ, rotation, mirror, yMin, yMax, 0, "auto", "", false, done);
  }

  public BlueprintBuildJob(
      String name,
      int anchorX,
      int anchorY,
      int anchorZ,
      int rotation,
      String mirror,
      int yMin,
      int yMax,
      int maxBlocks,
      Consumer<String> done)
      throws IOException {
    this(
        name,
        anchorX,
        anchorY,
        anchorZ,
        rotation,
        mirror,
        yMin,
        yMax,
        maxBlocks,
        "auto",
        "",
        false,
        done);
  }

  public BlueprintBuildJob(
      String name,
      int anchorX,
      int anchorY,
      int anchorZ,
      int rotation,
      String mirror,
      int yMin,
      int yMax,
      int maxBlocks,
      String scaffoldMode,
      String scaffoldBlock,
      boolean keepScaffold,
      Consumer<String> done)
      throws IOException {
    this(
        name,
        anchorX,
        anchorY,
        anchorZ,
        rotation,
        mirror,
        yMin,
        yMax,
        maxBlocks,
        scaffoldMode,
        scaffoldBlock,
        keepScaffold,
        true,
        done);
  }

  private BlueprintBuildJob(
      String name,
      int anchorX,
      int anchorY,
      int anchorZ,
      int rotation,
      String mirror,
      int yMin,
      int yMax,
      int maxBlocks,
      String scaffoldMode,
      String scaffoldBlock,
      boolean keepScaffold,
      boolean saveInitialCheckpoint,
      Consumer<String> done)
      throws IOException {
    super(done);
    BlueprintLibrary.LoadedBlueprint loaded =
        BlueprintLibrary.loadWithMeta(name, rotation, mirror);
    BlueprintFormat.BlueprintSpec spec = loaded.spec();
    this.fileName = loaded.entry().name();
    this.displayName = spec.name;
    this.blueprintSha256 = loaded.sha256();
    this.worldDim = Store.worldDimKey();
    this.anchorX = anchorX;
    this.anchorY = anchorY;
    this.anchorZ = anchorZ;
    this.rotation = BlueprintFormat.normalizeRotation(rotation);
    this.mirror = BlueprintFormat.normalizeMirror(mirror);
    this.yMin = Math.max(0, yMin);
    this.yMax = Math.min(spec.sizeY - 1, yMax < 0 ? spec.sizeY - 1 : yMax);
    this.maxBlocks = Math.max(0, maxBlocks);
    this.requestedScaffoldMode = BlueprintScaffoldPlanner.parseMode(scaffoldMode);
    this.requestedScaffoldBlock = scaffoldBlock == null ? "" : scaffoldBlock.strip();
    this.keepScaffold = keepScaffold;
    this.bpMinX = anchorX;
    this.bpMaxX = anchorX + spec.sizeX - 1;
    this.bpMinZ = anchorZ;
    this.bpMaxZ = anchorZ + spec.sizeZ - 1;
    var p = mc().player;
    this.baseGroundY = p == null ? anchorY : Math.min(anchorY, p.blockPosition().getY());
    for (var v : spec.voxels) {
      blueprintSolidKeys.add(
          BlueprintScaffoldPlanner.posKey(anchorX + v.x(), anchorY + v.y(), anchorZ + v.z()));
    }
    this.steps =
        BlueprintPlanner.buildOrder(spec, anchorX, anchorY, anchorZ, this.yMin, this.yMax);
    if (saveInitialCheckpoint) saveCheckpoint("running");
  }

  public static String dryRunReport(
      String name,
      int anchorX,
      int anchorY,
      int anchorZ,
      int rotation,
      String mirror,
      int yMin,
      int yMax)
      throws IOException {
    BlueprintLibrary.LoadedBlueprint loaded =
        BlueprintLibrary.loadWithMeta(name, rotation, mirror);
    BlueprintFormat.BlueprintSpec spec = loaded.spec();
    int hi = yMax < 0 ? spec.sizeY - 1 : Math.min(spec.sizeY - 1, yMax);
    List<BlueprintPlanner.BuildStep> steps =
        BlueprintPlanner.buildOrder(spec, anchorX, anchorY, anchorZ, Math.max(0, yMin), hi);
    var mc = mc();
    int matched = 0, mismatch = 0, ready = 0, blocked = 0, unsupported = 0, unloadedCount = 0;
    boolean creative =
        mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.CREATIVE;
    Map<String, Integer> bag = BlueprintLibrary.playerBagItemCounts();
    Map<String, Integer> needed = new LinkedHashMap<>();
    var mismatchArr = new JsonArray();
    var blockedArr = new JsonArray();
    var unloadedArr = new JsonArray();

    for (BlueprintPlanner.BuildStep s : steps) {
      if (!isItemPlaceable(s.itemId())) {
        unsupported++;
        continue;
      }
      BlockPos p = new BlockPos(s.wx(), s.wy(), s.wz());
      if (mc.level == null || !mc.level.hasChunkAt(p)) {
        unloadedCount++;
        if (unloadedArr.size() < 8) unloadedArr.add(p.toShortString());
        continue;
      }
      BlockState cur = mc.level.getBlockState(p);
      String curId = BuiltInRegistries.BLOCK.getKey(cur.getBlock()).toString();
      Map<String, String> curProps = extractProperties(cur);
      boolean replaceable = cur.canBeReplaced() || BlueprintFormat.isAirBlock(curId);
      switch (BlueprintPlanner.classifyBlock(curId, curProps, replaceable, s)) {
        case MATCHED -> matched++;
        case STATE_MISMATCH -> {
          mismatch++;
          if (mismatchArr.size() < 12) {
            mismatchArr.add(sampleDiff(p, s, curId, curProps));
          }
        }
        case MISSING -> {
          ready++;
          needed.merge(s.itemId(), 1, Integer::sum);
        }
        case BLOCKED_EXISTING -> {
          blocked++;
          if (blockedArr.size() < 12) {
            blockedArr.add(sampleDiff(p, s, curId, curProps));
          }
        }
      }
    }

    var out = new JsonObject();
    out.addProperty("dry_run", true);
    out.addProperty("name", loaded.entry().name());
    out.addProperty("file_name", loaded.entry().name());
    out.addProperty("display_name", spec.name);
    out.addProperty("blueprint_sha256", loaded.sha256());
    out.addProperty("steps_in_range", steps.size());
    out.addProperty("already_matched", matched);
    out.addProperty("state_mismatch", mismatch);
    out.addProperty("ready_to_place", ready);
    out.addProperty("blocked_existing", blocked);
    out.addProperty("skipped_unsupported", unsupported);
    out.addProperty("unloaded", unloadedCount);
    out.add("scaffold_estimate", BlueprintScaffoldPlanner.estimateForBlueprint(spec));
    if (mismatchArr.size() > 0) out.add("state_mismatch_samples", mismatchArr);
    if (blockedArr.size() > 0) out.add("blocked_samples", blockedArr);
    if (unloadedArr.size() > 0) out.add("unloaded_samples", unloadedArr);
    if (!creative) {
      var shortArr = new JsonArray();
      for (var e : needed.entrySet()) {
        int have = bag.getOrDefault(e.getKey(), 0);
        if (have < e.getValue()) {
          var row = new JsonObject();
          row.addProperty("item", e.getKey());
          row.addProperty("need_remaining", e.getValue());
          row.addProperty("in_bag", have);
          shortArr.add(row);
        }
      }
      out.add("bag_shortage", shortArr);
    }
    return out.toString();
  }

  public static JsonObject statusJson() {
    if (Actions.job instanceof BlueprintBuildJob job) {
      var o = job.buildSummaryJson(job.tearingDown ? "teardown" : "running");
      o.addProperty("active_now", true);
      if (job.activeStep != null) {
        var cur = new JsonObject();
        cur.addProperty("step_index", job.activeStepIndex);
        cur.addProperty("wx", job.activeStep.wx());
        cur.addProperty("wy", job.activeStep.wy());
        cur.addProperty("wz", job.activeStep.wz());
        cur.addProperty("block", job.activeStep.blockId());
        cur.addProperty("item", job.activeStep.itemId());
        cur.addProperty("sub_status", ActionSummary.describe(Actions.status));
        o.add("current_step", cur);
      }
      return o;
    }
    if (Store.data != null && Store.data.blueprintCheckpoints != null) {
      JsonObject cp =
          Store.data.blueprintCheckpoints.get(Store.worldDimKey() + "|" + CHECKPOINT_KEY);
      if (cp != null) {
        var o = cp.deepCopy();
        o.addProperty("active_now", false);
        return o;
      }
    }
    var empty = new JsonObject();
    empty.addProperty("state", "none");
    empty.addProperty("active_now", false);
    return empty;
  }

  public static JsonObject pauseActive() {
    if (!(Actions.job instanceof BlueprintBuildJob job)) {
      var out = new JsonObject();
      out.addProperty("paused", false);
      out.addProperty("message", "当前没有正在运行的蓝图任务。");
      return out;
    }
    job.stopChild();
    job.finishedNormally = true;
    RollbackJob.recordingSuppressed = false;
    job.saveCheckpoint("paused");
    JsonObject summary = job.buildSummaryJson("paused");
    job.finish("部分完成：[BLUEPRINT_PAUSED] 已暂停蓝图施工并保存断点。" + summary);
    var out = summary.deepCopy();
    out.addProperty("paused", true);
    return out;
  }

  public static JsonObject abortActive() {
    if (Actions.job instanceof BlueprintBuildJob job) {
      job.stopChild();
      int preserved = flushScaffoldsToLedger(job.scaffoldStack);
      preserved += flushScaffoldsToLedger(job.pendingTempCleanups);
      job.finishedNormally = true;
      job.checkpointCleared = true;
      RollbackJob.recordingSuppressed = false;
      clearWorldCheckpoint();
      JsonObject summary = job.buildSummaryJson("aborted");
      summary.addProperty("preserved_scaffolds_for_cleanup", preserved);
      job.finish("部分完成：[BLUEPRINT_ABORTED] 已中止蓝图施工，未拆脚手架已保留至清理账本。" + summary);
      var out = summary.deepCopy();
      out.addProperty("aborted", true);
      return out;
    }
    int preserved = flushCheckpointScaffoldsToLedger();
    boolean hadCheckpoint = clearWorldCheckpoint();
    var out = new JsonObject();
    out.addProperty("aborted", true);
    out.addProperty("cleared_checkpoint", hadCheckpoint);
    out.addProperty("preserved_scaffolds_for_cleanup", preserved);
    out.addProperty("message", "已中止并清除当前世界蓝图断点（待清脚手架已转存至清理账本）。");
    return out;
  }

  private static int flushScaffoldsToLedger(Collection<ScaffoldEntry> entries) {
    if (entries == null || entries.isEmpty()) return 0;
    JsonArray ledger = Store.scaffoldLedger();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < ledger.size(); i++) {
      JsonElement el = ledger.get(i);
      if (!el.isJsonObject()) continue;
      JsonObject row = el.getAsJsonObject();
      if (!row.has("wx") || !row.has("wy") || !row.has("wz")) continue;
      seen.add(
          BlueprintScaffoldPlanner.posKey(
              row.get("wx").getAsInt(), row.get("wy").getAsInt(), row.get("wz").getAsInt()));
    }
    int added = 0;
    for (ScaffoldEntry e : entries) {
      if (e == null) continue;
      if (seen.add(BlueprintScaffoldPlanner.posKey(e.wx(), e.wy(), e.wz()))) {
        ledger.add(e.toJson());
        added++;
      }
    }
    if (added > 0) Store.saveSoon();
    return added;
  }

  private static int flushCheckpointScaffoldsToLedger() {
    if (Store.data == null || Store.data.blueprintCheckpoints == null) return 0;
    JsonObject cp =
        Store.data.blueprintCheckpoints.get(Store.worldDimKey() + "|" + CHECKPOINT_KEY);
    if (cp == null || !cp.has("scaffold_stack") || !cp.get("scaffold_stack").isJsonArray()) return 0;
    JsonArray arr = cp.getAsJsonArray("scaffold_stack");
    List<ScaffoldEntry> list = new ArrayList<>(arr.size());
    for (int i = 0; i < arr.size(); i++) {
      if (!arr.get(i).isJsonObject()) continue;
      ScaffoldEntry se = ScaffoldEntry.fromJson(arr.get(i).getAsJsonObject());
      if (se != null) list.add(se);
    }
    return flushScaffoldsToLedger(list);
  }

  public static boolean clearWorldCheckpoint() {
    if (Store.data == null || Store.data.blueprintCheckpoints == null) return false;
    Object prev = Store.data.blueprintCheckpoints.remove(Store.worldDimKey() + "|" + CHECKPOINT_KEY);
    if (prev != null) {
      Store.saveSoon();
      return true;
    }
    return false;
  }

  public static Actions.Job cleanupScaffold(Consumer<String> done) throws IOException {
    if (Store.data != null && Store.data.blueprintCheckpoints != null) {
      JsonObject cp =
          Store.data.blueprintCheckpoints.get(Store.worldDimKey() + "|" + CHECKPOINT_KEY);
      if (cp != null
          && cp.has("scaffold_stack")
          && cp.get("scaffold_stack").isJsonArray()
          && cp.getAsJsonArray("scaffold_stack").size() > 0) {
        try {
          BlueprintBuildJob job = resumeFromCheckpoint(0, false, done);
          job.cursor = job.steps.size();
          job.tearingDown = true;
          return job;
        } catch (IOException | IllegalStateException ignored) {
          flushCheckpointScaffoldsToLedger();
          clearWorldCheckpoint();
        }
      }
    }
    if (Store.scaffoldLedger().size() > 0) {
      var args = new JsonObject();
      args.addProperty("action", "teardown");
      return new ScaffoldJob(args, done);
    }
    throw new IllegalStateException("[NOT_FOUND] 当前世界及蓝图断点均没有待清理的脚手架");
  }

  public static BlueprintBuildJob resumeFromCheckpoint(int maxBlocks, Consumer<String> done)
      throws IOException {
    return resumeFromCheckpoint(maxBlocks, null, done);
  }

  public static BlueprintBuildJob resumeFromCheckpoint(
      int maxBlocks, Boolean keepScaffoldOverride, Consumer<String> done) throws IOException {
    if (Store.data == null || Store.data.blueprintCheckpoints == null)
      throw new IllegalStateException("[NOT_FOUND] 当前世界没有可恢复的蓝图断点");
    String key = Store.worldDimKey() + "|" + CHECKPOINT_KEY;
    JsonObject cp = Store.data.blueprintCheckpoints.get(key);
    if (cp == null || (!cp.has("file_name") && !cp.has("name")))
      throw new IllegalStateException("[NOT_FOUND] 当前世界没有可恢复的蓝图断点");
    if (cp.has("world_dim")) {
      String savedDim = cp.get("world_dim").getAsString();
      if (!savedDim.isBlank() && !savedDim.equals(Store.worldDimKey())) {
        throw new IllegalStateException(
            "[WRONG_CONTEXT] 蓝图断点属于其他世界或维度：" + savedDim + "，拒绝跨界续建");
      }
    }
    String targetName =
        cp.has("file_name") && !cp.get("file_name").getAsString().isBlank()
            ? cp.get("file_name").getAsString()
            : cp.get("name").getAsString();
    int rot = cp.has("rotation") ? cp.get("rotation").getAsInt() : 0;
    String mir = cp.has("mirror") ? cp.get("mirror").getAsString() : "none";
    String expectedSha = cp.has("blueprint_sha256") ? cp.get("blueprint_sha256").getAsString() : "";
    if (!expectedSha.isBlank()) {
      BlueprintLibrary.LoadedBlueprint current = BlueprintLibrary.loadWithMeta(targetName, rot, mir);
      if (!expectedSha.equalsIgnoreCase(current.sha256())) {
        throw new IllegalStateException(
            "[BLUEPRINT_HASH_MISMATCH] 蓝图文件已被替换或修改，原断点 SHA-256="
                + expectedSha
                + " 与当前文件不符；请先 abort 后重新开始");
      }
    }
    String scMode = cp.has("scaffold_mode") ? cp.get("scaffold_mode").getAsString() : "auto";
    String scBlock = cp.has("scaffold_block") ? cp.get("scaffold_block").getAsString() : "";
    boolean keep =
        keepScaffoldOverride != null
            ? keepScaffoldOverride
            : (cp.has("keep_scaffold") && cp.get("keep_scaffold").getAsBoolean());
    BlueprintBuildJob job =
        new BlueprintBuildJob(
            targetName,
            cp.get("anchor_x").getAsInt(),
            cp.get("anchor_y").getAsInt(),
            cp.get("anchor_z").getAsInt(),
            rot,
            mir,
            cp.get("y_min").getAsInt(),
            cp.get("y_max").getAsInt(),
            maxBlocks,
            scMode,
            scBlock,
            keep,
            false,
            done);
    if (cp.has("scaffold_stack") && cp.get("scaffold_stack").isJsonArray()) {
      for (JsonElement el : cp.getAsJsonArray("scaffold_stack")) {
        if (!el.isJsonObject()) continue;
        ScaffoldEntry se = ScaffoldEntry.fromJson(el.getAsJsonObject());
        if (se != null
            && job.scaffoldKeys.add(BlueprintScaffoldPlanner.posKey(se.wx(), se.wy(), se.wz()))) {
          job.scaffoldStack.add(se);
        }
      }
    }
    job.saveCheckpoint("running");
    return job;
  }

  @Override
  void tick() {
    if (finishedNormally) return;
    if (++ticks > 14400) {
      stopChild();
      finishedNormally = true;
      RollbackJob.recordingSuppressed = false;
      saveCheckpoint("timeout");
      finish("部分完成：[BLUEPRINT_TIMEOUT] 蓝图施工达到单次超时，已保存断点。" + buildSummaryJson("timeout"));
      return;
    }
    var mc = mc();
    if (mc.player == null || mc.level == null) {
      stopChild();
      finishedNormally = true;
      RollbackJob.recordingSuppressed = false;
      saveCheckpoint("disconnected");
      finish("失败：[WRONG_CONTEXT] 世界未就绪");
      return;
    }
    if (SurvivalPolicy.shouldPauseBuildWork(
        mc.player.getHealth(),
        mc.player.getMaxHealth(),
        mc.player.getFoodData().getFoodLevel(),
        mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.CREATIVE,
        Store.config.testIgnoreLowHealth)) {
      stopChild();
      finishedNormally = true;
      RollbackJob.recordingSuppressed = false;
      saveCheckpoint("paused");
      finish(
          "部分完成：[BLUEPRINT_PAUSED] 生命或饱食过低（生命 "
              + Math.round(mc.player.getHealth())
              + "，饱食 "
              + mc.player.getFoodData().getFoodLevel()
              + "），已暂停蓝图施工并保存断点，请补给后继续。"
              + buildSummaryJson("paused"));
      return;
    }

    if (activePlace != null) {
      boolean suppress = !pendingTempCleanups.isEmpty() || !pendingScaffoldPlacements.isEmpty() || activeStep == null;
      RollbackJob.recordingSuppressed = suppress;
      try {
        activePlace.tick();
      } finally {
        RollbackJob.recordingSuppressed = false;
      }
      updateStatusLine();
      return;
    }

    if (activeMine != null) {
      RollbackJob.recordingSuppressed = true;
      try {
        activeMine.tick();
      } finally {
        RollbackJob.recordingSuppressed = false;
      }
      updateStatusLine();
      return;
    }

    if (activeCollect != null) {
      RollbackJob.recordingSuppressed = true;
      try {
        activeCollect.tick();
      } finally {
        RollbackJob.recordingSuppressed = false;
      }
      updateStatusLine();
      return;
    }

    if (teardownApproachNav != null) {
      int nr = teardownApproachNav.tick();
      if (nr == 0) {
        updateStatusLine();
        return;
      }
      ScaffoldEntry approached = teardownApproachEntry;
      Consumer<Boolean> cb = teardownApproachCallback;
      boolean wasStepOff = teardownApproachIsStepOff;
      teardownApproachNav.stop();
      teardownApproachNav = null;
      teardownApproachEntry = null;
      teardownApproachCallback = null;
      teardownApproachIsStepOff = false;
      if (nr < 0 || approached == null) {
        if (cb != null) cb.accept(false);
        return;
      }
      if (wasStepOff) {
        boolean onGround = mc.player != null && mc.player.onGround();
        boolean onScaffolding = isStandingOnScaffolding(mc);
        boolean onTrackedSupport =
            mc.player != null
                && scaffoldKeys.contains(
                    BlueprintScaffoldPlanner.posKey(
                        mc.player.blockPosition().getX(),
                        mc.player.blockPosition().getY() - 1,
                        mc.player.blockPosition().getZ()));
        boolean stillNeedsStepOff =
            mc.player != null
                && TeardownReachPlanner.needsSolidStepOff(
                    mc.player.blockPosition().getX(),
                    mc.player.blockPosition().getY(),
                    mc.player.blockPosition().getZ(),
                    approached.wx(),
                    approached.wy(),
                    approached.wz());
        if (!TeardownReachPlanner.stepOffVerified(
            onGround, onScaffolding, onTrackedSupport, stillNeedsStepOff)) {
          if (cb != null) cb.accept(false);
          return;
        }
      }
      launchScaffoldMine(approached, wasStepOff, cb);
      return;
    }

    if (tearingDown) {
      tickTeardown(mc);
      return;
    }

    if (!pendingTempCleanups.isEmpty() && tempCleanupTargetStepIdx < 0) {
      // Remove completed step's temporary side-face support block before moving on
      ScaffoldEntry temp = pendingTempCleanups.peekFirst();
      launchScaffoldMine(
          temp,
          ok -> {
            pendingTempCleanups.pollFirst();
            if (!ok) flushScaffoldsToLedger(List.of(temp));
          });
      return;
    }

    if (!pendingScaffoldPlacements.isEmpty()) {
      tickNextScaffoldPlacement(mc);
      return;
    }

    if (maxBlocks > 0
        && placed >= maxBlocks
        && (cursor < steps.size() || !deferredRetryQueue.isEmpty())) {
      finishedNormally = true;
      saveCheckpoint("paused");
      finish(
          "部分完成：[BLUEPRINT_BATCH_LIMIT] 本批已放置 "
              + placed
              + " 格，断点已保存，可用 blueprint_status(action=resume) 续建。"
              + buildSummaryJson("paused"));
      return;
    }

    boolean creative =
        mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.CREATIVE;
    updateStatusLine();

    int scanBudget = 0;
    while (scanBudget < 32) {
      boolean isRetry = false;
      int stepIdx;
      if (cursor < steps.size()) {
        stepIdx = cursor++;
      } else if (!deferredRetryQueue.isEmpty()) {
        stepIdx = deferredRetryQueue.removeFirst();
        isRetry = true;
      } else {
        break;
      }
      scanBudget++;
      BlueprintPlanner.BuildStep step = steps.get(stepIdx);
      if (!isItemPlaceable(step.itemId())) {
        skippedUnsupported++;
        continue;
      }
      BlockPos pos = new BlockPos(step.wx(), step.wy(), step.wz());
      if (!mc.level.hasChunkAt(pos)) {
        unloaded++;
        if (unloadedSamples.size() < 8) unloadedSamples.add(pos.toShortString());
        continue;
      }
      BlockState cur = mc.level.getBlockState(pos);
      String curId = BuiltInRegistries.BLOCK.getKey(cur.getBlock()).toString();
      Map<String, String> curProps = extractProperties(cur);
      boolean replaceable = cur.canBeReplaced() || BlueprintFormat.isAirBlock(curId);
      BlueprintPlanner.MatchStatus match =
          BlueprintPlanner.classifyBlock(curId, curProps, replaceable, step);
      if (match == BlueprintPlanner.MatchStatus.MATCHED) {
        alreadyMatched++;
        continue;
      }
      if (match == BlueprintPlanner.MatchStatus.STATE_MISMATCH) {
        stateMismatch++;
        if (stateMismatchSamples.size() < MAX_RECORDED_FAILURES) {
          stateMismatchSamples.add(sampleDiff(pos, step, curId, curProps));
        }
        continue;
      }
      if (match == BlueprintPlanner.MatchStatus.BLOCKED_EXISTING) {
        blockedExisting++;
        continue;
      }
      if (!creative && !hasBagBlockItem(step.itemId())) {
        skippedMissingItem++;
        missingCounts.merge(step.itemId(), 1, Integer::sum);
        continue;
      }

      launchChildPlace(stepIdx, step, isRetry);
      return;
    }

    if (cursor >= steps.size()
        && deferredRetryQueue.isEmpty()
        && pendingScaffoldPlacements.isEmpty()
        && activePlace == null
        && activeMine == null) {
      if (!keepScaffold && !scaffoldStack.isEmpty()) {
        tearingDown = true;
        saveCheckpoint("teardown");
        tickTeardown(mc);
        return;
      }
      finishBuild();
    }
  }

  private void finishBuild() {
    finishedNormally = true;
    String endState =
        skippedMissingItem == 0
                && failed == 0
                && teardownFailed == 0
                && stateMismatch == 0
                && unloaded == 0
                && needScaffoldError.isBlank()
                && (keepScaffold || scaffoldStack.isEmpty())
            ? "complete"
            : "partial";
    saveCheckpoint(endState);
    var summary = buildSummaryJson(endState);
    String head = "complete".equals(endState) ? "成功" : "部分完成";
    String scaffInfo =
        scaffoldsPlaced > 0 || scaffoldsRemoved > 0 || scaffoldsCollected > 0
            ? "，脚手架 搭"
                + scaffoldsPlaced
                + "/拆"
                + scaffoldsRemoved
                + (scaffoldsCollected > 0 ? "/收" + scaffoldsCollected : "")
            : "";
    String prefix = !needScaffoldError.isBlank() && placed == 0 ? head + "：[NEED_SCAFFOLD] " : head + "：";
    finish(
        prefix
            + "蓝图施工完成 "
            + fileName
            + "（新放 "
            + placed
            + "，已有 "
            + alreadyMatched
            + "，属性不符 "
            + stateMismatch
            + "，缺料跳过 "
            + skippedMissingItem
            + "，不支持/无支撑跳过 "
            + skippedUnsupported
            + "，被占 "
            + blockedExisting
            + "，未加载 "
            + unloaded
            + "，失败 "
            + failed
            + scaffInfo
            + "）。"
            + summary);
  }

  private void tickNextScaffoldPlacement(Minecraft mc) {
    while (!pendingScaffoldPlacements.isEmpty()) {
      PendingScaffold ps = pendingScaffoldPlacements.removeFirst();
      BlockPos pos = new BlockPos(ps.wx(), ps.wy(), ps.wz());
      String key = BlueprintScaffoldPlanner.posKey(ps.wx(), ps.wy(), ps.wz());
      if (blueprintSolidKeys.contains(key)) continue;
      if (!mc.level.hasChunkAt(pos)) continue;
      BlockState cur = mc.level.getBlockState(pos);
      if (BuiltInRegistries.BLOCK.getKey(cur.getBlock()).toString().equals(ps.blockId())) {
        if (ps.retryStepIdx() >= 0) deferredRetryQueue.addFirst(ps.retryStepIdx());
        continue;
      }
      if (!cur.canBeReplaced() && !cur.isAir()) continue;
      if (requestedScaffoldBlock.isBlank()
          && (mc.gameMode == null
              || mc.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.CREATIVE)) {
        int liveBag = BlueprintLibrary.playerBagItemCounts().getOrDefault(ps.blockId(), 0);
        int reservedBom = remainingBomCounts().getOrDefault(ps.blockId(), 0);
        if (liveBag - reservedBom < 1) {
          needScaffoldError =
              "[NEED_SCAFFOLD] 背包材料 "
                  + ps.blockId()
                  + " 扣除剩余蓝图用量后已无富余，停止继续搭架（需留存主体物料）";
          pendingScaffoldPlacements.clear();
          updateStatusLine();
          return;
        }
      }
      PlaceJob job =
          new PlaceJob(
              ps.blockId(),
              pos,
              reply -> {
                activePlace = null;
                if (finishedNormally || Actions.job != this) return;
                if (!Rules.actionFailed(reply)) {
                  scaffoldsPlaced++;
                  ScaffoldEntry se =
                      new ScaffoldEntry(ps.wx(), ps.wy(), ps.wz(), ps.blockId(), ps.kind());
                  if (ps.isTempSupport()) {
                    pendingTempCleanups.addLast(se);
                    tempCleanupTargetStepIdx = ps.retryStepIdx();
                  } else if (scaffoldKeys.add(key)) {
                    scaffoldStack.add(se);
                  }
                  saveCheckpoint("running");
                  if (ps.retryStepIdx() >= 0) {
                    deferredRetryQueue.addFirst(ps.retryStepIdx());
                  }
                } else {
                  handleScaffoldPlaceFailure(ps);
                }
              });
      job.managed = true;
      activePlace = job;
      updateStatusLine();
      return;
    }
  }

  private void handleScaffoldPlaceFailure(PendingScaffold failedCell) {
    int retryIdx = failedCell.retryStepIdx();
    boolean isTowerOrOverhang =
        "SCAFFOLD_TOWER".equals(failedCell.kind()) || "SCAFFOLD_OVERHANG".equals(failedCell.kind());
    if (!failedCell.isTempSupport()
        && isTowerOrOverhang
        && !failedCell.bandKey().isBlank()
        && requestedScaffoldMode == BlueprintScaffoldPlanner.Mode.AUTO
        && solidFallbackBands.add(failedCell.bandKey())) {
      List<PendingScaffold> kept = new ArrayList<>();
      while (!pendingScaffoldPlacements.isEmpty()) {
        PendingScaffold next = pendingScaffoldPlacements.removeFirst();
        if (failedCell.bandKey().equals(next.bandKey())) {
          if (next.retryStepIdx() >= 0) retryIdx = next.retryStepIdx();
        } else {
          kept.add(next);
        }
      }
      pendingScaffoldPlacements.addAll(kept);

      int colon = failedCell.bandKey().indexOf(':');
      int targetStandY = Integer.parseInt(failedCell.bandKey().substring(0, colon));
      String side = failedCell.bandKey().substring(colon + 1);
      int rise = Math.max(1, targetStandY - baseGroundY);
      var mat =
          BlueprintScaffoldPlanner.selectMaterial(
              BlueprintScaffoldPlanner.Mode.SOLID_RAMP,
              "",
              BlueprintLibrary.playerBagItemCounts(),
              remainingBomCounts(),
              rise,
              rise * 2 + 4);
      if (mat.error().isBlank() && !mat.blockId().isBlank()) {
        Set<String> occupied = new HashSet<>(blueprintSolidKeys);
        occupied.addAll(scaffoldKeys);
        int tx = retryIdx >= 0 && retryIdx < steps.size() ? steps.get(retryIdx).wx() : (bpMinX + bpMaxX) / 2;
        int tz = retryIdx >= 0 && retryIdx < steps.size() ? steps.get(retryIdx).wz() : (bpMinZ + bpMaxZ) / 2;
        List<BlueprintScaffoldPlanner.ScaffoldCell> plan =
            BlueprintScaffoldPlanner.planHollowPerimeterRamp(
                bpMinX, bpMaxX, bpMinZ, bpMaxZ, baseGroundY, targetStandY, side, tx, tz, occupied);
        if (!plan.isEmpty()) {
          // Insert backward into deque front so removeFirst() builds strictly bottom-to-top!
          for (int i = plan.size() - 1; i >= 0; i--) {
            var cell = plan.get(i);
            int link = (i == plan.size() - 1) ? retryIdx : -1;
            pendingScaffoldPlacements.addFirst(
                new PendingScaffold(
                    cell.wx(),
                    cell.wy(),
                    cell.wz(),
                    mat.blockId(),
                    cell.kind().name(),
                    false,
                    link,
                    failedCell.bandKey()));
          }
          return;
        }
      }
    }
    if (retryIdx >= 0) {
      deferredRetryQueue.addLast(retryIdx);
    }
  }

  private void tickTeardown(Minecraft mc) {
    while (!scaffoldStack.isEmpty()) {
      int lastIdx = scaffoldStack.size() - 1;
      ScaffoldEntry entry = scaffoldStack.get(lastIdx);
      String key = BlueprintScaffoldPlanner.posKey(entry.wx(), entry.wy(), entry.wz());
      BlockPos pos = new BlockPos(entry.wx(), entry.wy(), entry.wz());
      if (blueprintSolidKeys.contains(key)) {
        scaffoldStack.remove(lastIdx);
        scaffoldKeys.remove(key);
        continue;
      }
      if (!mc.level.hasChunkAt(pos)) {
        teardownFailed++;
        teardownClimbCleared = false;
        climbExit.reset();
        tearingDown = false;
        saveCheckpoint("teardown");
        finishBuild();
        return;
      }
      BlockState cur = mc.level.getBlockState(pos);
      String curId = BuiltInRegistries.BLOCK.getKey(cur.getBlock()).toString();
      if (cur.isAir() || !curId.equals(entry.blockId())) {
        scaffoldStack.remove(lastIdx);
        scaffoldKeys.remove(key);
        continue;
      }
      if ("minecraft:scaffolding".equals(entry.blockId())
          && !teardownClimbCleared
          && (mc.player.onClimbable() || isStandingOnScaffolding(mc))) {
        ScaffoldJob.ClimbExit.ExitState exit = climbExit.step(mc);
        if (exit == ScaffoldJob.ClimbExit.ExitState.MOVING) return;
        if (exit == ScaffoldJob.ClimbExit.ExitState.BLOCKED) {
          if (mc.player != null && !teardownStepOffTried) {
            teardownStepOffTried = true;
            BlockPos safeCell =
                ScaffoldJob.findSolidStepOffCell(
                    mc,
                    mc.player.blockPosition(),
                    baseGroundY,
                    p -> scaffoldKeys.contains(BlueprintScaffoldPlanner.posKey(p.getX(), p.getY(), p.getZ())));
            if (safeCell != null) {
              Navigator stepOffNav = new Navigator();
              if (stepOffNav.startLocal(safeCell)) {
                climbExit.reset();
                teardownApproachNav = stepOffNav;
                teardownApproachEntry = entry;
                teardownApproachIsStepOff = true;
                teardownApproachCallback = teardownMineCallback(entry, key);
                return;
              }
            }
          }
          teardownFailed++;
          teardownClimbCleared = false;
          climbExit.reset();
          tearingDown = false;
          saveCheckpoint("teardown");
          finishBuild();
          return;
        }
        teardownClimbCleared = true;
      }
      if (!"minecraft:scaffolding".equals(entry.blockId())
          && mc.player != null
          && !teardownStepOffTried
          && TeardownReachPlanner.needsSolidStepOff(
              mc.player.blockPosition().getX(),
              mc.player.blockPosition().getY(),
              mc.player.blockPosition().getZ(),
              pos.getX(),
              pos.getY(),
              pos.getZ())) {
        teardownStepOffTried = true;
        BlockPos safeCell =
            ScaffoldJob.findSolidStepOffCell(
                mc,
                pos,
                baseGroundY,
                p -> scaffoldKeys.contains(BlueprintScaffoldPlanner.posKey(p.getX(), p.getY(), p.getZ())));
        if (safeCell != null) {
          Navigator stepOffNav = new Navigator();
          if (stepOffNav.startLocal(safeCell)) {
            teardownApproachNav = stepOffNav;
            teardownApproachEntry = entry;
            teardownApproachIsStepOff = true;
            teardownApproachCallback = teardownMineCallback(entry, key);
            return;
          }
        }
      }
      PlayerControls.release();
      launchScaffoldMine(entry, teardownMineCallback(entry, key));
      return;
    }
    teardownClimbCleared = false;
    teardownStepOffTried = false;
    climbExit.reset();
    boolean collectMode =
        mc.gameMode != null
            && (mc.gameMode.getPlayerMode() == GameType.SURVIVAL
                || mc.gameMode.getPlayerMode() == GameType.ADVENTURE);
    if (!dropCollectStarted
        && TeardownReachPlanner.shouldCollectTeardownDrops(
            collectMode, scaffoldsRemoved, !removedScaffoldItemIds.isEmpty())) {
      dropCollectStarted = true;
      pendingDropCollectIds.addAll(removedScaffoldItemIds);
    }
    if (dropCollectStarted && launchNextScaffoldDropCollection(mc)) {
      return;
    }
    tearingDown = false;
    finishBuild();
  }

  private boolean launchNextScaffoldDropCollection(Minecraft mc) {
    if (mc.level == null || mc.player == null) {
      pendingDropCollectIds.clear();
      return false;
    }
    if (mc.player.containerMenu != mc.player.inventoryMenu || mc.screen != null) {
      pendingDropCollectIds.clear();
      return false;
    }
    BlockPos center =
        new BlockPos((bpMinX + bpMaxX) / 2, baseGroundY, (bpMinZ + bpMaxZ) / 2);
    int radius = Math.min(22, Math.max(10, Math.max(bpMaxX - bpMinX, bpMaxZ - bpMinZ) + 8));
    var area = new net.minecraft.world.phys.AABB(center).inflate(radius);
    while (!pendingDropCollectIds.isEmpty()) {
      String itemId = pendingDropCollectIds.removeFirst();
      var drops =
          mc.level.getEntitiesOfClass(
              net.minecraft.world.entity.item.ItemEntity.class,
              area,
              e -> e.isAlive() && Actions.match(e.getItem(), itemId));
      if (drops.isEmpty()) continue;
      try {
        CollectionJob job =
            new CollectionJob(
                    itemId,
                    Integer.MAX_VALUE,
                    radius,
                    center,
                    e -> true,
                    true,
                    reply -> {
                      if (CollectionJob.lastReceipt != null) {
                        scaffoldsCollected += Math.max(0, CollectionJob.lastReceipt.gained());
                      }
                      activeCollect = null;
                    })
                .maxTicks(400)
                .backgroundDuty(true);
        job.managed = true;
        activeCollect = job;
        updateStatusLine();
        return true;
      } catch (IllegalArgumentException e) {
        activeCollect = null;
      }
    }
    return false;
  }

  private Consumer<Boolean> teardownMineCallback(ScaffoldEntry entry, String key) {
    return ok -> {
      teardownClimbCleared = false;
      teardownStepOffTried = false;
      climbExit.reset();
      if (ok) {
        int idx = scaffoldStack.size() - 1;
        if (idx >= 0 && scaffoldStack.get(idx).equals(entry)) {
          scaffoldStack.remove(idx);
          scaffoldKeys.remove(key);
          scaffoldsRemoved++;
          removedScaffoldItemIds.add(entry.blockId());
          saveCheckpoint(tearingDown ? "teardown" : "running");
        }
      } else {
        // Enforce strict LIFO: retain blocked stack top and pause teardown cleanly.
        teardownFailed++;
        tearingDown = false;
        saveCheckpoint("teardown");
        finishBuild();
      }
    };
  }

  private static boolean isStandingOnScaffolding(Minecraft mc) {
    if (mc.player == null || mc.level == null) return false;
    BlockPos feet = mc.player.blockPosition();
    return (mc.level.hasChunkAt(feet)
            && mc.level.getBlockState(feet).getBlock()
                instanceof net.minecraft.world.level.block.ScaffoldingBlock)
        || (mc.level.hasChunkAt(feet.below())
            && mc.level.getBlockState(feet.below()).getBlock()
                instanceof net.minecraft.world.level.block.ScaffoldingBlock);
  }

  private void launchScaffoldMine(ScaffoldEntry entry, Consumer<Boolean> onResult) {
    launchScaffoldMine(entry, true, onResult);
  }

  private void launchScaffoldMine(
      ScaffoldEntry entry, boolean allowApproach, Consumer<Boolean> onResult) {
    BlockPos pos = new BlockPos(entry.wx(), entry.wy(), entry.wz());
    var player = mc().player;
    var level = mc().level;
    boolean loaded = level != null && level.hasChunkAt(pos);
    double dist = player == null ? 0.0 : Math.sqrt(pos.distSqr(player.blockPosition()));
    TeardownReachPlanner.Decision decision = TeardownReachPlanner.plan(dist, loaded);
    if (decision.mode() == TeardownReachPlanner.PlanMode.TOO_FAR) {
      if (onResult != null) onResult.accept(false);
      return;
    }
    if (decision.mode() == TeardownReachPlanner.PlanMode.APPROACH_THEN_MINE) {
      if (!allowApproach) {
        if (onResult != null) onResult.accept(false);
        return;
      }
      Navigator nav =
          new Navigator().interactionDy(-6, 3).allowScaffoldingColumnMine(true).maxRadius(5);
      if (nav.startForInteraction(pos)) {
        teardownApproachNav = nav;
        teardownApproachEntry = entry;
        teardownApproachCallback = onResult;
        return;
      }
      if (onResult != null) onResult.accept(false);
      return;
    }
    try {
      MiningJob mine =
          new MiningJob(
                  entry.blockId(),
                  1,
                  decision.mineRadius(),
                  pos,
                  true,
                  5,
                  reply -> {
                    activeMine = null;
                    if (finishedNormally || Actions.job != this) return;
                    if (onResult != null) onResult.accept(!Rules.actionFailed(reply));
                  })
              .scaffoldTeardown(true);
      mine.managed = true;
      activeMine = mine;
    } catch (IllegalArgumentException e) {
      if (onResult != null) onResult.accept(false);
    }
  }

  private void launchChildPlace(int stepIdx, BlueprintPlanner.BuildStep step, boolean isRetry) {
    activeStep = step;
    activeStepIndex = stepIdx;
    BlockPos pos = new BlockPos(step.wx(), step.wy(), step.wz());
    PlaceJob place =
        new PlaceJob(
            step.itemId(),
            pos,
            reply -> handleChildFinish(stepIdx, step, pos, isRetry, reply));
    place.managed = true;
    if (step.placementState() != null && step.placementState().size() > 0) {
      place.state(step.placementState());
    }
    activePlace = place;
  }

  private void handleChildFinish(
      int stepIdx,
      BlueprintPlanner.BuildStep step,
      BlockPos pos,
      boolean isRetry,
      String reply) {
    activePlace = null;
    activeStep = null;
    activeStepIndex = -1;
    if (finishedNormally || Actions.job != this) return;

    if (tempCleanupTargetStepIdx == stepIdx) {
      tempCleanupTargetStepIdx = -1; // Triggers temp cleanup on next tick before continuing
    }

    if (!Rules.actionFailed(reply)) {
      placed++;
      if (placed % 4 == 0) saveCheckpoint("running");
      return;
    }

    if (reply.contains("缺少") || reply.contains("不足")) {
      skippedMissingItem++;
      missingCounts.merge(step.itemId(), 1, Integer::sum);
      return;
    }

    String code = ReasonCodes.describe(reply).get("reason_code").getAsString();
    boolean needsAccess =
        Set.of(
                "NOT_REACHABLE",
                "UNREACHABLE",
                "NO_SUPPORT_FACE",
                "PLACEMENT_STATE_UNAVAILABLE",
                "NO_PLACE_SITE",
                "NO_VISIBLE_FACE")
            .contains(code);

    if (needsAccess && requestedScaffoldMode != BlueprintScaffoldPlanner.Mode.NONE) {
      if (tryPlanTempSupportOrScaffold(stepIdx, step, code)) {
        return;
      }
    }

    boolean transientFailure =
        Set.of(
                "NOT_REACHABLE",
                "UNREACHABLE",
                "PLACE_CHANGED",
                "NO_SUPPORT_FACE",
                "PLACE_TIMEOUT",
                "PLACE_UNCONFIRMED",
                "NO_PLACE_SITE")
            .contains(code);
    if (!isRetry && transientFailure && retriedSteps.add(stepIdx)) {
      deferredRetryQueue.addLast(stepIdx);
      return;
    }

    if (code.equals("TARGET_OCCUPIED")) {
      blockedExisting++;
    } else if (code.equals("NO_SUPPORT_FACE") || code.equals("PLACEMENT_STATE_UNAVAILABLE")) {
      skippedUnsupported++;
    } else {
      failed++;
    }
    recordFailure(stepIdx, step, pos, code, reply);
  }

  private boolean tryPlanTempSupportOrScaffold(
      int stepIdx, BlueprintPlanner.BuildStep step, String reasonCode) {
    var mc = mc();
    if (mc.level == null || mc.player == null) return false;
    Map<String, Integer> bag = BlueprintLibrary.playerBagItemCounts();
    Map<String, Integer> remBom = remainingBomCounts();

    // 1. Try single-block temporary side support first when missing horizontal/axis face
    boolean faceMissing =
        reasonCode.equals("NO_SUPPORT_FACE")
            || reasonCode.equals("PLACEMENT_STATE_UNAVAILABLE")
            || BlueprintPlanner.forbidsUpFace(step);
    if (faceMissing && tempTriedSteps.add(stepIdx)) {
      String solidReq =
          "minecraft:scaffolding".equals(requestedScaffoldBlock) ? "" : requestedScaffoldBlock;
      var solidMat =
          BlueprintScaffoldPlanner.selectMaterial(
              BlueprintScaffoldPlanner.Mode.PILLAR, solidReq, bag, remBom, 1, 1);
      if (solidMat.error().isBlank() && !solidMat.blockId().isBlank()) {
        BlueprintScaffoldPlanner.ScaffoldCell aux =
            BlueprintScaffoldPlanner.pickTempSupportPos(
                step,
                blueprintSolidKeys,
                c -> {
                  BlockPos p = new BlockPos(c[0], c[1], c[2]);
                  return mc.level.hasChunkAt(p)
                      && (mc.level.getBlockState(p).isAir()
                          || mc.level.getBlockState(p).canBeReplaced());
                },
                c -> Reach.placement(new BlockPos(c[0], c[1], c[2])) != null);
        if (aux != null) {
          pendingScaffoldPlacements.addLast(
              new PendingScaffold(
                  aux.wx(), aux.wy(), aux.wz(), solidMat.blockId(), aux.kind().name(), true, stepIdx, ""));
          return true;
        }
      }
    }

    // 2. Plan 3D elevation ramp or scaffolding tower for unreachable upper layers / facing sectors
    int targetStandY = step.wy() - 1;
    int groundY = Math.min(baseGroundY, mc.player.blockPosition().getY());
    if (targetStandY <= groundY) return false;

    String side = BlueprintPlanner.requiredStanceSide(step);
    if (side.isBlank()) side = nearestOuterSide(step);
    String bandKey = targetStandY + ":" + side;
    boolean bandAlreadyPlanned = elevationPlannedBands.contains(bandKey);
    int attempts = stepScaffoldAttempts.getOrDefault(stepIdx, 0);
    if (attempts >= 2) return false;
    stepScaffoldAttempts.put(stepIdx, attempts + 1);

    int rise = targetStandY - groundY;
    var mat =
        BlueprintScaffoldPlanner.selectMaterial(
            requestedScaffoldMode, requestedScaffoldBlock, bag, remBom, rise, rise * 2 + 4);
    if (!mat.error().isBlank() || mat.blockId().isBlank()) {
      needScaffoldError = mat.error();
      recordFailure(
          stepIdx, step, new BlockPos(step.wx(), step.wy(), step.wz()), "NEED_SCAFFOLD", mat.error());
      return false;
    }

    Set<String> occupied = new HashSet<>(blueprintSolidKeys);
    occupied.addAll(scaffoldKeys);

    List<BlueprintScaffoldPlanner.ScaffoldCell> plan = List.of();
    if (!bandAlreadyPlanned) {
      if (mat.resolvedMode() == BlueprintScaffoldPlanner.Mode.SCAFFOLDING) {
        int[] baseAndDir = scaffoldingAnchorForStep(side, step);
        plan =
            BlueprintScaffoldPlanner.planScaffoldingTower(
                baseAndDir[0],
                groundY,
                baseAndDir[1],
                targetStandY,
                baseAndDir[2],
                baseAndDir[3],
                baseAndDir[4],
                occupied);
      } else {
        plan =
            BlueprintScaffoldPlanner.planHollowPerimeterRamp(
                bpMinX,
                bpMaxX,
                bpMinZ,
                bpMaxZ,
                groundY,
                targetStandY,
                side,
                step.wx(),
                step.wz(),
                occupied);
      }
    }
    if (plan.isEmpty() || !planReachesStep(plan, step)) {
      var clusterTargets = new ArrayList<BlueprintScaffoldPlanner.Pos>();
      var requiredSides = new HashMap<BlueprintScaffoldPlanner.Pos, String>();
      var stepPos = new BlueprintScaffoldPlanner.Pos(step.wx(), step.wy(), step.wz());
      clusterTargets.add(stepPos);
      String stepReqSide = BlueprintPlanner.requiredStanceSide(step);
      if (!stepReqSide.isBlank()) requiredSides.put(stepPos, stepReqSide);
      for (int i = cursor; i < steps.size() && clusterTargets.size() < 48; i++) {
        var s = steps.get(i);
        if (Math.abs(s.wy() - step.wy()) <= 2 && Math.abs(s.wx() - step.wx()) <= 10 && Math.abs(s.wz() - step.wz()) <= 10) {
          var p = new BlueprintScaffoldPlanner.Pos(s.wx(), s.wy(), s.wz());
          if (!p.equals(stepPos)) {
            clusterTargets.add(p);
            String req = BlueprintPlanner.requiredStanceSide(s);
            if (!req.isBlank()) requiredSides.put(p, req);
          }
        }
      }
      var worldView =
          new BlueprintScaffoldPlanner.WorldView() {
            @Override
            public boolean isLoaded(int x, int y, int z) {
              return mc.level.hasChunkAt(new BlockPos(x, y, z));
            }

            @Override
            public boolean isFreeAir(int x, int y, int z) {
              BlockPos bp = new BlockPos(x, y, z);
              if (!mc.level.hasChunkAt(bp)) return false;
              if (blueprintSolidKeys.contains(BlueprintScaffoldPlanner.posKey(x, y, z))) return false;
              var st = mc.level.getBlockState(bp);
              return st.isAir() || st.canBeReplaced();
            }

            @Override
            public boolean isWalkableStand(int x, int y, int z) {
              return hasSturdyTopFace(x, y - 1, z) && isFreeAir(x, y, z) && isFreeAir(x, y + 1, z);
            }

            @Override
            public boolean hasSturdyTopFace(int x, int y, int z) {
              BlockPos bp = new BlockPos(x, y, z);
              return mc.level.hasChunkAt(bp)
                  && mc.level.getBlockState(bp).isFaceSturdy(mc.level, bp, net.minecraft.core.Direction.UP);
            }
          };
      var clusterPlan =
          BlueprintScaffoldPlanner.planCluster(
              worldView,
              clusterTargets,
              new BlueprintScaffoldPlanner.Pos(mc.player.getBlockX(), groundY, mc.player.getBlockZ()),
              mat.resolvedMode(),
              4.2,
              occupied,
              stepPos,
              requiredSides);
      if (clusterPlan.validLifo()
          && !clusterPlan.buildOrder().isEmpty()
          && !clusterPlan.uncoveredTargets().contains(stepPos)) {
        plan = clusterPlan.buildOrder();
      } else if (!planReachesStep(plan, step)) {
        plan = List.of();
      }
    }
    if (plan.isEmpty() || !planReachesStep(plan, step)) return false;

    if (mat.resolvedMode() == BlueprintScaffoldPlanner.Mode.SCAFFOLDING) {
      var vanillaCheck =
          BlueprintScaffoldPlanner.validateVanillaScaffoldingLifo(
              plan,
              pos -> {
                BlockPos bp = new BlockPos(pos[0], pos[1], pos[2]);
                return mc.level.hasChunkAt(bp)
                    && mc.level.getBlockState(bp).isFaceSturdy(mc.level, bp, net.minecraft.core.Direction.UP);
              },
              occupied);
      if (!vanillaCheck.ok()) {
        needScaffoldError = vanillaCheck.reason();
        recordFailure(
            stepIdx,
            step,
            new BlockPos(step.wx(), step.wy(), step.wz()),
            "NEED_SCAFFOLD",
            vanillaCheck.reason());
        return false;
      }
    }

    boolean isCreative =
        mc.gameMode != null && mc.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.CREATIVE;
    if (!isCreative && requestedScaffoldBlock.isBlank() && plan.size() > mat.availableSurplus()) {
      String gapMsg =
          "[NEED_SCAFFOLD] 自动搭架实际需 "
              + plan.size()
              + " 格 "
              + mat.blockId()
              + "，扣除蓝图预留后可用富余仅 "
              + mat.availableSurplus()
              + " 格";
      needScaffoldError = gapMsg;
      recordFailure(
          stepIdx, step, new BlockPos(step.wx(), step.wy(), step.wz()), "NEED_SCAFFOLD", gapMsg);
      return false;
    }

    elevationPlannedBands.add(bandKey);
    for (int i = 0; i < plan.size(); i++) {
      var cell = plan.get(i);
      int linkRetry = (i == plan.size() - 1) ? stepIdx : -1;
      pendingScaffoldPlacements.addLast(
          new PendingScaffold(
              cell.wx(),
              cell.wy(),
              cell.wz(),
              mat.blockId(),
              cell.kind().name(),
              false,
              linkRetry,
              bandKey));
    }
    return true;
  }

  private boolean planReachesStep(
      List<BlueprintScaffoldPlanner.ScaffoldCell> plan, BlueprintPlanner.BuildStep step) {
    String reqSide = BlueprintPlanner.requiredStanceSide(step);
    var targetPos = new BlueprintScaffoldPlanner.Pos(step.wx(), step.wy(), step.wz());
    boolean needEyeAbove = step.wy() > baseGroundY + 1;
    for (var c : plan) {
      double standY = c.wy() + 1.0;
      double eyeY = standY + 1.62;
      if (needEyeAbove
          && (!reqSide.isBlank() || step.wy() > standY + 1.0)
          && eyeY < step.wy() + 0.05) {
        continue;
      }
      double dx = (step.wx() + 0.5) - (c.wx() + 0.5);
      double dy = (step.wy() + 0.5) - eyeY;
      double dz = (step.wz() + 0.5) - (c.wz() + 0.5);
      if (dx * dx + dy * dy + dz * dz <= 21.16
          && BlueprintScaffoldPlanner.stanceMatchesRequiredSide(c.wx(), c.wz(), targetPos, reqSide)) {
        return true;
      }
    }
    return false;
  }

  private String nearestOuterSide(BlueprintPlanner.BuildStep step) {
    int dn = Math.abs(step.wz() - bpMinZ);
    int ds = Math.abs(bpMaxZ - step.wz());
    int dw = Math.abs(step.wx() - bpMinX);
    int de = Math.abs(bpMaxX - step.wx());
    int best = Math.min(Math.min(dn, ds), Math.min(dw, de));
    if (best == dn) return "north";
    if (best == ds) return "south";
    if (best == dw) return "west";
    return "east";
  }

  private int[] scaffoldingAnchorForStep(String side, BlueprintPlanner.BuildStep step) {
    int clampedX = Math.max(bpMinX, Math.min(bpMaxX, step.wx()));
    int clampedZ = Math.max(bpMinZ, Math.min(bpMaxZ, step.wz()));
    return switch (side) {
      case "south" -> {
        int baseZ = bpMaxZ + 2;
        int over = Math.max(2, Math.min(5, baseZ - step.wz()));
        yield new int[] {clampedX, baseZ, 0, -1, over};
      }
      case "west" -> {
        int baseX = bpMinX - 2;
        int over = Math.max(2, Math.min(5, step.wx() - baseX));
        yield new int[] {baseX, clampedZ, 1, 0, over};
      }
      case "east" -> {
        int baseX = bpMaxX + 2;
        int over = Math.max(2, Math.min(5, baseX - step.wx()));
        yield new int[] {baseX, clampedZ, -1, 0, over};
      }
      default -> {
        int baseZ = bpMinZ - 2;
        int over = Math.max(2, Math.min(5, step.wz() - baseZ));
        yield new int[] {clampedX, baseZ, 0, 1, over};
      }
    };
  }

  private Map<String, Integer> remainingBomCounts() {
    Map<String, Integer> rem = new LinkedHashMap<>();
    for (int i = Math.max(0, cursor - 1); i < steps.size(); i++) {
      rem.merge(steps.get(i).itemId(), 1, Integer::sum);
    }
    return rem;
  }

  private void recordFailure(
      int stepIdx, BlueprintPlanner.BuildStep step, BlockPos pos, String reasonCode, String reply) {
    if (failures.size() >= MAX_RECORDED_FAILURES) return;
    var mc = mc();
    String curId = "unloaded";
    Map<String, String> curProps = Map.of();
    if (mc.level != null && mc.level.hasChunkAt(pos)) {
      BlockState cur = mc.level.getBlockState(pos);
      curId = BuiltInRegistries.BLOCK.getKey(cur.getBlock()).toString();
      curProps = extractProperties(cur);
    }
    var item = new JsonObject();
    item.addProperty("step_index", stepIdx);
    item.addProperty("wx", step.wx());
    item.addProperty("wy", step.wy());
    item.addProperty("wz", step.wz());
    item.addProperty("expected_block", step.blockId());
    item.add("expected_state", step.placementState() == null ? new JsonObject() : step.placementState());
    item.addProperty("actual_block", curId);
    item.add("actual_state", Store.GSON.toJsonTree(curProps));
    item.addProperty("reason_code", reasonCode);
    item.addProperty("detail", Rules.clean(reply, 240));
    failures.add(item);
  }

  private void updateStatusLine() {
    if (tearingDown) {
      Actions.status =
          activeCollect != null
              ? "蓝图回收掉落脚手架 · 已收 " + scaffoldsCollected + " 件"
              : "蓝图脚手架安全拆解 · 剩余 " + scaffoldStack.size() + " 格";
      return;
    }
    if (activePlace != null && (!pendingScaffoldPlacements.isEmpty() || activeStep == null)) {
      Actions.status = "蓝图搭高脚手架 · 已搭 " + scaffoldsPlaced + " 格";
      return;
    }
    Actions.status =
        "蓝图施工 "
            + fileName
            + " · "
            + cursor
            + "/"
            + steps.size()
            + "（新放 "
            + placed
            + " · 已有 "
            + alreadyMatched
            + "）";
  }

  private void saveCheckpoint(String state) {
    if (checkpointCleared || Store.data == null) return;
    if (Store.data.blueprintCheckpoints == null)
      Store.data.blueprintCheckpoints = new LinkedHashMap<>();
    Store.data.blueprintCheckpoints.put(
        worldDim + "|" + CHECKPOINT_KEY, buildSummaryJson(state));
    Store.saveSoon();
  }

  private JsonObject buildSummaryJson(String state) {
    var o = new JsonObject();
    o.addProperty("state", state);
    o.addProperty("name", fileName);
    o.addProperty("file_name", fileName);
    o.addProperty("display_name", displayName);
    o.addProperty("world_dim", worldDim);
    o.addProperty("blueprint_sha256", blueprintSha256);
    o.addProperty("anchor_x", anchorX);
    o.addProperty("anchor_y", anchorY);
    o.addProperty("anchor_z", anchorZ);
    o.addProperty("rotation", rotation);
    o.addProperty("mirror", mirror);
    o.addProperty("y_min", yMin);
    o.addProperty("y_max", yMax);
    o.addProperty("scaffold_mode", requestedScaffoldMode.name().toLowerCase(Locale.ROOT));
    if (!requestedScaffoldBlock.isBlank()) o.addProperty("scaffold_block", requestedScaffoldBlock);
    o.addProperty("keep_scaffold", keepScaffold);
    o.addProperty("scaffolds_placed", scaffoldsPlaced);
    o.addProperty("scaffolds_removed", scaffoldsRemoved);
    o.addProperty("scaffolds_collected", scaffoldsCollected);
    o.addProperty("scaffolds_remaining", scaffoldStack.size());
    o.addProperty("cursor", cursor);
    o.addProperty("total_steps", steps.size());
    o.addProperty("placed", placed);
    o.addProperty("already_matched", alreadyMatched);
    o.addProperty("state_mismatch", stateMismatch);
    o.addProperty("skipped_missing_item", skippedMissingItem);
    o.addProperty("skipped_unsupported", skippedUnsupported);
    o.addProperty("blocked_existing", blockedExisting);
    o.addProperty("unloaded", unloaded);
    o.addProperty("failed", failed);
    if (!needScaffoldError.isBlank()) o.addProperty("need_scaffold_detail", needScaffoldError);
    if (!scaffoldStack.isEmpty()) {
      var scArr = new JsonArray();
      for (ScaffoldEntry se : scaffoldStack) scArr.add(se.toJson());
      o.add("scaffold_stack", scArr);
    }
    if (!missingCounts.isEmpty()) {
      var miss = new JsonObject();
      for (var e : missingCounts.entrySet()) miss.addProperty(e.getKey(), e.getValue());
      o.add("missing_items", miss);
    }
    if (!failures.isEmpty()) {
      var arr = new JsonArray();
      failures.forEach(arr::add);
      o.add("failures", arr);
    }
    if (!stateMismatchSamples.isEmpty()) {
      var arr = new JsonArray();
      stateMismatchSamples.forEach(arr::add);
      o.add("state_mismatch_samples", arr);
    }
    if (!unloadedSamples.isEmpty()) {
      var arr = new JsonArray();
      unloadedSamples.forEach(arr::add);
      o.add("unloaded_samples", arr);
    }
    return o;
  }

  private static JsonObject sampleDiff(
      BlockPos p, BlueprintPlanner.BuildStep step, String curId, Map<String, String> curProps) {
    var o = new JsonObject();
    o.addProperty("wx", p.getX());
    o.addProperty("wy", p.getY());
    o.addProperty("wz", p.getZ());
    o.addProperty("expected_block", step.blockId());
    o.add("expected_state", step.placementState() == null ? new JsonObject() : step.placementState());
    o.addProperty("actual_block", curId);
    o.add("actual_state", Store.GSON.toJsonTree(curProps));
    return o;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> extractProperties(BlockState state) {
    Map<String, String> out = new LinkedHashMap<>();
    if (state == null) return out;
    for (var entry : state.getValues().entrySet()) {
      putProp(out, (net.minecraft.world.level.block.state.properties.Property) entry.getKey(), entry.getValue());
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static <T extends Comparable<T>> void putProp(
      Map<String, String> out,
      net.minecraft.world.level.block.state.properties.Property<T> prop,
      Comparable<?> val) {
    out.put(prop.getName(), prop.getName((T) val));
  }

  private static boolean isItemPlaceable(String itemId) {
    if (itemId == null || itemId.isBlank()) return false;
    ResourceLocation rl = ResourceLocation.tryParse(itemId);
    if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) return false;
    return BuiltInRegistries.ITEM.get(rl) instanceof BlockItem;
  }

  private static boolean hasBagBlockItem(String itemId) {
    ResourceLocation rl = ResourceLocation.tryParse(itemId);
    if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) return false;
    var item = BuiltInRegistries.ITEM.get(rl);
    if (!(item instanceof BlockItem)) return false;
    var p = mc().player;
    if (p == null) return false;
    for (int i = 0; i < 36; i++) {
      ItemStack s = p.getInventory().getItem(i);
      if (!s.isEmpty() && s.is(item)) return true;
    }
    return false;
  }

  private void stopChild() {
    RollbackJob.recordingSuppressed = false;
    if (teardownApproachNav != null) {
      teardownApproachNav.stop();
      teardownApproachNav = null;
    }
    teardownApproachEntry = null;
    teardownApproachCallback = null;
    teardownApproachIsStepOff = false;
    teardownStepOffTried = false;
    if (activePlace != null) {
      activePlace.stop();
      activePlace = null;
    }
    if (activeMine != null) {
      activeMine.stop();
      activeMine = null;
    }
    if (activeCollect != null) {
      activeCollect.stop();
      activeCollect = null;
    }
    pendingDropCollectIds.clear();
    activeStep = null;
    activeStepIndex = -1;
    PlayerControls.release();
  }

  @Override
  void stop() {
    stopChild();
    if (!finishedNormally && !checkpointCleared) {
      saveCheckpoint(tearingDown ? "teardown" : "paused");
    }
    super.stop();
  }

  @Override
  String interrupted() {
    saveCheckpoint(tearingDown ? "teardown" : "paused");
    return "蓝图施工已暂停；已新放置 " + placed + " 格，脚手架残留 " + scaffoldStack.size() + " 格，断点已保存。";
  }
}
