package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;

/**
 * Deterministic Maid-style local duty scheduler. Runs purely in client Java ticks (0 LLM tokens),
 * strictly yields to pause/human/health/self-defense/explicit tasks, and disarms on world change
 * or death so no stale action ever replays across sessions.
 */
public final class DutyScheduler {
  private static final Navigator idleNav = new Navigator();
  private static final Set<BlockPos> discoveredContainers = new LinkedHashSet<>();
  private static final Set<BlockPos> lootedContainers = new LinkedHashSet<>();
  private static final Set<BlockPos> activatedWaystones = new LinkedHashSet<>();
  private static final Map<UUID, Long> blockedDrops = new HashMap<>();
  private static int expeditionFailures;
  private static Actions.Job activeDutyJob;
  private static boolean armed, autoEatRefilling;
  private static String runtimeState = "待命",
      detail = "纯待命，等待指示",
      suspendReason = "",
      lastDimension = "";
  private static long nextScanAt,
      nextIdleDropScanAt,
      nextFarmAt,
      nextLightAt,
      nextEatAt,
      nextAlertAt,
      nextExploreAt,
      nextRestockAt,
      nextFollowRetryAt,
      lastAutoSwitchAt,
      manualOverrideUntil,
      ownerOfflineSince,
      lastAutonomyCheckAt;
  private static double lastOwnerDist = -1;
  private static long lastOwnerDistAt;
  private static long expeditionStartedAt, lastContainerScanAt;
  private static BlockPos idleGoal, lastContainerScanPos;
  private static int idleFailures;
  private static long nextIdleRetryAt;
  private static String idleError = "";

  private DutyScheduler() {}

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  static CompanionDuty duty() {
    if (Store.data.duty == null) Store.data.duty = new CompanionDuty();
    Store.data.duty.normalize();
    return Store.data.duty;
  }

  public static boolean isArmed() {
    return armed;
  }

  public static String shortStatus() {
    var d = duty();
    if (!armed) return "值守 · " + d.modeEnum().label + "（待命）";
    return "值守 · " + d.modeEnum().label + " · " + detail;
  }

  private static void startDutyJob(Actions.Job job) {
    activeDutyJob = job;
    Actions.job = job;
  }

  public static boolean ownsCurrentJob() {
    return Actions.job != null && Actions.job == activeDutyJob;
  }

  public static void yieldCurrentJob(String reason) {
    if (ownsCurrentJob()) {
      Actions.cancel();
      activeDutyJob = null;
    }
    autoEatRefilling = false;
    suspend(reason);
  }

  public static void arm(boolean on, String reason) {
    armed = on;
    idleNav.stop();
    idleGoal = null;
    resetIdleRetry();
    resetOwnerTracking();
    if (!on) yieldCurrentJob(reason);
    if (on) {
      suspendReason = "";
      expeditionStartedAt =
          duty().modeEnum() == CompanionDuty.Mode.EXPEDITION_GATHER
              ? System.currentTimeMillis()
              : 0L;
      discoveredContainers.clear();
      lootedContainers.clear();
      CompanionDuty.pruneExpiredDrops(blockedDrops, System.currentTimeMillis());
      expeditionFailures = 0;
      nextFollowRetryAt = 0L;
      runtimeState = "值守中";
      detail = reason == null || reason.isBlank() ? duty().modeEnum().label : reason;
      if (mc().level != null) lastDimension = mc().level.dimension().location().toString();
      if (duty().modeEnum() == CompanionDuty.Mode.ESCORT_GUARD)
        FollowControl.ensureFollowingSaved();
    } else {
      runtimeState = "已待命";
      suspendReason = reason == null ? "" : reason;
      detail = suspendReason.isBlank() ? "值守已停止" : suspendReason;
    }
  }

  public static void setMode(CompanionDuty.Mode mode, boolean armNow, String reason) {
    setMode(mode, armNow, reason, true);
  }

  public static void setMode(
      CompanionDuty.Mode mode, boolean armNow, String reason, boolean humanManual) {
    var d = duty();
    CompanionDuty.Mode prev = d.modeEnum();
    if (humanManual) manualOverrideUntil = System.currentTimeMillis() + 5 * 60_000L;
    yieldCurrentJob("切换值守预设：" + mode.label);
    d.mode = mode.name();
    d.normalize();
    if (mode != CompanionDuty.Mode.EXPEDITION_GATHER) expeditionStartedAt = 0L;
    if (prev == CompanionDuty.Mode.ESCORT_GUARD && mode != CompanionDuty.Mode.ESCORT_GUARD) {
      if (FollowControl.active()) FollowControl.stop("切换值守预设：" + mode.label);
    }
    if (mode == CompanionDuty.Mode.BASE_STEWARD && !d.hasHome && mc().player != null && mc().level != null) {
      setHomeHere(false);
    }
    Store.save();
    if (mode == CompanionDuty.Mode.STANDBY) {
      arm(false, reason == null ? "已切换为纯待命" : reason);
    } else if (armNow) {
      if (!Agent.enabled) Agent.start();
      arm(true, reason == null ? "切换值守：" + mode.label : reason);
    } else {
      runtimeState = armed ? "值守中" : "已预设(未启动)";
      detail = "已预设为 " + mode.label;
    }
  }

  public static void setHomeHere(boolean saveNow) {
    var mc = mc();
    if (mc.player == null || mc.level == null) return;
    BlockPos cell = Navigator.standingCell();
    var d = duty();
    d.hasHome = true;
    d.homeDimension = mc.level.dimension().location().toString();
    d.homeX = cell.getX();
    d.homeY = cell.getY();
    d.homeZ = cell.getZ();
    d.homePlaceName = "当前基地锚点";
    d.normalize();
    ExplorationMemory.note(cell, ExplorationGraph.Kind.SAFE_POINT);
    if (saveNow) Store.save();
    Companion.notifyUser("已设定基地锚点：" + cell.toShortString());
  }

  public static void bindHomePlace(Store.Place place) {
    if (place == null) return;
    var d = duty();
    d.hasHome = true;
    d.homeDimension = place.dimension == null ? "" : place.dimension;
    d.homeX = place.x;
    d.homeY = place.y;
    d.homeZ = place.z;
    d.homePlaceName = place.name == null ? "" : place.name;
    d.normalize();
    Store.save();
  }

  public static void suspend(String reason) {
    if (idleGoal != null) idleNav.stop();
    idleGoal = null;
    resetOwnerTracking();
    if (armed) {
      runtimeState = "挂起退让";
      suspendReason = reason == null ? "" : reason;
    }
  }

  public static void disarmForSafety(String reason) {
    armed = false;
    idleNav.stop();
    idleGoal = null;
    expeditionStartedAt = 0L;
    discoveredContainers.clear();
    lootedContainers.clear();
    activatedWaystones.clear();
    blockedDrops.clear();
    autoEatRefilling = false;
    expeditionFailures = 0;
    resetIdleRetry();
    resetOwnerTracking();
    runtimeState = "安全待命";
    suspendReason = reason == null ? "" : reason;
    detail = suspendReason.isBlank() ? "重连/切换后默认安全待命" : suspendReason;
  }

  private static void resetOwnerTracking() {
    lastOwnerDist = -1;
    ownerOfflineSince = 0L;
  }

  private static void resetIdleRetry() {
    idleFailures = 0;
    nextIdleRetryAt = 0L;
    idleError = "";
  }

  /** Unarmed standby care (standby_auto_eat); follow ticking stays with LocalExecutor. */
  public static void tickStandby() {
    var mc = mc();
    if (armed || mc.player == null || mc.level == null || !mc.player.isAlive()) return;
    if (Actions.job != activeDutyJob) activeDutyJob = null;
    var d = duty();
    tickStandbyAutoEat(mc, d, d.modeEnum());
  }

  private static void tickStandbyAutoEat(Minecraft mc, CompanionDuty d, CompanionDuty.Mode mode) {
    if (mc.screen == null
        && Actions.job == null
        && !HarnessController.busy()
        && Agent.task.isBlank()
        && !FollowControl.active()
        && mc.gameMode != null
        && mc.gameMode.getPlayerMode() == GameType.SURVIVAL) {
      long now = System.currentTimeMillis();
      int food = mc.player.getFoodData().getFoodLevel();
      if (d.shouldTriggerAutoEat(mode, armed, autoEatRefilling, food)) {
        if (now >= nextEatAt) tryAutoEat(now, d);
      } else {
        autoEatRefilling = false;
      }
    }
  }

  public static void tick() {
    var mc = mc();
    if (mc.player == null || mc.level == null) return;
    String dim = mc.level.dimension().location().toString();
    if (!lastDimension.isEmpty() && !lastDimension.equals(dim)) {
      if (armed && duty().modeEnum() != CompanionDuty.Mode.ESCORT_GUARD) {
        disarmForSafety("维度切换，值守进入安全待命");
        return;
      }
    }
    lastDimension = dim;
    if (!mc.player.isAlive()) {
      disarmForSafety("角色死亡，值守已安全待命");
      return;
    }

    var d = duty();
    CompanionDuty.Mode mode = d.modeEnum();
    if (Actions.job != activeDutyJob) activeDutyJob = null;
    if (!armed || mode == CompanionDuty.Mode.STANDBY) {
      idleNav.stop();
      idleGoal = null;
      if (FollowControl.active()) FollowControl.tick();
      tickStandbyAutoEat(mc, d, mode);
      return;
    }
    if (mc.screen != null || Actions.job != null || HarnessController.busy() || !Agent.task.isBlank()) {
      if (FollowControl.active()) FollowControl.suspend("等待当前任务或界面结束");
      if (!ownsCurrentJob()) resetOwnerTracking();
      return;
    }

    long now = System.currentTimeMillis();
    if (now >= lastAutonomyCheckAt) {
      lastAutonomyCheckAt = now + 2000L;
      if (evaluateAutonomy(mc, d, mode, now)) return;
    }
    int food = mc.player.getFoodData().getFoodLevel();
    if (d.shouldTriggerAutoEat(mode, armed, autoEatRefilling, food)) {
      if (now >= nextEatAt && tryAutoEat(now, d)) return;
    } else {
      autoEatRefilling = false;
    }

    switch (mode) {
      case BASE_STEWARD -> tickBaseSteward(mc, d, dim, now);
      case ESCORT_GUARD -> tickEscortGuard(mc, d, now);
      case EXPEDITION_GATHER -> tickExpedition(mc, d, dim, now);
      default -> {}
    }
  }

  static void bindEscortTarget(UUID uuid, String name) {
    var d = duty();
    d.escortTargetUuid = uuid == null ? "" : uuid.toString();
    d.escortTargetName = name == null ? "" : name.trim();
    Store.save();
  }

  private static Player resolveEscortOrOwnerPlayer(Minecraft mc, CompanionDuty d) {
    if (d != null && d.escortTargetUuid != null && !d.escortTargetUuid.isBlank()) {
      try {
        UUID id = UUID.fromString(d.escortTargetUuid);
        Player p = mc.level == null ? null : mc.level.getPlayerByUUID(id);
        if (p != null && p != mc.player && p.isAlive() && !p.isSpectator()) return p;
      } catch (IllegalArgumentException ignored) {
      }
    }
    if (d != null && d.escortTargetName != null && !d.escortTargetName.isBlank()) {
      Player p = OwnerContext.findPlayer(mc, d.escortTargetName);
      if (p != null) return p;
    }
    return OwnerContext.findOwner(mc);
  }

  private static boolean evaluateAutonomy(
      Minecraft mc, CompanionDuty d, CompanionDuty.Mode mode, long now) {
    Player loadedOwner =
        mode == CompanionDuty.Mode.ESCORT_GUARD
            ? resolveEscortOrOwnerPlayer(mc, d)
            : OwnerContext.findOwner(mc);
    boolean ownerOnline = loadedOwner != null;
    if (!ownerOnline && mc.getConnection() != null) {
      UUID followId = FollowControl.currentTargetUuid();
      if (followId == null && d.escortTargetUuid != null && !d.escortTargetUuid.isBlank()) {
        try {
          followId = UUID.fromString(d.escortTargetUuid);
        } catch (IllegalArgumentException ignored) {
        }
      }
      String ownerName =
          mode == CompanionDuty.Mode.ESCORT_GUARD && d.escortTargetName != null && !d.escortTargetName.isBlank()
              ? d.escortTargetName.trim()
              : Store.config.owner == null ? "" : Store.config.owner.trim();
      for (var info : mc.getConnection().getOnlinePlayers()) {
        var prof = info.getProfile();
        if ((followId != null && followId.equals(prof.getId()))
            || (!ownerName.isBlank() && ownerName.equalsIgnoreCase(prof.getName()))) {
          ownerOnline = true;
          break;
        }
      }
    }
    double ownerDist = -1;
    boolean movingAway = false;
    if (loadedOwner != null) {
      ownerOfflineSince = 0L;
      ownerDist = mc.player.distanceTo(loadedOwner);
      if (lastOwnerDist >= 0 && now - lastOwnerDistAt <= 3_500L && ownerDist > lastOwnerDist + 1.5) {
        if (!d.hasHome
            || loadedOwner.blockPosition().distSqr(new BlockPos(d.homeX, d.homeY, d.homeZ))
                > (double) d.baseRadius * d.baseRadius * 0.64) {
          movingAway = true;
        }
      }
      lastOwnerDist = ownerDist;
      lastOwnerDistAt = now;
    } else if (!ownerOnline) {
      lastOwnerDist = -1;
      if (ownerOfflineSince == 0L) ownerOfflineSince = now;
    } else {
      ownerOfflineSince = 0L;
    }
    long offlineMs = ownerOfflineSince == 0L ? 0L : Math.max(0L, now - ownerOfflineSince);
    BlockPos homePos = new BlockPos(d.homeX, d.homeY, d.homeZ);
    boolean homeReachable =
        d.hasHome
            && mc.level.dimension().location().toString().equals(d.homeDimension)
            && mc.level.hasChunkAt(homePos)
            && mc.player.blockPosition().distSqr(homePos) <= 96.0 * 96.0;
    var tr =
        DutyAutonomyPolicy.recommendTransition(
            d.autonomyLevelEnum(),
            mode,
            armed,
            d.hasHome,
            ownerOnline,
            ownerDist,
            movingAway,
            d.baseRadius,
            freeMainSlots(mc.player),
            d.minFreeSlots,
            false,
            now,
            lastAutoSwitchAt,
            manualOverrideUntil,
            offlineMs,
            homeReachable);
    if (tr == null) return false;
    lastAutoSwitchAt = now;
    if (tr.targetMode() == CompanionDuty.Mode.ESCORT_GUARD
        && FollowControl.savedTarget() == null
        && loadedOwner != null) {
      FollowControl.select(
          new FollowControl.Target(
              loadedOwner.getUUID(), loadedOwner.getGameProfile().getName(), loadedOwner));
    }
    WorkLedger.add("duty", "自主切档：" + tr.reason());
    Companion.notifyUser(tr.reason());
    setMode(tr.targetMode(), tr.armed(), tr.reason(), false);
    return true;
  }

  private static boolean tryAutoEat(long now, CompanionDuty d) {
    var mc = mc();
    for (int i = 0; i < 36; i++) {
      var s = mc.player.getInventory().getItem(i);
      if (s.isEmpty() || s.getFoodProperties(mc.player) == null) continue;
      String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
      if (!ControlPolicy.FOOD.contains(id)) continue;
      autoEatRefilling = true;
      nextEatAt = now + 1_500L;
      idleNav.stop();
      idleGoal = null;
      runtimeState = "自理进食";
      detail = "饱食度偏低，正在补充食物至 " + d.autoEatTarget;
      startDutyJob(
          new EatJob(
              "*",
              r -> {
                WorkLedger.add("duty", "值守自理进食：" + Rules.clean(r, 120));
                if (Rules.actionFailed(r)) {
                  autoEatRefilling = false;
                  nextEatAt = System.currentTimeMillis() + 15_000L;
                } else if (mc().player != null
                    && mc().player.getFoodData().getFoodLevel() >= d.autoEatTarget) {
                  autoEatRefilling = false;
                }
              }));
      return true;
    }
    autoEatRefilling = false;
    nextEatAt = now + 15_000L;
    return false;
  }

  private static boolean launchBaseDropSweepIfNeeded(
      Minecraft mc, CompanionDuty d, String dim, BlockPos home, long now) {
    if (!d.basePickup || freeMainSlots(mc.player) < d.minFreeSlots || !canCollectDrops(mc))
      return false;
    CompanionDuty.pruneExpiredDrops(blockedDrops, now);
    var drops =
        mc.level.getEntitiesOfClass(
            ItemEntity.class,
            new net.minecraft.world.phys.AABB(home).inflate(Math.min(16, d.baseRadius)),
            e ->
                e.isAlive()
                    && !e.hasPickUpDelay()
                    && !e.isInWater()
                    && !CompanionDuty.isDropCooldownActive(blockedDrops, e.getUUID(), now)
                    && d.insideBaseTether(
                        dim, e.blockPosition().getX(), e.blockPosition().getY(), e.blockPosition().getZ()));
    if (drops.isEmpty()) return false;
    idleNav.stop();
    idleGoal = null;
    runtimeState = "基地拾取";
    detail = "正在清场收取基地内掉落物（" + drops.size() + "堆）";
    startDutyJob(
        new CollectionJob(
                "*",
                16,
                Math.min(16, d.baseRadius),
                home,
                e ->
                    !e.isInWater()
                        && !CompanionDuty.isDropCooldownActive(
                            blockedDrops, e.getUUID(), System.currentTimeMillis())
                        && d.insideBaseTether(
                            dim,
                            e.blockPosition().getX(),
                            e.blockPosition().getY(),
                            e.blockPosition().getZ()),
                true,
                r -> WorkLedger.add("duty", "基地拾取：" + Rules.clean(r, 120)))
            .backgroundDuty(true)
            .withSkipListener(id -> blockedDrops.put(id, System.currentTimeMillis() + 75_000L)));
    return true;
  }

  private static void tickBaseSteward(
      Minecraft mc, CompanionDuty d, String dim, long now) {
    if (!d.hasHome) {
      runtimeState = "缺少基地锚点";
      detail = "请先在「跟随与陪同」点击以当前位置设为基地中心";
      return;
    }
    if (!dim.equals(d.homeDimension)) {
      runtimeState = "跨维挂起";
      detail = "当前不在基地维度 " + d.homeDimension;
      return;
    }
    BlockPos home = new BlockPos(d.homeX, d.homeY, d.homeZ);
    BlockPos feet = mc.player.blockPosition();
    if (!d.insideBaseTether(dim, feet.getX(), feet.getY(), feet.getZ())) {
      runtimeState = "返回围栏";
      detail = "已超出基地 " + d.baseRadius + " 格围栏，正在返回锚点";
      if (!walkIdleTo(home)) disarmForSafety("返回锚点不可达：" + Rules.clean(idleError, 80));
      return;
    }
    if (idleGoal != null) {
      if (now >= nextIdleDropScanAt) {
        nextIdleDropScanAt = now + 1500L;
        if (launchBaseDropSweepIfNeeded(mc, d, dim, home, now)) return;
      }
      int st = idleNav.tick();
      if (st != 0) {
        idleGoal = null;
        if (st < 0) noteIdleFailure(now, idleNav.error());
        else resetIdleRetry();
      } else {
        runtimeState = "回基地站位";
        detail = "巡逻返程中，持续监测新掉落物";
        return;
      }
    }
    if (now < nextScanAt) return;
    nextScanAt = now + 1500L;

    if (launchBaseDropSweepIfNeeded(mc, d, dim, home, now)) return;

    if (d.baseFarm
        && now >= nextFarmAt
        && mc.gameMode.getPlayerMode() == GameType.SURVIVAL
        && canBreakBlocks()
        && canPlaceBlocks()) {
      String matureCrop = findNearbyMatureVanillaCrop(mc, d, dim);
      if (matureCrop != null) {
        nextFarmAt = now + 20_000L;
        idleNav.stop();
        runtimeState = "照料农田";
        detail = "基地内发现成熟作物，正在收割并补种";
        startDutyJob(
            new FarmJob(
                matureCrop,
                6,
                Math.min(12, d.baseRadius),
                r -> WorkLedger.add("duty", "基地农田：" + Rules.clean(r, 120))));
        return;
      }
    }

    if (d.baseLighting && now >= nextLightAt && canPlaceBlocks() && hasVanillaTorches(mc)) {
      if (hasDarkStandableSpot(mc, feet, 8, d, dim)) {
        nextLightAt = now + 25_000L;
        idleNav.stop();
        var args = new JsonObject();
        args.addProperty("count", 3);
        args.addProperty("radius", 8);
        runtimeState = "基地补光";
        detail = "基地存在昏暗区域，正在补充火把";
        startDutyJob(
            new IlluminateJob(
                args, r -> WorkLedger.add("duty", "基地补光：" + Rules.clean(r, 120))));
        return;
      }
    }

    if (d.baseRestock
        && now >= nextRestockAt
        && freeMainSlots(mc.player) < d.minFreeSlots
        && !Store.data.resourceDelivery.isBlank()
        && canOpenContainers()) {
      var targetPlace = Store.data.places.get(Store.data.resourceDelivery);
      if (targetPlace != null
          && dim.equals(targetPlace.dimension)
          && mc.level.hasChunkAt(targetPlace.pos())
          && d.insideBaseTether(dim, targetPlace.x, targetPlace.y, targetPlace.z)) {
        DepositPick pick = findDepositableSurplusItem(mc.player);
        nextRestockAt = now + 30_000L;
        if (pick != null) {
          idleNav.stop();
          runtimeState = "基地归箱";
          detail =
              "背包空槽偏少，向交付箱 "
                  + Store.data.resourceDelivery
                  + " 存入富余物品 "
                  + pick.itemId();
          startDutyJob(
              new ContainerJob(
                  targetPlace.pos(),
                  pick.filter(),
                  pick.surplus(),
                  true,
                  r -> WorkLedger.add("duty", "基地归箱：" + Rules.clean(r, 120))));
          return;
        }
      }
    }

    if (d.baseReturnIdle && feet.distSqr(home) > 25.0 && idleFailures < 3) {
      runtimeState = "返回站位";
      detail = "巡检无待办，走回基地待命点";
      walkIdleTo(home);
    } else {
      runtimeState = "驻守巡检";
      detail = "基地一切正常 · 围栏半径 " + d.baseRadius + " 格";
    }
  }

  private static void tickEscortGuard(Minecraft mc, CompanionDuty d, long now) {
    if (!FollowControl.active()) {
      if (now < nextFollowRetryAt || !FollowControl.ensureFollowingSaved()) {
        if (now >= nextFollowRetryAt) nextFollowRetryAt = now + 3_000L;
        runtimeState = "等待选人";
        detail = "请在「跟随与陪同」先选择要陪伴的玩家";
        return;
      }
      nextFollowRetryAt = 0L;
    }
    UUID ownerId = FollowControl.currentTargetUuid();
    Player owner = ownerId == null ? null : mc.level.getPlayerByUUID(ownerId);
    if (owner == null) {
      runtimeState = "跟随寻路";
      detail = FollowControl.shortStatus();
      FollowControl.tick();
      return;
    }

    double distToOwner = mc.player.distanceTo(owner);
    if (!d.withinGuardLeash(distToOwner)) {
      runtimeState = "快速归位";
      detail = "距离主人过远（>" + d.guardLeash + "格），优先紧跟归队";
      FollowControl.tick();
      return;
    }

    if (d.guardOwner && now >= nextScanAt) {
      nextScanAt = now + 1000L;
      var threats =
          mc.level.getEntitiesOfClass(
              LivingEntity.class,
              owner.getBoundingBox().inflate(d.guardRadius),
              e -> e != mc.player && e != owner && e.isAlive() && e instanceof Monster);
      threats.sort(
          Comparator.comparingInt(
                  (LivingEntity m) ->
                      CombatPolicy.recent(System.nanoTime(), CombatEvidence.ownerAttacker(m.getUUID()))
                              || CombatPolicy.recent(System.nanoTime(), CombatEvidence.attacker(m.getUUID()))
                          ? 0
                          : 1)
              .thenComparingDouble(m -> m.distanceToSqr(owner)));

      boolean canHunt = canHuntMobs();
      boolean hasMelee = MeleeOps.bestWeapon(false) >= 0;
      boolean hasRanged = hasBowAndArrows(mc);
      boolean survivalReady =
          mc.gameMode.getPlayerMode() == GameType.SURVIVAL
              && !SurvivalPolicy.blocked(
                  mc.player.getHealth(),
                  SurvivalPolicy.combat(mc.player.getMaxHealth()),
                  Store.config.testIgnoreLowHealth)
              && mc.player.getFoodData().getFoodLevel() >= 6;

      for (LivingEntity m : threats) {
        if (!mc.player.hasLineOfSight(m) && owner.distanceTo(m) > 5.0) continue;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString();
        boolean tamed =
            m instanceof TamableAnimal ta && ta.isTame()
                || m instanceof AbstractHorse horse && horse.isTamed();
        boolean friendlyNpc =
            m instanceof AbstractVillager
                || m instanceof net.minecraft.world.entity.animal.IronGolem;
        boolean boss = m instanceof WitherBoss || m instanceof EnderDragon;
        var decision =
            d.evaluateGuardTarget(
                id,
                m instanceof Player,
                tamed,
                friendlyNpc,
                m.hasCustomName(),
                boss,
                Store.config.attackPlayers,
                Store.config.attackNamed,
                hasRanged);
        if (decision == CompanionDuty.GuardAction.ALERT_AND_AVOID) {
          if (now >= nextAlertAt) {
            nextAlertAt = now + 6000L;
            Companion.notifyUser("警戒：主人附近发现破坏地形生物 " + id + "，已避开近战防炸！");
          }
          continue;
        }
        if (decision == CompanionDuty.GuardAction.ENGAGE_RANGED_SAFE
            && canHunt
            && survivalReady
            && hasRanged) {
          FollowControl.suspend("远程护卫中");
          runtimeState = "远程护卫";
          detail = "远程牵制威胁 " + id;
          startDutyJob(
              new RangedHuntJob(
                  id,
                  1,
                  Math.min(18, d.guardLeash + 4),
                  "minecraft:creeper".equals(id),
                  r -> WorkLedger.add("duty", "远程护卫：" + Rules.clean(r, 120))));
          return;
        }
        if (decision == CompanionDuty.GuardAction.ENGAGE_MELEE && canHunt && survivalReady && hasMelee) {
          boolean creeperMelee = "minecraft:creeper".equals(id) && !d.avoidTerrainDestroyingMobs;
          FollowControl.suspend("贴身护卫战斗");
          runtimeState = "护卫交战";
          detail = "保护主人 · 迎击 " + id;
          startDutyJob(
              new HuntJob(
                  id,
                  1,
                  Math.min(14, d.guardLeash),
                  creeperMelee,
                  r -> WorkLedger.add("duty", "护卫交战：" + Rules.clean(r, 120))));
          return;
        }
        if (decision != CompanionDuty.GuardAction.IGNORE && !canHunt) {
          detail = "随行警戒中（已降级：需开启「允许清怪」才能主动出击）";
        }
      }
    }

    if (d.escortPickup
        && distToOwner <= 6.5
        && freeMainSlots(mc.player) >= d.minFreeSlots
        && canCollectDrops(mc)
        && now >= nextScanAt) {
      nextScanAt = now + 2500L;
      CompanionDuty.pruneExpiredDrops(blockedDrops, now);
      var drops =
          mc.level.getEntitiesOfClass(
              ItemEntity.class,
              owner.getBoundingBox().inflate(5),
              e ->
                  e.isAlive()
                      && !e.hasPickUpDelay()
                      && !e.isInWater()
                      && !CompanionDuty.isDropCooldownActive(blockedDrops, e.getUUID(), now)
                      && Math.abs(e.getY() - owner.getY()) <= 2.0);
      if (!drops.isEmpty()) {
        FollowControl.suspend("顺手捡取主人周边掉落物");
        runtimeState = "随行拾取";
        detail = "帮主人拾取周边掉落物";
        BlockPos oPos = owner.blockPosition();
        startDutyJob(
            new CollectionJob(
                    "*",
                    6,
                    6,
                    oPos,
                    e ->
                        e.blockPosition().distSqr(oPos) <= 36.0
                            && !e.isInWater()
                            && !CompanionDuty.isDropCooldownActive(
                                blockedDrops, e.getUUID(), System.currentTimeMillis()),
                    true,
                    r -> WorkLedger.add("duty", "随行拾取：" + Rules.clean(r, 120)))
                .backgroundDuty(true)
                .withSkipListener(id -> blockedDrops.put(id, System.currentTimeMillis() + 75_000L)));
        return;
      }
    }

    if (d.escortTorch
        && now >= nextLightAt
        && canPlaceBlocks()
        && hasVanillaTorches(mc)
        && mc.level.getMaxLocalRawBrightness(mc.player.blockPosition()) <= 4) {
      nextLightAt = now + 20_000L;
      FollowControl.suspend("暗处补光开路");
      var args = new JsonObject();
      args.addProperty("count", 1);
      args.addProperty("radius", 4);
      runtimeState = "暗处开路";
      detail = "光线过暗，补插火把照明";
      startDutyJob(
          new IlluminateJob(
              args, r -> WorkLedger.add("duty", "随行照明：" + Rules.clean(r, 120))));
      return;
    }

    runtimeState = "陪同随行";
    detail = FollowControl.shortStatus();
    FollowControl.tick();
  }

  private static void tickExpedition(
      Minecraft mc, CompanionDuty d, String dim, long now) {
    if (expeditionStartedAt == 0L) expeditionStartedAt = now;
    int free = freeMainSlots(mc.player);
    boolean timeUp = (now - expeditionStartedAt) >= d.expeditionMinutes * 60_000L;
    boolean packFull = free < d.minFreeSlots;
    if (timeUp || packFull) {
      String reason = packFull ? "背包空槽达到回城阈值（" + free + "/" + d.minFreeSlots + "）" : "到达单趟外出时长预算";
      if (idleGoal == null
          && now >= nextIdleRetryAt
          && ExplorationMemory.graph() != null
          && ExplorationMemory.current() != null) {
        try {
          var args = new JsonObject();
          args.addProperty("target", "nearest_safe");
          args.addProperty("max_seconds", 90);
          runtimeState = "外出回城";
          detail = reason + "，沿探索图安全返回";
          ExplorationJob.start(
              "explore_return",
              args,
              r -> {
                WorkLedger.add("duty", "外出回城：" + Rules.clean(r, 120));
                if (!Rules.actionFailed(r)) {
                  finishExpeditionReturn(d, reason);
                } else {
                  disarmForSafety("外出回城受阻（" + Rules.clean(r, 60) + "），已安全原地待命");
                }
              });
          activeDutyJob = Actions.job;
          return;
        } catch (IllegalArgumentException ignored) {
        }
      }
      if (d.hasHome && dim.equals(d.homeDimension)) {
        BlockPos home = new BlockPos(d.homeX, d.homeY, d.homeZ);
        if (mc.player.blockPosition().distSqr(home) > 16.0) {
          runtimeState = "返回基地";
          detail = reason + "，正在返回基地锚点";
          if (!walkIdleTo(home))
            disarmForSafety(
                "外出回城受阻（返回锚点不可达：" + Rules.clean(idleError, 60) + "），已安全原地待命");
          return;
        }
      }
      finishExpeditionReturn(d, reason);
      return;
    }

    if (now < nextScanAt) return;
    nextScanAt = now + 2500L;

    // 1. Scan & classify nearby Waystones + Lootr / wild containers without touching base chests
    BlockPos feet = mc.player.blockPosition();
    BlockPos lootTarget = null;
    BlockPos waystoneTarget = null;
    CompanionDuty.ContainerClass lootClass = CompanionDuty.ContainerClass.NONE;
    boolean movedFar =
        lastContainerScanPos == null || lastContainerScanPos.distSqr(feet) >= 9.0;
    if (movedFar || now - lastContainerScanAt >= 4000L) {
      lastContainerScanPos = feet;
      lastContainerScanAt = now;
      for (int dx = -7; dx <= 7; dx++) {
        for (int dz = -7; dz <= 7; dz++) {
          for (int dy = -2; dy <= 2; dy++) {
            BlockPos p = feet.offset(dx, dy, dz);
            if (!mc.level.hasChunkAt(p)) continue;
            String id = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString();
            if (waystoneTarget == null
                && CompanionDuty.isWaystoneBlock(id)
                && !activatedWaystones.contains(p)
                && !activatedWaystones.contains(p.above())
                && !activatedWaystones.contains(p.below())) {
              waystoneTarget = p.immutable();
            }
            boolean isBase = d.hasHome && d.insideBaseTether(dim, p.getX(), p.getY(), p.getZ());
            CompanionDuty.ContainerClass cls = d.classifyContainer(id, isBase);
            if (cls == CompanionDuty.ContainerClass.WILD_CONTAINER && isRegisteredStorage(dim, p))
              cls = CompanionDuty.ContainerClass.BASE_PROTECTED;
            if (cls == CompanionDuty.ContainerClass.NONE || cls == CompanionDuty.ContainerClass.BASE_PROTECTED)
              continue;
            if (discoveredContainers.add(p.immutable())) {
              String tagName =
                  cls == CompanionDuty.ContainerClass.LOOTR_INDEPENDENT
                      ? "Lootr独立战利品箱"
                      : "野外容器";
              ExplorationMemory.note(safePointNear(p), ExplorationGraph.Kind.SAFE_POINT);
              WorkLedger.add("duty", "发现" + tagName + " @ " + p.toShortString() + " (" + id + ")");
            }
            if (lootTarget == null
                && !lootedContainers.contains(p)
                && d.canLootContainer(cls)
                && canOpenContainers()) {
              lootTarget = p.immutable();
              lootClass = cls;
            }
          }
        }
      }
    }
    if (waystoneTarget != null && canPlaceBlocks()) {
      final BlockPos ws = waystoneTarget;
      activatedWaystones.add(ws);
      activatedWaystones.add(ws.above());
      activatedWaystones.add(ws.below());
      runtimeState = "激活传送石碑";
      detail = "接近并点亮路过 Waystone：" + ws.toShortString();
      startDutyJob(createWaystoneActivationJob(ws));
      return;
    }
    if (lootTarget != null && free >= d.minFreeSlots) {
      lootedContainers.add(lootTarget);
      runtimeState =
          lootClass == CompanionDuty.ContainerClass.LOOTR_INDEPENDENT
              ? "搜刮 Lootr 宝箱"
              : "搜刮野外容器";
      detail = "正在查看并收取 " + lootTarget.toShortString();
      startDutyJob(
          new ContainerJob(
              lootTarget,
              "*",
              16,
              false,
              r -> WorkLedger.add("duty", "野外搜刮：" + Rules.clean(r, 120))));
      return;
    }

    // 2. Sweep loose nearby drops (respecting persistent blockedDrops cooldown)
    CompanionDuty.pruneExpiredDrops(blockedDrops, now);
    if (free >= d.minFreeSlots && canCollectDrops(mc)) {
      var drops =
          mc.level.getEntitiesOfClass(
              ItemEntity.class,
              mc.player.getBoundingBox().inflate(8),
              e ->
                  e.isAlive()
                      && !e.hasPickUpDelay()
                      && !e.isInWater()
                      && !CompanionDuty.isDropCooldownActive(blockedDrops, e.getUUID(), now));
      if (!drops.isEmpty()) {
        runtimeState = "外出捡物";
        detail = "拾取附近地面散落战利品";
        startDutyJob(
            new CollectionJob(
                    "*",
                    8,
                    8,
                    mc.player.blockPosition(),
                    e ->
                        !e.isInWater()
                            && !CompanionDuty.isDropCooldownActive(
                                blockedDrops, e.getUUID(), System.currentTimeMillis()),
                    true,
                    r -> WorkLedger.add("duty", "外出拾取：" + Rules.clean(r, 120)))
                .backgroundDuty(true)
                .withSkipListener(id -> blockedDrops.put(id, System.currentTimeMillis() + 75_000L)));
        return;
      }
    }

    // 3. Active outbound roam exploration (with STALE auto-healing & no 0-step fake loop)
    if (now >= nextExploreAt
        && ExplorationMemory.graph() != null
        && ExplorationMemory.current() != null) {
      nextExploreAt = now + 4500L;
      var roamArgs = new JsonObject();
      roamArgs.addProperty("max_distance", 48);
      roamArgs.addProperty("max_seconds", 45);
      roamArgs.addProperty("return_after", false);
      roamArgs.addProperty("guard_hostiles", true);
      try {
        boolean unbreakableWorld = sampleLocallyUnbreakable(mc, feet);
        runtimeState = unbreakableWorld ? "后室搜刮探路" : "外出探图";
        detail = "主动向未探区域推进";
        RoamExplorationJob.start(
            roamArgs,
            r -> {
              WorkLedger.add("duty", "探图：" + Rules.clean(r, 120));
              if (Rules.actionFailed(r)) {
                if (++expeditionFailures >= 3) {
                  disarmForSafety("外出探图连续受阻，已原地安全待命（请换方向或手动引导）");
                }
              } else {
                expeditionFailures = 0;
              }
            });
        activeDutyJob = Actions.job;
        return;
      } catch (IllegalArgumentException ignored) {
      }
    }

    runtimeState = "外出待机扫描";
    detail =
        "已登记容器 "
            + discoveredContainers.size()
            + " · 空槽 "
            + free
            + (d.scavengeWildContainers ? " · 允许搜刮野生箱" : " · 仅扫描不翻箱");
  }

  private static void finishExpeditionReturn(CompanionDuty d, String reason) {
    long now = System.currentTimeMillis();
    var p = mc().player;
    int free = p == null ? 0 : freeMainSlots(p);
    var tr =
        DutyAutonomyPolicy.recommendTransition(
            d.autonomyLevelEnum(),
            CompanionDuty.Mode.EXPEDITION_GATHER,
            true,
            d.hasHome,
            true,
            -1,
            false,
            d.baseRadius,
            free,
            d.minFreeSlots,
            true,
            now,
            lastAutoSwitchAt,
            manualOverrideUntil,
            0L);
    if (tr != null) {
      lastAutoSwitchAt = now;
      WorkLedger.add("duty", "自主切档：" + tr.reason() + "（" + reason + "）");
      Companion.notifyUser(tr.reason());
      setMode(tr.targetMode(), tr.armed(), tr.reason(), false);
      return;
    }
    disarmForSafety("外出行程结束：" + reason);
  }

  /** Walks toward target with failure backoff; returns false after 3 consecutive failures. */
  private static boolean walkIdleTo(BlockPos target) {
    var mc = mc();
    if (mc.player == null) return true;
    long now = System.currentTimeMillis();
    if (idleGoal == null || !idleGoal.equals(target)) {
      if (now < nextIdleRetryAt) {
        appendIdleError();
        return idleFailures < 3;
      }
      if (idleNav.startExact(target)) idleGoal = target;
      else {
        String why = idleNav.error();
        idleNav.stop();
        noteIdleFailure(now, why);
        return idleFailures < 3;
      }
    }
    if (idleGoal != null) {
      int st = idleNav.tick();
      if (st != 0) {
        idleGoal = null;
        if (st < 0) noteIdleFailure(now, idleNav.error());
        else resetIdleRetry();
      }
    }
    return idleFailures < 3;
  }

  private static void noteIdleFailure(long now, String why) {
    idleFailures++;
    nextIdleRetryAt = now + 3_000L;
    idleError = why == null || why.isBlank() ? "寻路失败" : why;
    appendIdleError();
  }

  private static void appendIdleError() {
    if (idleFailures > 0)
      detail = detail + "（寻路受阻 " + idleFailures + "/3：" + Rules.clean(idleError, 60) + "）";
  }

  private static boolean isRegisteredStorage(String dim, BlockPos p) {
    for (BlockPos q : List.of(p, p.north(), p.south(), p.east(), p.west())) {
      for (var place : Store.data.places.values()) {
        if (place != null
            && dim.equals(place.dimension)
            && place.x == q.getX()
            && place.y == q.getY()
            && place.z == q.getZ()) return true;
      }
      var point =
          Store.data.world == null
              ? null
              : Store.data.world.get(WorldMemory.key(dim, q.getX(), q.getY(), q.getZ()));
      if (point != null
          && (point.inventory != null || point.label != null && !point.label.isBlank())) return true;
    }
    return false;
  }

  private static boolean sampleLocallyUnbreakable(Minecraft mc, BlockPos feet) {
    int solid = 0, unbreakable = 0;
    for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
      BlockPos p = feet.relative(dir);
      if (!mc.level.hasChunkAt(p)) continue;
      var st = mc.level.getBlockState(p);
      if (st.isAir()) continue;
      solid++;
      if (st.getDestroySpeed(mc.level, p) < 0) unbreakable++;
    }
    return solid > 0 && unbreakable == solid;
  }

  private static String findNearbyMatureVanillaCrop(
      Minecraft mc, CompanionDuty d, String dim) {
    BlockPos feet = mc.player.blockPosition();
    for (int dx = -8; dx <= 8; dx++) {
      for (int dz = -8; dz <= 8; dz++) {
        for (int dy = -2; dy <= 2; dy++) {
          BlockPos p = feet.offset(dx, dy, dz);
          if (!mc.level.hasChunkAt(p) || !d.insideBaseTether(dim, p.getX(), p.getY(), p.getZ()))
            continue;
          var state = mc.level.getBlockState(p);
          if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) continue;
          String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
          if (Set.of(
                  "minecraft:wheat",
                  "minecraft:carrots",
                  "minecraft:potatoes",
                  "minecraft:beetroots")
              .contains(id)) return id;
        }
      }
    }
    return null;
  }

  private static boolean hasDarkStandableSpot(
      Minecraft mc, BlockPos feet, int r, CompanionDuty d, String dim) {
    for (int dx = -r; dx <= r; dx += 2) {
      for (int dz = -r; dz <= r; dz += 2) {
        BlockPos p = feet.offset(dx, 0, dz);
        if (!mc.level.hasChunkAt(p) || !d.insideBaseTether(dim, p.getX(), p.getY(), p.getZ()))
          continue;
        if (Navigator.stand(p) && mc.level.getMaxLocalRawBrightness(p) <= 4) return true;
      }
    }
    return false;
  }

  private static boolean hasVanillaTorches(Minecraft mc) {
    for (int i = 0; i < 36; i++) {
      if (mc.player.getInventory().getItem(i).is(Items.TORCH)) return true;
    }
    return false;
  }

  private static boolean hasBowAndArrows(Minecraft mc) {
    boolean bow = false, arrow = false;
    for (int i = 0; i < 36; i++) {
      var s = mc.player.getInventory().getItem(i);
      if (s.is(Items.BOW) && s.getMaxDamage() - s.getDamageValue() > 2) bow = true;
      if (s.is(Items.ARROW) && !s.hasTag()) arrow = true;
    }
    return bow && arrow;
  }

  private record DepositPick(String itemId, String filter, int surplus) {}

  private static DepositPick findDepositableSurplusItem(Player p) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    Map<String, Integer> firstSlot = new HashMap<>();
    Map<String, Boolean> blocked = new HashMap<>();
    Map<String, String> plainFilter = new HashMap<>();
    for (int slot = 9; slot < 36; slot++) {
      var stack = p.getInventory().getItem(slot);
      if (stack.isEmpty()) continue;
      // NBT stacks (filled shulkers, enchanted books, named items...) are never auto-deposited.
      if (stack.hasTag()) continue;
      String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
      counts.merge(id, stack.getCount(), Integer::sum);
      firstSlot.putIfAbsent(id, slot);
      plainFilter.putIfAbsent(id, WorldSense.fingerprint(stack));
      Item item = stack.getItem();
      boolean equipment =
          item instanceof TieredItem
              || item instanceof ArmorItem
              || item instanceof ProjectileWeaponItem
              || item instanceof ShieldItem
              || item instanceof TridentItem
              || item instanceof FishingRodItem
              || item instanceof FlintAndSteelItem
              || item instanceof ShearsItem;
      boolean protectedOrMandatory =
          Store.data.bag.protectedItems.contains(id) || Store.data.bag.mandatory.contains(id);
      if (stack.isDamageableItem()
          || stack.isEdible()
          || equipment
          || protectedOrMandatory) {
        blocked.put(id, true);
      }
    }
    DepositPick best = null;
    for (var entry : counts.entrySet()) {
      String id = entry.getKey();
      int total = entry.getValue();
      int reserve = Store.data.bag.reserve(id);
      boolean itemBlocked = blocked.getOrDefault(id, false);
      int slot = firstSlot.getOrDefault(id, 9);
      if (!CompanionDuty.isSafeAutoDepositCandidate(
          slot, id, itemBlocked, false, false, false, total, reserve)) continue;
      int surplus = Math.min(64, total - Math.max(0, reserve));
      if (best == null || surplus > best.surplus()) {
        best = new DepositPick(id, plainFilter.getOrDefault(id, id), surplus);
      }
    }
    return best;
  }

  private static Actions.Job createWaystoneActivationJob(BlockPos ws) {
    return new Actions.Job(r -> WorkLedger.add("duty", "路石激活：" + Rules.clean(r, 120))) {
      final Navigator nav = new Navigator();
      final AimGate aim = new AimGate();
      int stage, waitTicks;
      boolean started;

      @Override
      void tick() {
        var mc = mc();
        if (mc.player == null || mc.level == null) {
          finish("失败：世界未就绪");
          return;
        }
        if (stage == 0) {
          if (!started) {
            started = true;
            if (Reach.hit(ws) == null && !nav.startForInteraction(ws)) {
              finish("跳过：无法接近路石 " + ws.toShortString());
              return;
            }
          }
          if (Reach.hit(ws) == null) {
            int r = nav.tick();
            if (r < 0) {
              finish("跳过：接近路石失败 " + ws.toShortString());
              return;
            }
            if (r == 0) return;
          } else {
            nav.stop();
          }
          stage = 1;
        }
        if (stage == 1) {
          var hit = Reach.hit(ws);
          if (hit == null) {
            finish("跳过：路石不可见");
            return;
          }
          if (!aim.ready(hit.getLocation())) return;
          mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
          mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
          stage = 2;
          waitTicks = 0;
          return;
        }
        if (++waitTicks <= 15) {
          if (mc.screen != null) {
            mc.player.closeContainer();
            mc.setScreen(null);
            waitTicks = 16;
          } else {
            return;
          }
        }
        if (mc.screen != null) {
          mc.player.closeContainer();
          mc.setScreen(null);
        }
        BlockPos safePos = safePointNear(ws);
        ExplorationMemory.note(safePos, ExplorationGraph.Kind.SAFE_POINT);
        Companion.notifyUser("途经并激活 Waystone 传送石碑，已记为安全点 @ " + safePos.toShortString());
        finish("成功：已触发并记录 Waystone 安全点 " + safePos.toShortString());
      }

      @Override
      void stop() {
        nav.stop();
        super.stop();
      }
    };
  }

  private static BlockPos safePointNear(BlockPos containerPos) {
    for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
      BlockPos side = containerPos.relative(dir);
      if (Navigator.stand(side)) return side;
    }
    BlockPos above = containerPos.above();
    if (Navigator.stand(above)) return above;
    return Navigator.standingCell();
  }

  private static int freeMainSlots(Player p) {
    int free = 0;
    for (int i = 9; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) free++;
    return free;
  }

  private static boolean canCollectDrops(Minecraft mc) {
    var mode = mc.gameMode == null ? null : mc.gameMode.getPlayerMode();
    return mode == GameType.SURVIVAL || mode == GameType.ADVENTURE;
  }

  private static boolean fullPermission() {
    return Store.config.fullGameControl || "FULL".equals(Store.config.permission);
  }

  private static boolean canBreakBlocks() {
    return fullPermission() || ("LIMITED".equals(Store.config.permission) && Store.config.allowMine);
  }

  private static boolean canPlaceBlocks() {
    return fullPermission() || ("LIMITED".equals(Store.config.permission) && Store.config.allowPlace);
  }

  private static boolean canHuntMobs() {
    return fullPermission() || ("LIMITED".equals(Store.config.permission) && Store.config.allowHunt);
  }

  private static boolean canOpenContainers() {
    return fullPermission()
        || ("LIMITED".equals(Store.config.permission) && Store.config.allowContainers);
  }

  public static JsonObject status() {
    var d = duty();
    var out = new JsonObject();
    out.addProperty("mode", d.modeEnum().name());
    out.addProperty("mode_label", d.modeEnum().label);
    out.addProperty("autonomy_level", d.autonomyLevelEnum().name());
    out.addProperty("armed", armed);
    out.addProperty("owns_active_job", ownsCurrentJob());
    out.addProperty("runtime_state", runtimeState);
    out.addProperty("detail", detail);
    if (!suspendReason.isBlank()) out.addProperty("suspend_reason", suspendReason);
    out.addProperty("has_home", d.hasHome);
    if (d.hasHome) {
      var home = new JsonObject();
      home.addProperty("dimension", d.homeDimension);
      home.addProperty("x", d.homeX);
      home.addProperty("y", d.homeY);
      home.addProperty("z", d.homeZ);
      home.addProperty("name", d.homePlaceName);
      out.add("home", home);
    }
    out.addProperty("base_radius", d.baseRadius);
    out.addProperty("follow_range", d.followRange);
    out.addProperty("guard_owner", d.guardOwner);
    out.addProperty("avoid_terrain_destroying_mobs", d.avoidTerrainDestroyingMobs);
    out.addProperty("guard_ranged_safe", d.guardRangedSafe);
    out.addProperty("guard_leash", d.guardLeash);
    out.addProperty("scavenge_wild_containers", d.scavengeWildContainers);
    out.addProperty("prefer_lootr_containers", d.preferLootrContainers);
    out.addProperty("auto_eat", d.autoEat);
    out.addProperty("standby_auto_eat", d.standbyAutoEat);
    out.addProperty("auto_eat_threshold", d.autoEatThreshold);
    out.addProperty("auto_eat_target", d.autoEatTarget);
    out.addProperty("navigation_detour_only", Store.config.navigationDetourOnly);
    out.addProperty("navigation_break", Store.config.navigationBreak);
    out.addProperty("navigation_place", Store.config.navigationPlace);
    out.addProperty("discovered_containers", discoveredContainers.size());
    var gaps = new JsonArray();
    for (String g :
        d.missingPermissions(
            Store.config.fullGameControl || "FULL".equals(Store.config.permission),
            Store.config.permission,
            Store.config.allowMine,
            Store.config.allowPlace,
            Store.config.allowHunt,
            Store.config.allowContainers,
            Store.config.navigationDetourOnly,
            Store.config.navigationBreak))
      gaps.add(g);
    out.add("missing_permissions", gaps);
    return out;
  }

  public static JsonObject setFromTool(JsonObject a) {
    var d = duty();
    boolean wasManualLock = d.autonomyLevelEnum() == DutyAutonomyPolicy.Level.MANUAL_LOCK;
    boolean blockedByManualLock = false;
    if (a.has("autonomy_level"))
      d.autonomyLevel =
          DutyAutonomyPolicy.Level.parse(Agent.str(a, "autonomy_level", d.autonomyLevel)).name();
    if (a.has("base_radius")) d.baseRadius = Rules.bounded(a.get("base_radius").getAsInt(), 8, 96);
    if (a.has("follow_range")) d.followRange = Agent.str(a, "follow_range", d.followRange);
    if (a.has("follow_player") || a.has("follow_uuid")) {
      String fUuid = Agent.str(a, "follow_uuid", "");
      String fName = Agent.str(a, "follow_player", "");
      if (!fUuid.isBlank() || !fName.isBlank()) {
        var fArgs = new JsonObject();
        if (!fUuid.isBlank()) fArgs.addProperty("target_uuid", fUuid);
        if (!fName.isBlank()) fArgs.addProperty("player_name", fName);
        FollowControl.start(fArgs, true);
      }
    }
    if (a.has("guard_owner")) d.guardOwner = a.get("guard_owner").getAsBoolean();
    if (a.has("avoid_terrain_destroying_mobs"))
      d.avoidTerrainDestroyingMobs = a.get("avoid_terrain_destroying_mobs").getAsBoolean();
    if (a.has("scavenge_wild_containers"))
      d.scavengeWildContainers = a.get("scavenge_wild_containers").getAsBoolean();
    if (a.has("prefer_lootr_containers"))
      d.preferLootrContainers = a.get("prefer_lootr_containers").getAsBoolean();
    if (a.has("auto_eat")) d.autoEat = a.get("auto_eat").getAsBoolean();
    if (a.has("standby_auto_eat")) d.standbyAutoEat = a.get("standby_auto_eat").getAsBoolean();
    if (a.has("auto_eat_threshold")) d.autoEatThreshold = a.get("auto_eat_threshold").getAsInt();
    if (a.has("auto_eat_target")) d.autoEatTarget = a.get("auto_eat_target").getAsInt();
    if (a.has("navigation_detour_only")) {
      Store.config.navigationDetourOnly = a.get("navigation_detour_only").getAsBoolean();
      Store.saveSettingsOnly();
    }
    if (a.has("navigation_break")) {
      Store.config.navigationBreak = a.get("navigation_break").getAsBoolean();
      Store.saveSettingsOnly();
    }
    if (a.has("set_home_here") && a.get("set_home_here").getAsBoolean()) setHomeHere(false);
    boolean wantArm = a.has("armed") ? a.get("armed").getAsBoolean() : armed;
    if (a.has("mode")) {
      CompanionDuty.Mode requested =
          CompanionDuty.Mode.parse(Agent.str(a, "mode", d.mode));
      if (wasManualLock
          && !a.has("autonomy_level")
          && requested != CompanionDuty.Mode.STANDBY
          && requested != d.modeEnum()) {
        blockedByManualLock = true;
        d.normalize();
        Store.save();
      } else {
        yieldCurrentJob("工具切换值守模式");
        setMode(
            requested,
            a.has("armed") ? wantArm : true,
            "工具切换值守模式",
            false);
      }
    } else {
      d.normalize();
      Store.save();
      if (a.has("armed")) {
        yieldCurrentJob("工具切换值守模式");
        arm(wantArm, "工具更新值守启停");
      }
    }
    var out = status();
    if (blockedByManualLock) {
      out.addProperty("blocked_by_manual_lock", true);
      out.addProperty(
          "message", "当前为人工锁定模式（MANUAL_LOCK），拒绝自主切换值守模式；请先解锁或由玩家手动切换。");
    }
    return out;
  }

  static void handleChatCommand(ChatCommand.Duty cmd, Consumer<String> reply) {
    switch (cmd.action()) {
      case STANDBY -> {
        setMode(CompanionDuty.Mode.STANDBY, false, "本地口令切回纯待命");
        reply.accept("已切换为：纯待命 · 听候差遣");
      }
      case BASE_STEWARD -> {
        setMode(CompanionDuty.Mode.BASE_STEWARD, true, "本地口令启动驻守基地");
        reply.accept("已切换为：驻守基地 · 自主值守（围栏 " + duty().baseRadius + " 格）");
      }
      case ESCORT_GUARD -> {
        setMode(CompanionDuty.Mode.ESCORT_GUARD, true, "本地口令启动跟随与守护");
        reply.accept("已切换为：跟随陪同 · 守护玩家");
      }
      case EXPEDITION_GATHER -> {
        setMode(CompanionDuty.Mode.EXPEDITION_GATHER, true, "本地口令启动外出探索采集");
        reply.accept("已切换为：外出探索 · 搜刮采集");
      }
      case GUIDE_HOME -> {
        yieldCurrentJob("本地口令触发带玩家回家");
        var args = new JsonObject();
        args.addProperty("target", cmd.argument().isBlank() ? "nearest_safe" : cmd.argument());
        args.addProperty("guide_player", true);
        args.addProperty("max_seconds", 240);
        try {
          ExplorationJob.start("explore_return", args, reply);
          reply.accept("好的，我沿 3D 探索记录带你一起返回 " + args.get("target").getAsString() + "，跟紧我~");
        } catch (IllegalArgumentException ex) {
          reply.accept("暂时无法领路回家：" + ex.getMessage());
        }
      }
      case PAUSE -> {
        arm(false, "本地口令暂停值守");
        reply.accept("值守已暂停，保留当前方案（" + duty().modeEnum().label + "）");
      }
      case RESUME -> {
        setMode(duty().modeEnum(), true, "本地口令恢复值守");
        reply.accept("值守已恢复：" + duty().modeEnum().label);
      }
      case STATUS ->
          reply.accept(
              "值守：" + duty().modeEnum().label + " · " + (armed ? runtimeState : "未启动") + " · " + detail);
    }
  }
}
