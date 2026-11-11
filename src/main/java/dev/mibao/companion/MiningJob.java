package dev.mibao.companion;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** Client-player surface mining. Never opens tunnels, changes inventory, or forces chunks. */
public final class MiningJob extends Actions.Job {
  public record MiningReceipt(
      long epoch,
      List<BlockPos> positions,
      int gained,
      boolean capacityBlocked,
      boolean toolBlocked) {}

  public static MiningReceipt lastReceipt;
  private final List<BlockPos> broken = new ArrayList<>();
  private boolean requireAxe, naturalTree;
  private boolean groundDrops;

  public MiningJob groundDrops(boolean value) {
    groundDrops = value;
    return this;
  }

  public MiningJob requireAxe(boolean value) {
    requireAxe = value;
    if (value && Agent.resource != null) treeColumns = Agent.resource.treeColumns();
    return this;
  }

  public MiningJob naturalTree(boolean value) {
    naturalTree = value;
    return this;
  }

  private String modifier = "";
  private long modifierGeneration = -1;
  private int modifierTicks;

  public MiningJob withKey(String name) {
    if (!name.isBlank()) {
      var key = PlayerControls.find(name);
      String reason = PlayerControls.blocked(key, PlayerControls.effectiveModifier(key));
      if (!reason.isBlank() || !PlayerControls.keyAccess(name, true).equals("allowed"))
        throw new IllegalArgumentException("[MODIFIER_KEY] " + reason + "；检查按键权限");
      modifier = name;
    }
    return this;
  }

  private final Navigator nav = new Navigator();
  private final AimGate aim = new AimGate();
  private final Set<UUID> oldDrops = new HashSet<>();
  private InventoryMove prepare;
  private final WorkPlan plan;
  private final BlockPos exactTarget;
  private final boolean confirm, creative;
  private String lastDenial = "";
  private final Map<String, Integer> denials = new TreeMap<>();
  private final BlockPos center;
  private final String filter;
  private final int radius, side, volume;
  private final TagKey<Block> tag;
  private final Block block;
  private final List<BlockPos> candidates = new ArrayList<>();
  private final Set<BlockPos> rejectedTargets = new HashSet<>();
  private final Map<String, Integer> before;
  private Map<Long, Boolean> treeColumns = new HashMap<>();

  private boolean naturalColumn(BlockPos pos) {
    long key = BlockPos.asLong(pos.getX(), 0, pos.getZ());
    return treeColumns.computeIfAbsent(
        key,
        k -> {
          BlockPos root = pos;
          for (int i = 0;
              i < 16
                  && mc().level.hasChunkAt(root.below())
                  && mc().level.getBlockState(root.below()).is(net.minecraft.tags.BlockTags.LOGS);
              i++) root = root.below();
          if (!mc().level.hasChunkAt(root.below())
              || !mc().level.getBlockState(root.below()).is(net.minecraft.tags.BlockTags.DIRT))
            return false;
          int leaves = 0;
          for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++)
              for (int y = 1; y <= 8; y++) {
                BlockPos p = root.offset(x, y, z);
                if (mc().level.hasChunkAt(p)
                    && mc().level.getBlockState(p).is(net.minecraft.tags.BlockTags.LEAVES)
                    && ++leaves >= 3) return true;
              }
          return false;
        });
  }

  private BlockPos target;
  private BlockState original;
  private int cursor, phase, delay, skipped, unknown, observed, rejectedTrees, rejectedSafety;
  private int rescans, lastRescanObserved;
  private boolean capacityBlocked, toolBlocked;
  private boolean issued, scanned;
  private boolean fallingTarget;
  private boolean neighborFalling;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public MiningJob(String filter, int count, int radius, Consumer<String> done) {
    this(filter, count, radius, null, false, 0, done);
  }

  public MiningJob(
      String filter,
      int count,
      int radius,
      BlockPos exactTarget,
      boolean confirm,
      Consumer<String> done) {
    this(filter, count, radius, exactTarget, confirm, exactTarget == null ? 0 : 3, done);
  }

  public MiningJob(
      String filter,
      int count,
      int radius,
      BlockPos exactTarget,
      boolean confirm,
      int stanceRadius,
      Consumer<String> done) {
    super(done);
    if (stanceRadius > 0) nav.exactStance(true).maxRadius(stanceRadius);
    this.exactTarget = exactTarget;
    this.confirm = confirm;
    creative = mc().gameMode.getPlayerMode() == GameType.CREATIVE;
    lastReceipt = null;
    this.filter = filter;
    this.radius = radius;
    plan = new WorkPlan(new WorkPlan.Step(WorkPlan.ScanType.BLOCK, filter, count, radius));
    if (mc().gameMode.getPlayerMode() != GameType.SURVIVAL && !(exactTarget != null && creative))
      throw new IllegalArgumentException("[MODE_REQUIRED] 资源采集仅支持生存；定点拆除可用生存或创造");
    if (mc().player.containerMenu != mc().player.inventoryMenu)
      throw new IllegalArgumentException("请先关闭容器");
    var key = ResourceLocation.tryParse(filter.startsWith("#") ? filter.substring(1) : filter);
    if (key == null) throw new IllegalArgumentException("需要完整方块ID或#方块标签");
    tag = filter.startsWith("#") ? TagKey.create(Registries.BLOCK, key) : null;
    block = tag == null ? BuiltInRegistries.BLOCK.getOptional(key).orElse(null) : null;
    if (tag == null && (block == null || block.defaultBlockState().isAir()))
      throw new IllegalArgumentException("未知方块或空气");
    center = mc().player.blockPosition();
    side = radius * 2 + 1;
    volume = exactTarget == null ? side * side * side : 1;
    if (exactTarget != null)
      for (var e :
          mc().level
              .getEntitiesOfClass(
                  net.minecraft.world.entity.item.ItemEntity.class,
                  new net.minecraft.world.phys.AABB(exactTarget).inflate(5)))
        oldDrops.add(e.getUUID());
    PlayerEvidence.begin();
    before = PlayerEvidence.actual("*");
  }

  private boolean matches(BlockState state) {
    return tag == null ? state.is(block) : state.is(tag);
  }

  private String breakDenial(BlockPos pos, BlockState state) {
    return BreakPolicy.denial(
        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
        state.hasBlockEntity(),
        exactTarget != null,
        confirm,
        state.hasBlockEntity() && exactTarget != null && freshEmpty(pos));
  }

  public static boolean freshEmpty(BlockPos pos) {
    var mc = Minecraft.getInstance();
    var point =
        Store.data.world.get(
            WorldMemory.key(
                mc.level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ()));
    if (point == null
        || !point.present
        || point.inventory == null
        || !point.block.equals(
            BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString()))
      return false;
    var inventory = point.inventory;
    return inventory.source.equals("server_synced_opened_client_menu")
        && inventory.syncRevision > 0
        && inventory.syncedAt > 0
        && !inventory.truncated
        && inventory.slotCount > 0
        && System.currentTimeMillis() - inventory.observed < 30_000
        && inventory.items.stream().noneMatch(i -> i.count > 0);
  }

  private boolean safe(BlockPos pos, BlockState state) {
    if (naturalTree && (!state.is(net.minecraft.tags.BlockTags.LOGS) || !naturalColumn(pos)))
      return false;
    lastDenial = breakDenial(pos, state);
    if (!lastDenial.isBlank()) return false;
    if (!mc().level.hasChunkAt(pos)) {
      lastDenial = "[UNLOADED_TARGET] 目标未加载";
      return false;
    }
    if (state.getDestroySpeed(mc().level, pos) < 0) {
      lastDenial = "[BLOCK_PROTECTED] 目标不可破坏";
      return false;
    }
    if (!state.getFluidState().isEmpty()) {
      lastDenial = "[FLUID_TARGET] 不拆带流体目标以免放水";
      return false;
    }
    var feet = mc().player.blockPosition();
    if (pos.equals(feet.below())
        || (pos.equals(feet) || pos.equals(feet.above()))
            && !state.getCollisionShape(mc().level, pos).isEmpty()) {
      lastDenial = "[PLAYER_SUPPORT] 不拆当前脚下支撑/玩家碰撞格";
      return false;
    }
    boolean exposed = false;
    neighborFalling = false;
    for (var d : Direction.values()) {
      BlockPos n = pos.relative(d);
      if (!mc().level.hasChunkAt(n)) {
        lastDenial = "[UNLOADED_NEIGHBOR] 周围区块未知";
        return false;
      }
      var s = mc().level.getBlockState(n);
      if (!s.getFluidState().isEmpty() || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)) {
        lastDenial = "[NEIGHBOR_HAZARD] 邻格有流体或火";
        return false;
      }
      if (s.getBlock() instanceof FallingBlock) neighborFalling = true;
      if (s.isAir()) exposed = true;
    }
    fallingTarget = state.getBlock() instanceof FallingBlock;
    if (!exposed) {
      lastDenial =
          fallingTarget
              ? "[FALLING_TARGET] 落块目标未暴露：先清出可见的支撑面再拆"
              : "[TARGET_NOT_EXPOSED] 目标未暴露：先清出相邻方块再拆";
      return false;
    }
    return true;
  }

  private void result(String reason) {

    int gained =
        PlayerEvidence.revision == 0
            ? 0
            : DeltaProof.gained(before, PlayerEvidence.actual("*"), PlayerEvidence.confirmed("*"));
    lastReceipt =
        new MiningReceipt(epoch, List.copyOf(broken), gained, capacityBlocked, toolBlocked);
    String result =
        (plan.isDone() ? "成功：" : "部分完成：")
            + (plan.isDone() ? "" : capacityBlocked ? "[NO_CAPACITY] " : toolBlocked ? "[TOOL_UNAVAILABLE] " : "[PARTIAL_RESULT] ")
            + "服务器确认挖除 "
            + observed
            + " / "
            + plan.steps.get(0).count()
            + " 个 "
            + filter
            + " 方块。"
            + reason
            + " 背包已确认新增 "
            + gained
            + " 件（所有变体合计，不等同矿物产量）；后续拾取阶段另行核对。"
            + delayedRead();
    if (exactTarget != null && plan.isDone() && creative) {
      finish("成功：创造模式已取得服务器方块挖除确认，不要求掉落物或工具消耗；位置 " + exactTarget.toShortString());
      return;
    }
    if (exactTarget != null && plan.isDone()) {
      if (groundDrops && !roomFor(original)) {
        finish("成功：服务器确认定点挖除；[DROPS_LEFT_ON_GROUND] 背包无容量，产物留地待回收；位置 "
            + exactTarget.toShortString() + "；" + result);
        return;
      }
      stop();
      Actions.job =
          new CollectionJob(
              "*",
              Integer.MAX_VALUE,
              6,
              exactTarget,
              e ->
                  !oldDrops.contains(e.getUUID())
                      && PickupGeometry.nearBroken(
                          exactTarget.getX() + .5,
                          exactTarget.getY(),
                          exactTarget.getZ() + .5,
                          e.getX(),
                          e.getY(),
                          e.getZ()),
              true,
              r ->
                  done.accept(
                      (r.startsWith("成功") ? "成功：" : "部分完成：")
                          + "定点拆除已获服务器方块确认；拾取阶段："
                          + r
                          + "；原挖除："
                          + result));
      return;
    }
    finish(result);
  }

  /** Delayed readout for unconfirmed/success receipts: target state and the current hand. */
  private String delayedRead() {
    String block = "未知";
    if (target != null && mc().level.hasChunkAt(target))
      block =
          BuiltInRegistries.BLOCK.getKey(mc().level.getBlockState(target).getBlock()).toString();
    var held = mc().player.getMainHandItem();
    String hand =
        held.isEmpty()
            ? "空手"
            : BuiltInRegistries.ITEM.getKey(held.getItem()).toString() + "×" + held.getCount();
    return "；延迟读数：目标格="
        + block
        + "，手持="
        + hand
        + (fallingTarget ? "，落块目标已放行（脚下支撑/流体/火/未加载检查仍生效）" : "")
        + (neighborFalling ? "，邻格落块仅提示（未拦截）" : "");
  }

  @Override
  void tick() {
    if (prepare != null) {
      Actions.status = "采矿准备 · 从主背包取工具并核验";
      int r = prepare.tick();
      if (r < 0) {
        result(prepare.error);
        return;
      }
      if (r > 0) {
        mc().player.getInventory().selected = prepare.readySlot;
        prepare = null;
      }
      return;
    }
    if (++ticks > 6000) {
      result("达到5分钟预算。");
      return;
    }
    if (mc().screen != null) {
      nav.stop();
      result("其他界面已打开，停止采矿。");
      return;
    }
    if (SurvivalPolicy.blocked(
            mc().player.getHealth(),
            SurvivalPolicy.work(mc().player.getMaxHealth()),
            Store.config.testIgnoreLowHealth)
        || mc().player.getFoodData().getFoodLevel() < 6) {
      result("生命或饱食不足，请补给。");
      return;
    }
    if (mc().player.blockPosition().distSqr(center) > (radius + 4.0) * (radius + 4.0)) {
      result("已偏离固定搜索区域。");
      return;
    }
    if (!ClientEvidence.attached()) {
      result("没有服务器同步证据接收器。");
      return;
    }
    Actions.status =
        "采矿 · 已核实 "
            + observed
            + " / "
            + plan.steps.get(0).count()
            + (scanned ? "" : " · 分帧扫描 " + cursor + "/" + volume);
    if (plan.isDone()) {
      result("目标数量已满足。");
      return;
    }
    if (!scanned) {
      long until = System.nanoTime() + 2_000_000;
      int budget = 256;
      while (cursor < volume && budget-- > 0 && System.nanoTime() < until) {
        int n = cursor++;
        BlockPos p =
            exactTarget != null
                ? exactTarget
                : center.offset(
                    n % side - radius, n / (side * side) - radius, (n / side) % side - radius);
        if (p.distSqr(center) > (double) radius * radius
            || p.getY() < mc().level.getMinBuildHeight()
            || p.getY() >= mc().level.getMaxBuildHeight()) continue;
        if (!mc().level.hasChunkAt(p)) {
          unknown++;
          continue;
        }
        var state = mc().level.getBlockState(p);
        if (matches(state) && !rejectedTargets.contains(p)) {
          if (naturalTree && (!state.is(net.minecraft.tags.BlockTags.LOGS) || !naturalColumn(p)))
            rejectedTrees++;
          else if (!safe(p, state)) {
            rejectedSafety++;
            if (!lastDenial.isBlank()) denials.merge(lastDenial, 1, Integer::sum);
          } else if (candidates.size() < 512) {
            candidates.add(p);
            WorldSense.recordResource(p);
          }
        }
      }
      if (cursor < volume) return;
      candidates.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
      scanned = true;
      return;
    }
    if (target == null) {
      while (!candidates.isEmpty()) {
        BlockPos p = candidates.remove(0);
        if (!mc().level.hasChunkAt(p)) continue;
        var state = mc().level.getBlockState(p);
        if (matches(state) && safe(p, state)) {
          target = p;
          original = state;
          break;
        }
      }
      if (target == null) {
        if (observed > lastRescanObserved && rescans < 2) {
          lastRescanObserved = observed;
          rescans++;
          cursor = 0;
          scanned = false;
          rejectedTrees = rejectedSafety = unknown = 0;
          return;
        }
        result(
            "没有更多安全可接近候选；"
                + (naturalTree ? "不符合完整自然树条件 " + rejectedTrees + " 个原木采样（可能为残留树冠、建筑或根部未加载）；" : "")
                + (denials.isEmpty() ? "" : denials.toString() + "；")
                + "安全条件排除 "
                + rejectedSafety
                + "，不可达/变化 "
                + skipped
                + "；局部重扫 "
                + rescans
                + " 次；未加载采样 "
                + unknown
                + " 处仍未知。");
        return;
      }
      if (!creative && !roomFor(original) && !groundDrops) {
        capacityBlocked = true;
        result("背包不能容纳该方块的已知普通产物，或未知掉落需要空位。");
        return;
      }
      phase = 0;
      delay = 0;
      issued = false;
      PlayerEvidence.forget(target);
      if (!nav.startForInteraction(target)) {
        skip();
        return;
      }
      return;
    }
    if (!mc().level.hasChunkAt(target)) {
      result("目标区块卸载。");
      return;
    }
    var state = mc().level.getBlockState(target);
    var receipt = PlayerEvidence.block(target);
    if (MiningProof.broken(issued, receipt != null && receipt.isAir(), state.isAir())) {
      if (plan.verify(target.toShortString())) {
        observed++;
        broken.add(target.immutable());
      }
      WorldSense.record(target, state);
      nav.stop();
      mc().gameMode.stopDestroyBlock();
      target = null;
      return;
    }
    if (state.isAir()) {
      Navigator.release();
      if (!issued) {
        skip();
        return;
      }
      if (++delay > 100) result("客户端显示空气但未收到服务器确认；该方块不计数。");
      return;
    }
    if (state != original) {
      result("目标状态被替换；停止，避免挖错方块。");
      return;
    }
    if (!safe(target, state)) {
      result("目标附近出现流体、落块或不安全站位。");
      return;
    }
    if (phase == 0) {
      int r = nav.tick();
      if (r < 0) {
        skip();
        return;
      }
      if (r == 0) return;
      phase = 1;
      delay = 0;
    }
    if (!issued && !creative) {
      int selected = -1;
      float speed = 0;
      for (int i = 0; i < 36; i++) {
        ItemStack tool = mc().player.getInventory().getItem(i);
        if (requireAxe && !(tool.getItem() instanceof AxeItem)) continue;
        if (tool.isDamageableItem() && tool.getMaxDamage() - tool.getDamageValue() <= 1) continue;
        if (state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(state)) continue;
        float s = tool.getDestroySpeed(state);
        if (s > speed) {
          speed = s;
          selected = i;
        }
      }
      if (selected < 0) {
        toolBlocked = true;
        result("随身背包缺少可正确采集且耐久足够的工具。");
        return;
      }
      if (selected >= 9) {
        String fp = WorldSense.fingerprint(mc().player.getInventory().getItem(selected));
        prepare = InventoryMove.ensure(s -> !s.isEmpty() && WorldSense.fingerprint(s).equals(fp));
        nav.stop();
        return;
      }
      mc().player.getInventory().selected = selected;
    }
    var tool = mc().player.getMainHandItem();
    if (!creative && tool.isDamageableItem() && tool.getMaxDamage() - tool.getDamageValue() <= 1) {
      toolBlocked = true;
      result("工具即将损坏。");
      return;
    }
    if (++delay > 300) {
      result("单个方块15秒内未获确认，请检查领地权限、工具或延迟。");
      return;
    }
    var hit = Reach.hit(target);
    if (hit == null) {
      result("目标被遮挡或超出交互距离。");
      return;
    }
    Actions.look(hit.getLocation());
    if (!issued && !aim.ready(hit.getLocation())) return;
    if (!modifier.isBlank() && modifierGeneration != PlayerControls.generation) {
      PlayerControls.pressMod(modifier, "");
      modifierGeneration = PlayerControls.generation;
      modifierTicks = 0;
      return;
    }
    if (!modifier.isBlank() && ++modifierTicks > 200) {
      result("修饰键单次保持已达200tick，停止并释放；不重发挖掘");
      return;
    }
    if (!issued) {
      mc().gameMode.startDestroyBlock(target, hit.getDirection());
      issued = true;
    } else mc().gameMode.continueDestroyBlock(target, hit.getDirection());
    mc().player.swing(InteractionHand.MAIN_HAND);
  }

  private boolean roomFor(BlockState state) {
    if (mc().player.getInventory().getFreeSlot() >= 0) return true;
    String drop =
        GatheringCargo.commonDrop(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    if (drop.isEmpty()) return false;
    var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(drop)).orElse(null);
    if (item == null) return false;
    var prototype = item.getDefaultInstance();
    for (int i = 0; i < 36; i++) {
      var stack = mc().player.getInventory().getItem(i);
      if (ItemStack.isSameItemSameTags(stack, prototype)
          && stack.getCount() < stack.getMaxStackSize()) return true;
    }
    return false;
  }

  private void skip() {
    nav.stop();
    mc().gameMode.stopDestroyBlock();
    if (target != null) rejectedTargets.add(target);
    target = null;
    if (++skipped >= 12) result("12个候选不可达或发生变化，请协助调整通道。");
  }

  @Override
  void stop() {
    if (prepare != null) prepare.stop();
    nav.stop();
    super.stop();
  }

  @Override
  String interrupted() {
    return "采矿已停止；服务器确认挖除 " + observed + " 个；未确认目标不计数。";
  }
}
