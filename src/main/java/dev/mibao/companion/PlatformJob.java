package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/** Bounded flat-floor builder: confirmed floor -> crouched stance -> place once -> confirm. */
public final class PlatformJob extends Actions.Job {
  private static JsonObject last = new JsonObject();
  private final PlatformPlan.Area area;
  private final String item;
  private final Block block;
  private final List<PlatformPlan.Cell> cells;
  private final Set<PlatformPlan.Cell> completed = new HashSet<>();
  private final String scope = Store.scope() + "|" + dimension;
  private final int oldSlot, delay;
  private final boolean creative;
  private boolean originalSneak, started, finished;
  private int phase, placed, wait, stepTicks, before;
  private long generation;
  private InventoryMove prepare;
  private AimGate aim = new AimGate();
  private PlatformPlan.Cell pending, anchor;
  private Deque<PlatformPlan.Cell> route = new ArrayDeque<>();
  private Vec3 stance;
  private String fingerprint;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  private BlockPos pos(PlatformPlan.Cell c) {
    return new BlockPos(c.x(), area.y(), c.z());
  }

  public PlatformJob(PlatformPlan.Area area, String item, int delay, Consumer<String> done) {
    super(done);
    this.area = area;
    this.delay = delay;
    cells = area.cells();
    var resource = ResourceLocation.tryParse(item);
    if (resource == null
        || !BuiltInRegistries.ITEM.containsKey(resource)
        || !(BuiltInRegistries.ITEM.get(resource) instanceof BlockItem bi))
      throw new IllegalArgumentException("[BUILD_MATERIAL] 指定普通完整方块注册ID");
    this.item = resource.toString();
    block = ((BlockItem) BuiltInRegistries.ITEM.get(resource)).getBlock();
    if (block instanceof FallingBlock
        || block instanceof LeavesBlock
        || block instanceof EntityBlock
        || block instanceof TntBlock
        || block == Blocks.MAGMA_BLOCK
        || !Block.isShapeFullBlock(
            block.defaultBlockState().getCollisionShape(mc().level, mc().player.blockPosition())))
      throw new IllegalArgumentException("[BUILD_MATERIAL] 首版仅支持非掉落/非机器的完整实体方块，暂不铺半砖");
    creative = mc().gameMode.getPlayerMode() == GameType.CREATIVE;
    if (!creative && mc().gameMode.getPlayerMode() != GameType.SURVIVAL)
      throw new IllegalArgumentException("[MODE_REQUIRED] 需要生存或创造模式");
    oldSlot = mc().player.getInventory().selected;
    validateArea(area);
  }

  public static void validateArea(PlatformPlan.Area a) {
    var p = mc().player;
    if (a.y() < mc().level.getMinBuildHeight() || a.y() >= mc().level.getMaxBuildHeight() - 2)
      throw new IllegalArgumentException("[BUILD_BOUNDS] 地板高度超出建筑范围");
    double dx = Math.max(Math.max(a.x() - p.getX(), 0), p.getX() - (a.x() + a.width()));
    double dz = Math.max(Math.max(a.z() - p.getZ(), 0), p.getZ() - (a.z() + a.depth()));
    if (dx * dx + dz * dz > 36 || Math.abs(p.getY() - (a.y() + 1)) > .2)
      throw new IllegalArgumentException("[BUILD_START] 请站在同高度、距离施工范围6格内的实体平台上");
  }

  private boolean full(PlatformPlan.Cell c) {
    var b = pos(c);
    if (!mc().level.hasChunkAt(b)) return false;
    var state = mc().level.getBlockState(b);
    var name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    return state.getFluidState().isEmpty()
        && !name.contains("magma")
        && !name.contains("campfire")
        && !name.contains("cactus")
        && !(state.getBlock() instanceof FallingBlock)
        && !(state.getBlock() instanceof LeavesBlock)
        && Block.isShapeFullBlock(state.getCollisionShape(mc().level, b));
  }

  private boolean walkable(PlatformPlan.Cell c) {
    if (pending != null && c.equals(pending) && !completed.contains(c)) return false;
    if (c.x() < Math.min(area.x(), mc().player.getBlockX()) - 3
        || c.x() > Math.max(area.x() + area.width(), mc().player.getBlockX()) + 3
        || c.z() < Math.min(area.z(), mc().player.getBlockZ()) - 3
        || c.z() > Math.max(area.z() + area.depth(), mc().player.getBlockZ()) + 3
        || !full(c)) return false;
    return clear(c.x() + .5, c.z() + .5);
  }

  private boolean clear(double x, double z) {
    double half = mc().player.getBbWidth() / 2;
    return mc().level
        .noCollision(
            mc().player,
            new AABB(
                x - half,
                area.y() + 1.01,
                z - half,
                x + half,
                area.y() + 1 + mc().player.getBbHeight(),
                z + half));
  }

  private boolean supported(double x, double z) {
    double half = mc().player.getBbWidth() / 2, coverage = 0;
    for (int bx = (int) Math.floor(x - half); bx <= (int) Math.floor(x + half); bx++)
      for (int bz = (int) Math.floor(z - half); bz <= (int) Math.floor(z + half); bz++) {
        var c = new PlatformPlan.Cell(bx, bz);
        if (full(c) && (pending == null || !c.equals(pending) || completed.contains(c)))
          coverage += PlatformPlan.overlap(x, z, half, c);
      }
    return coverage >= .07 && clear(x, z);
  }

  private int materialCount() {
    int count = 0;
    for (int i = 0; i < 36; i++) {
      var s = mc().player.getInventory().getItem(i);
      if (!s.isEmpty()
          && !s.hasTag()
          && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(item))
        count += s.getCount();
    }
    return count;
  }

  private boolean material(ItemStack stack) {
    return !stack.isEmpty()
        && !stack.hasTag()
        && stack.getItem() instanceof BlockItem b
        && b.getBlock() == block;
  }

  public static JsonObject status() {
    if (last.size() == 0 && Store.data.checkpoints.containsKey("platform_build"))
      last = Store.data.checkpoints.get("platform_build").deepCopy();
    var o = last.deepCopy();
    if (Actions.job instanceof PlatformJob job) {
      o = survey(job.area);
      o.addProperty("scope", job.scope);
      o.addProperty("state", "running");
      o.addProperty("placed_verified", job.placed);
      o.addProperty("item", job.item);
      o.addProperty("phase", job.phase);
      if (job.pending != null) o.addProperty("pending", job.pos(job.pending).toShortString());
    }
    o.addProperty(
        "historical_context",
        !Agent.str(o, "scope", "").equals(Store.scope() + "|" + mc().level.dimension().location()));
    return o;
  }

  public static JsonObject preview(PlatformPlan.Area area) {
    validateArea(area);
    return survey(area);
  }

  private static JsonObject survey(PlatformPlan.Area area) {
    var o = new JsonObject();
    int air = 0, occupied = 0, unknown = 0;
    for (var c : area.cells()) {
      var p = new BlockPos(c.x(), area.y(), c.z());
      if (!mc().level.hasChunkAt(p)) unknown++;
      else if (mc().level.getBlockState(p).isAir() && mc().level.getFluidState(p).isEmpty()) air++;
      else occupied++;
    }
    o.addProperty("cells", area.width() * area.depth());
    o.addProperty("missing_air_cells", air);
    o.addProperty("preserved_existing", occupied);
    o.addProperty("unloaded", unknown);
    o.addProperty("floor_y", area.y());
    o.addProperty("note", "只填空气，不拆现有方块；预览不保证路径或材料足够");
    return o;
  }

  private void publish(String state, String reason) {
    if (!scope.equals(Store.scope() + "|" + mc().level.dimension().location())) return;
    var o = survey(area);
    o.addProperty("scope", scope);
    o.addProperty("state", state);
    o.addProperty("phase", state);
    o.addProperty("reason", reason);
    o.addProperty("placed_verified", placed);
    o.addProperty("item", item);
    if (pending != null) o.addProperty("pending", pos(pending).toShortString());
    last = o;
    o.addProperty("x", area.x());
    o.addProperty("y", area.y());
    o.addProperty("z", area.z());
    o.addProperty("width", area.width());
    o.addProperty("depth", area.depth());
    Store.data.checkpoints.put("platform_build", o.deepCopy());
    Store.save();
  }

  private void halt(String code, String reason) {
    finished = true;
    publish("stopped", reason);
    finish(
        (placed > 0 ? "部分完成：" : "失败：")
            + "["
            + code
            + "] "
            + reason
            + "；已核实放置"
            + placed
            + "格；不重放未确认点击。");
  }

  public void interruptedBy(String reason) {
    halt("BUILD_INTERRUPTED", reason);
  }

  private void crouch() {
    PlayerControls.hold(mc().options.keyShift);
    mc().player.setSprinting(false);
    if (!mc().player.isShiftKeyDown()) {
      mc().player.setShiftKeyDown(true);
      mc().getConnection()
          .send(
              new ServerboundPlayerCommandPacket(
                  mc().player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
    }
  }

  private void still() {
    PlayerControls.motionState(false, false);
  }

  public void guardEdge() {
    if (mc().player == null || mc().level == null) return;
    if (!started || phase == 0 || mc().screen != null || !Agent.enabled || Agent.paused) return;
    var p = mc().player;
    var v = p.getDeltaMovement();
    if (!supported(p.getX() + v.x * 1.5, p.getZ() + v.z * 1.5))
      halt("BUILD_EDGE", "逐刻边缘检查发现预计落脚支撑不足，释放移动");
  }

  private boolean move(Vec3 target) {
    var p = mc().player;
    double dx = target.x - p.getX(), dz = target.z - p.getZ(), distance = Math.hypot(dx, dz);
    if (distance < .055) {
      still();
      stepTicks = 0;
      return true;
    }
    if (++stepTicks > 180) {
      halt("BUILD_STUCK", "潜行移动180刻没有到达下一个落脚点");
      return false;
    }
    double step = Math.min(.07, distance);
    double nx = p.getX() + dx / distance * step + p.getDeltaMovement().x * .5;
    double nz = p.getZ() + dz / distance * step + p.getDeltaMovement().z * .5;
    if (!supported(nx, nz)) {
      halt("BUILD_EDGE", "下一步支撑不足，已停止向虚空推进");
      return false;
    }
    p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
    p.setXRot(35);
    PlayerControls.motionState(true, false);
    return false;
  }

  private void choose() {
    pending = null;
    still();
    var start = new PlatformPlan.Cell(mc().player.getBlockX(), mc().player.getBlockZ());
    if (!walkable(start)) {
      halt("BUILD_FOOTING", "脚下不是可通行完整方块，请先回到平台中心");
      return;
    }
    var parents = PlatformPlan.reachable(start, this::walkable, 4096);
    double best = Double.MAX_VALUE;
    PlatformPlan.Cell target = null, support = null;
    Vec3 selected = null;
    for (var c : cells) {
      var at = pos(c);
      if (!mc().level.hasChunkAt(at)) {
        halt("UNLOADED_TARGET", "施工区域有未加载方块");
        return;
      }
      if (!mc().level.getBlockState(at).isAir() || !mc().level.getFluidState(at).isEmpty())
        continue;
      for (var neighbor : c.neighbors()) {
        if (!parents.containsKey(neighbor) || !walkable(neighbor)) continue;
        double sx = neighbor.x() + .5 + (c.x() - neighbor.x()) * .60;
        double sz = neighbor.z() + .5 + (c.z() - neighbor.z()) * .60;
        if (!supported(sx, sz)) continue;
        var point = new Vec3(sx, area.y() + 1, sz);
        if (Reach.placementFrom(at, point.add(0, mc().player.getEyeHeight(), 0)) == null) continue;
        double cost =
            PlatformPlan.path(parents, neighbor).size()
                + point.distanceToSqr(mc().player.position()) * .1;
        if (cost < best) {
          best = cost;
          target = c;
          support = neighbor;
          selected = point;
        }
      }
    }
    if (target == null) {
      int missing = preview(area).get("missing_air_cells").getAsInt();
      if (missing > 0) {
        halt("BUILD_FRONTIER", "剩余" + missing + "个空位没有安全可达支撑面");
        return;
      }
      finished = true;
      publish("complete", "空气位已铺完，现有方块保持不变");
      finish("成功：施工范围空气位已铺完；本次服务器方块与材料核实" + placed + "格，保留原有占用。不是所有占用方块都保证可通行。");
      return;
    }
    pending = target;
    anchor = support;
    stance = selected;
    route = new ArrayDeque<>(PlatformPlan.path(parents, support));
    phase = 1;
    stepTicks = 0;
    aim = new AimGate();
  }

  @Override
  void tick() {
    if (++ticks > 72000) {
      halt("BUILD_TIMEOUT", "单批施工超过一小时");
      return;
    }
    var p = mc().player;
    if (!started) {
      originalSneak = p.isShiftKeyDown();
      PlayerControls.release();
      generation = PlayerControls.generation;
      started = true;
      if (preview(area).get("unloaded").getAsInt() > 0) {
        halt("UNLOADED_TARGET", "施工区域未完全加载");
        return;
      }
      publish("running", "预检完成，准备材料和安全落脚点");
    }
    Actions.status =
        "平台施工 · 已核实"
            + placed
            + "格 · "
            + (phase == 3 ? "等待服务器放置核验" : phase == 1 || phase == 2 ? "潜行安全推进" : "准备下一格");
    if (phase != 0 && generation != PlayerControls.generation) {
      halt("BUILD_INTERRUPTED", "控制输入已被其他操作释放");
      return;
    }
    if (prepare == null && (mc().screen != null || p.containerMenu != p.inventoryMenu)) {
      halt("SCREEN_BLOCKING", "施工时界面发生变化");
      return;
    }
    if (Math.abs(p.getY() - (area.y() + 1)) > .18
        || !p.onGround()
        || p.isInWater()
        || p.isInLava()) {
      halt("BUILD_FOOTING", "离地、受击或高度改变，释放移动；无法保证外力下绝不掉落");
      return;
    }
    if (!supported(p.getX(), p.getZ())) {
      halt("BUILD_EDGE", "当前支撑不足，停止移动");
      return;
    }
    if (phase == 0) {
      still();
      if (prepare == null && survey(area).get("missing_air_cells").getAsInt() == 0) {
        finished = true;
        publish("complete", "空气位已铺完");
        finish("成功：平台补空位施工结束；本次已核实" + placed + "格，原有方块未拆改。");
        return;
      }
      if (prepare != null) {
        int r = prepare.tick();
        if (r < 0) {
          halt("BUILD_MATERIAL", prepare.error);
          return;
        }
        if (r == 0) return;
        p.getInventory().selected = prepare.readySlot;
        prepare = null;
        generation = PlayerControls.generation;
      }
      int hot = -1;
      for (int i = 0; i < 9; i++)
        if (material(p.getInventory().getItem(i))) {
          hot = i;
          break;
        }
      if (hot < 0) {
        if (materialCount() == 0 || creative) {
          halt("BUILD_MATERIAL", "材料不足；创造模式请先将普通材料放到快捷栏");
          return;
        }
        prepare = InventoryMove.ensure(this::material);
        return;
      }
      p.getInventory().selected = hot;
      fingerprint = WorldSense.fingerprint(p.getMainHandItem());
      PlayerEvidence.begin();
      crouch();
      choose();
      return;
    }
    crouch();
    if (phase == 1) {
      if (route.isEmpty()) {
        phase = 2;
        stepTicks = 0;
        return;
      }
      var next = route.peekFirst();
      if (!walkable(next)) {
        halt("BUILD_PATH_CHANGED", "原先已确认的落脚方块或头部空间变化");
        return;
      }
      if (move(new Vec3(next.x() + .5, area.y() + 1, next.z() + .5))) route.removeFirst();
      return;
    }
    if (phase == 2) {
      if (!walkable(anchor)) {
        halt("BUILD_SUPPORT_CHANGED", "支撑方块被改变");
        return;
      }
      if (!move(stance)) return;
      var target = pos(pending);
      if (!mc().level.getBlockState(target).isAir()) {
        halt("BUILD_TARGET_CHANGED", "目标已被其他操作占用");
        return;
      }
      var hit = Reach.placementFrom(target, p.getEyePosition());
      var stack = p.getMainHandItem();
      if (hit == null || !material(stack)) {
        halt("BUILD_REACH", "支撑面不可见或手中材料变化");
        return;
      }
      var context = new BlockPlaceContext(p, InteractionHand.MAIN_HAND, stack, hit);
      if (!context.canPlace() || !context.getClickedPos().equals(target)) {
        halt("BUILD_PLACE_CONDITION", "不能正常放入目标格");
        return;
      }
      if (!aim.ready(hit.getLocation())) return;
      before = PlayerEvidence.total(PlayerEvidence.actual(fingerprint));
      PlayerEvidence.forget(target);
      mc().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
      p.swing(InteractionHand.MAIN_HAND);
      phase = 3;
      wait = 0;
      return;
    }
    if (phase == 3) {
      still();
      var target = pos(pending);
      var server = PlayerEvidence.block(target);
      boolean match =
          server != null && server.is(block) && server.equals(mc().level.getBlockState(target));
      boolean counts =
          creative
              || PlacementProof.accepted(
                  true,
                  match,
                  true,
                  before,
                  PlayerEvidence.total(PlayerEvidence.actual(fingerprint)),
                  PlayerEvidence.total(PlayerEvidence.confirmed(fingerprint)));
      if (match && counts) {
        completed.add(pending);
        placed++;
        WorldSense.record(target, server);
        pending = null;
        phase = 4;
        wait = 0;
        if (placed % 16 == 0) publish("running", "最近16格已核实");
        return;
      }
      if (++wait > 100) halt("BUILD_PLACE_UNCONFIRMED", "5秒内没有取得匹配方块更新及实际材料消耗；未继续向前");
      return;
    }
    if (++wait >= delay) {
      phase = 0;
    }
  }

  @Override
  void stop() {
    PlayerControls.release();
    if (started && mc().player != null && !originalSneak) {
      mc().player.setShiftKeyDown(false);
      if (mc().getConnection() != null)
        mc().getConnection()
            .send(
                new ServerboundPlayerCommandPacket(
                    mc().player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
    }
    if (mc().player != null) mc().player.getInventory().selected = oldSlot;
    if (!finished && started) {
      try {
        publish("interrupted", "人工停止或执行中断；不自动续做");
      } catch (RuntimeException ignored) {
      }
    }
    if (prepare != null) prepare.stop();
    super.stop();
  }

  @Override
  String interrupted() {
    return "平台施工已停止；已核实" + placed + "格。再次施工须重新观察，不重放点击。";
  }
}
