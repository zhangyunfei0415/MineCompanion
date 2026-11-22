package dev.mibao.companion;

import java.util.*;

/**
 * Pure persisted & runtime configuration for Maid-style companion duty modes (`跟随与陪同`).
 * Zero {@code net.minecraft.*} imports so offline JUnit validation exercises every rule directly.
 */
public final class CompanionDuty {
  private static final Set<String> ESSENTIAL_UTILITY_ITEMS =
      Set.of(
          "minecraft:torch",
          "minecraft:soul_torch",
          "minecraft:lantern",
          "minecraft:arrow",
          "minecraft:spectral_arrow",
          "minecraft:tipped_arrow",
          "minecraft:bow",
          "minecraft:crossbow",
          "minecraft:shield",
          "minecraft:water_bucket",
          "minecraft:bucket",
          "minecraft:ender_pearl",
          "minecraft:totem_of_undying",
          "minecraft:clock",
          "minecraft:compass",
          "minecraft:crafting_table",
          "minecraft:furnace");

  public static boolean isSafeAutoDepositCandidate(
      int slotIndex,
      String itemId,
      boolean damageable,
      boolean edible,
      boolean equipment,
      boolean protectedOrMandatory,
      int count,
      int reserve) {
    if (slotIndex < 9 || slotIndex >= 36 || itemId == null || itemId.isBlank()) return false;
    if (damageable || edible || equipment || protectedOrMandatory) return false;
    if (ESSENTIAL_UTILITY_ITEMS.contains(itemId)) return false;
    return count > Math.max(0, reserve);
  }

  public enum Mode {
    STANDBY("纯待命", "听候差遣，仅低血自救与等待派单"),
    BASE_STEWARD("驻守基地", "在基地围栏内捡掉落物、收作物、补火把与休整"),
    ESCORT_GUARD("跟随与守护", "紧随玩家、守护并反击近身威胁、顺手捡物与开路"),
    EXPEDITION_GATHER("外出探索采集", "自动适配自然采矿与后室搜刮探路，满包安全回城");

    public final String label, summary;

    Mode(String label, String summary) {
      this.label = label;
      this.summary = summary;
    }

    public static Mode parse(String value) {
      if (value == null || value.isBlank()) return STANDBY;
      String key = value.strip().toUpperCase(Locale.ROOT);
      return switch (key) {
        case "BASE", "BASE_STEWARD", "STEWARD", "驻守", "驻守基地" -> BASE_STEWARD;
        case "ESCORT", "GUARD", "ESCORT_GUARD", "FOLLOW", "跟随", "守护", "跟随与守护" -> ESCORT_GUARD;
        case "EXPEDITION", "GATHER", "EXPEDITION_GATHER", "EXPLORE", "外出", "外出探索", "外出采集" ->
            EXPEDITION_GATHER;
        default -> STANDBY;
      };
    }
  }

  public enum FollowRange {
    CLOSE("紧贴 3~5格"),
    NORMAL("标准 4~7格"),
    WIDE("宽距 8~12格");

    public final String label;

    FollowRange(String label) {
      this.label = label;
    }

    public static FollowRange parse(String value) {
      if (value == null || value.isBlank()) return NORMAL;
      return switch (value.strip().toUpperCase(Locale.ROOT)) {
        case "CLOSE", "紧贴" -> CLOSE;
        case "WIDE", "宽距" -> WIDE;
        default -> NORMAL;
      };
    }
  }

  public enum ExpeditionFocus {
    AUTO("自动适配环境"),
    ORE_MINE("矿脉采掘"),
    WOOD_GATHER("伐木采集"),
    SCAVENGE_MAP("探索与搜刮");

    public final String label;

    ExpeditionFocus(String label) {
      this.label = label;
    }

    public static ExpeditionFocus parse(String value) {
      if (value == null || value.isBlank()) return AUTO;
      return switch (value.strip().toUpperCase(Locale.ROOT)) {
        case "ORE", "ORE_MINE", "MINE" -> ORE_MINE;
        case "WOOD", "WOOD_GATHER" -> WOOD_GATHER;
        case "SCAVENGE", "SCAVENGE_MAP", "MAP", "EXPLORE" -> SCAVENGE_MAP;
        default -> AUTO;
      };
    }
  }

  public enum GuardAction {
    ENGAGE_MELEE,
    ENGAGE_RANGED_SAFE,
    ALERT_AND_AVOID,
    IGNORE
  }

  public enum ContainerClass {
    BASE_PROTECTED,
    LOOTR_INDEPENDENT,
    WILD_CONTAINER,
    NONE
  }

  public String mode = Mode.STANDBY.name();

  // Base steward tether & tasks
  public boolean hasHome = false;
  public String homeDimension = "", homePlaceName = "";
  public int homeX, homeY, homeZ;
  public int baseRadius = 32;
  public boolean basePickup = true,
      baseFarm = true,
      baseLighting = true,
      baseRestock = true,
      baseReturnIdle = true;

  // Escort & guard behavior
  public String followRange = FollowRange.NORMAL.name();
  public boolean guardOwner = true;
  /** Default true: avoid melee rush on creepers/ghasts; toggle off when server enables explosion protection. */
  public boolean avoidTerrainDestroyingMobs = true;
  public boolean guardRangedSafe = false;
  public int guardRadius = 7, guardLeash = 14;
  public boolean escortPickup = true, escortTorch = false;

  // Outbound expedition, navigation & Lootr/container scavenging
  public String expeditionFocus = ExpeditionFocus.AUTO.name();
  /** Default false: scan and record containers to memory without taking items unless enabled. */
  public boolean scavengeWildContainers = false;
  /** Recognize per-player lootr:* chests separately from normal player storage chests. */
  public boolean preferLootrContainers = true;
  public int expeditionMinutes = 10, minFreeSlots = 3, minToolDurability = 16;

  // General safety & cost control
  public String autonomyLevel = DutyAutonomyPolicy.Level.AUTO_FULL.name();
  public boolean autoEat = true;
  public boolean allowAiEscalation = false;

  public void normalize() {
    mode = modeEnum().name();
    followRange = followRangeEnum().name();
    expeditionFocus = expeditionFocusEnum().name();
    autonomyLevel = autonomyLevelEnum().name();
    if (homeDimension == null) homeDimension = "";
    if (homePlaceName == null) homePlaceName = "";
    baseRadius = Rules.bounded(baseRadius, 8, 96);
    guardRadius = Rules.bounded(guardRadius, 4, 16);
    guardLeash = Rules.bounded(guardLeash, 8, 32);
    expeditionMinutes = Rules.bounded(expeditionMinutes, 2, 60);
    minFreeSlots = Rules.bounded(minFreeSlots, 1, 12);
    minToolDurability = Rules.bounded(minToolDurability, 4, 256);
  }

  public Mode modeEnum() {
    return Mode.parse(mode);
  }

  public FollowRange followRangeEnum() {
    return FollowRange.parse(followRange);
  }

  public ExpeditionFocus expeditionFocusEnum() {
    return ExpeditionFocus.parse(expeditionFocus);
  }

  public DutyAutonomyPolicy.Level autonomyLevelEnum() {
    return DutyAutonomyPolicy.Level.parse(autonomyLevel);
  }

  public static boolean isDropCooldownActive(Map<UUID, Long> blocked, UUID id, long nowMs) {
    if (blocked == null || id == null) return false;
    Long until = blocked.get(id);
    if (until == null) return false;
    if (nowMs >= until) {
      blocked.remove(id);
      return false;
    }
    return true;
  }

  public static void pruneExpiredDrops(Map<UUID, Long> blocked, long nowMs) {
    if (blocked == null || blocked.isEmpty()) return;
    blocked.entrySet().removeIf(e -> e.getValue() == null || nowMs >= e.getValue());
  }

  public boolean insideBaseTether(String dimension, int x, int y, int z) {
    if (!hasHome || dimension == null || !homeDimension.equals(dimension)) return false;
    long dx = (long) x - homeX;
    long dz = (long) z - homeZ;
    long dy = (long) y - homeY;
    return dx * dx + dz * dz <= (long) baseRadius * baseRadius && Math.abs(dy) <= Math.max(16, baseRadius);
  }

  public boolean withinGuardLeash(double distanceToOwner) {
    return distanceToOwner <= guardLeash;
  }

  public GuardAction evaluateGuardTarget(
      String entityId,
      boolean isPlayer,
      boolean isTamed,
      boolean isVillagerOrGolem,
      boolean hasCustomName,
      boolean isBoss,
      boolean attackPlayers,
      boolean attackNamed,
      boolean hasRangedWeapon) {
    if (isTamed || isVillagerOrGolem || isBoss) return GuardAction.IGNORE;
    if (isPlayer) return attackPlayers ? GuardAction.ENGAGE_MELEE : GuardAction.IGNORE;
    if (hasCustomName && !attackNamed) return GuardAction.IGNORE;
    if (VolatileTagPolicy.isTerrainDestroyingMob(entityId)) {
      if (avoidTerrainDestroyingMobs) {
        return guardRangedSafe && hasRangedWeapon && "minecraft:creeper".equals(entityId)
            ? GuardAction.ENGAGE_RANGED_SAFE
            : GuardAction.ALERT_AND_AVOID;
      }
      return "minecraft:creeper".equals(entityId)
          ? GuardAction.ENGAGE_MELEE
          : GuardAction.ALERT_AND_AVOID;
    }
    if (CombatPolicy.HUNTABLE.contains(entityId)) return GuardAction.ENGAGE_MELEE;
    return GuardAction.IGNORE;
  }

  public ContainerClass classifyContainer(String blockId, boolean isRegisteredBaseContainer) {
    if (blockId == null || blockId.isBlank()) return ContainerClass.NONE;
    if (preferLootrContainers && VolatileTagPolicy.isLootrContainer(blockId))
      return ContainerClass.LOOTR_INDEPENDENT;
    if (isRegisteredBaseContainer) return ContainerClass.BASE_PROTECTED;
    if (VolatileTagPolicy.isWildLootableContainer(blockId)) return ContainerClass.WILD_CONTAINER;
    return ContainerClass.NONE;
  }

  public boolean canLootContainer(ContainerClass kind) {
    if (kind == null || kind == ContainerClass.NONE || kind == ContainerClass.BASE_PROTECTED)
      return false;
    return scavengeWildContainers;
  }

  public List<String> missingPermissions(
      boolean fullAccess,
      String permissionMode,
      boolean allowMine,
      boolean allowPlace,
      boolean allowHunt,
      boolean allowContainers,
      boolean navigationDetourOnly,
      boolean navigationBreak) {
    List<String> gaps = new ArrayList<>();
    Mode active = modeEnum();
    if (active == Mode.STANDBY) return gaps;
    if (!fullAccess && "ASK".equals(permissionMode)) {
      gaps.add("当前权限为「逐次审批(ASK)」：建议在设置页切为「有限自主(LIMITED)」或「完全放权(FULL)」，以免值守动作被拦截");
    }
    boolean canHunt = fullAccess || ("LIMITED".equals(permissionMode) && allowHunt);
    boolean canMine = fullAccess || ("LIMITED".equals(permissionMode) && allowMine);
    boolean canPlace = fullAccess || ("LIMITED".equals(permissionMode) && allowPlace);
    boolean canContainer = fullAccess || ("LIMITED".equals(permissionMode) && allowContainers);
    switch (active) {
      case BASE_STEWARD -> {
        if (!hasHome) gaps.add("尚未设定基地锚点：请点击「以当前位置设为基地中心」开启围栏巡逻");
        if (baseFarm && (!canMine || !canPlace))
          gaps.add("照料作物需要「允许采集」与「允许放置」，当前仅捡物休整");
        if (baseLighting && !canPlace) gaps.add("基地补火把需要「允许放置」开关，当前自动照明已跳过");
      }
      case ESCORT_GUARD -> {
        if (guardOwner && !canHunt)
          gaps.add("未开启「允许清怪」：守护模式已降级为只跟随 + 危险预警 + 拾取");
        if (escortTorch && !canPlace) gaps.add("随行插火把需要「允许放置」开关");
      }
      case EXPEDITION_GATHER -> {
        if (navigationDetourOnly && (expeditionFocusEnum() == ExpeditionFocus.ORE_MINE || expeditionFocusEnum() == ExpeditionFocus.WOOD_GATHER))
          gaps.add("当前开启了「导航仅绕行」：外出挖障/开路受限，将以探路与表面搜刮为主");
        else if (!navigationDetourOnly && !navigationBreak && (expeditionFocusEnum() == ExpeditionFocus.ORE_MINE || expeditionFocusEnum() == ExpeditionFocus.WOOD_GATHER))
          gaps.add("如需外出采矿或伐木破障，请打开「允许导航与外出破坏」与「允许采集」");
        if (scavengeWildContainers && !canContainer)
          gaps.add("已启用野生容器搜刮，但缺少「允许容器交互」权限，暂只扫描登记清单");
      }
      default -> {}
    }
    return gaps;
  }
}
