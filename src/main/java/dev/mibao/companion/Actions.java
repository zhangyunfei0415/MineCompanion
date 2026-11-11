package dev.mibao.companion;

import com.google.gson.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.*;

public final class Actions {
  public static Job job;
  public static TransferReceipt lastTransfer;
  public static String status = "待命";

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public abstract static class Job {
    final long epoch = Agent.epoch;
    final Consumer<String> done;
    int ticks;
    final String dimension = mc().level.dimension().location().toString();

    Job(Consumer<String> d) {
      done = d;
    }

    abstract void tick();

    void stop() {
      Navigator.release();
      if (mc().gameMode != null) mc().gameMode.stopDestroyBlock();
    }

    String interrupted() {
      return "";
    }

    void finish(String text) {
      stop();
      if (job == this) {
        job = null;
        status = ActionSummary.describe(text);
        if (epoch == Agent.epoch) done.accept(text);
      }
    }
  }

  public static void cancel() {
    AgentPointer.clear();
    if (job != null) {
      String message = job.interrupted();
      job.stop();
      if (!message.isBlank()) Companion.notifyUser(message);
    }
    job = null;
    Navigator.release();
    status = "已停止";
  }

  public static void tick() {
    if (job == null) return;
    if (mc().player == null || mc().level == null || job.epoch != Agent.epoch) {
      cancel();
      return;
    }
    if (!job.dimension.equals(mc().level.dimension().location().toString())) {
      job.finish("失败：维度已切换，旧动作作废，请重新观察。");
      return;
    }
    if (mc().player.isDeadOrDying()) {
      Job j = job;
      j.finish("失败：角色死亡。请协助复活或取回物品。");
      Agent.stop("角色死亡，等待帮助");
      return;
    }
    if (SurvivalPolicy.blocked(
            mc().player.getHealth(),
            SurvivalPolicy.critical(mc().player.getMaxHealth()),
            Store.config.testIgnoreLowHealth)
        && !HealthRecovery.active()
        && !(job instanceof EatJob)) {
      job.finish("失败：[RECOVERY_REQUIRED] 生命偏低，停止当前作业，先观察并恢复。");
      return;
    }
    if (Agent.paused) {
      if (job instanceof GameInputJob) job.finish("输入已中断并释放：AI已暂停，请重新观察。");
      Navigator.release();
      return;
    }
    if (Panel.managing(mc().screen)) {
      if (job instanceof GameInputJob) job.finish("输入已中断并释放：进入伙伴面板，不自动续按。");
      Navigator.release();
      return;
    }
    Job current = job;
    try {
      current.tick();
    } catch (Exception e) {
      if (job == current)
        current.finish(
            "失败：" + e.getClass().getSimpleName() + " " + Rules.clean(e.getMessage(), 160));
    }
  }

  public static JsonObject observe() {
    return observation(true);
  }

  public static JsonObject compact() {
    var o = new JsonObject();
    var p = mc().player;
    o.addProperty("build", BuildInfo.ID);
    o.addProperty("epoch", Agent.epoch);
    o.addProperty("epoch_reason", Agent.lastEpochReason);
    o.add("cooperation", Cooperation.view());
    o.addProperty("connected", p != null);
    if (p == null) return o;
    o.addProperty("server_scope", Store.scope());
    o.addProperty("dimension", mc().level.dimension().location().toString());
    o.addProperty("x", p.getX());
    o.addProperty("y", p.getY());
    o.addProperty("z", p.getZ());
    o.addProperty("job", status);
    o.add("menu_summary", menuSummary());
    o.addProperty("details", "inspect_menu offset/limit; inspect_gui; observe compact=false");
    return o;
  }

  public static JsonObject menuSummary() {
    var o = new JsonObject();
    var m = mc().player.containerMenu;
    int slots = 0, empty = 0;
    for (var s : m.slots)
      if (s.container != mc().player.getInventory()) {
        slots++;
        if (!s.hasItem()) empty++;
      }
    o.addProperty("menu_id", m.containerId);
    o.addProperty("total_slots", m.slots.size());
    o.addProperty("container_slots", slots);
    o.addProperty("container_empty", empty);
    o.addProperty("sync", MenuContext.syncStatus());
    o.add("cursor", item(m.getCarried()));
    return o;
  }

  public static JsonObject snapshot() {
    return observation(false);
  }

  private static JsonObject observation(boolean capture) {
    JsonObject o = new JsonObject();
    o.addProperty("build", BuildInfo.ID);
    o.addProperty("environment", EnvironmentKey.current());
    var p = mc().player;
    if (p == null) {
      o.addProperty("connected", false);
      return o;
    }
    o.add("runtime_stats", RuntimeStats.snapshot());
    o.add("client_debug", ClientDebug.inspect(null));
    o.add("execution_plan", ProgressBoard.snapshot());
    o.addProperty("game_mode", mc().gameMode.getPlayerMode().getName());
    o.addProperty("command_permission_level_2", p.hasPermissions(2));
    o.addProperty("connected", true);
    o.addProperty("epoch", Agent.epoch);
    o.addProperty("driver", Store.config.driver);
    o.addProperty("watching", WatchMode.active());
    o.addProperty("combat", HuntJob.detail);
    o.addProperty("server_scope", Store.scope());
    o.addProperty("dimension", mc().level.dimension().location().toString());
    o.addProperty("yaw", p.getYRot());
    o.addProperty("pitch", p.getXRot());
    o.addProperty("on_ground", p.onGround());
    o.add(
        "velocity",
        Store.GSON.toJsonTree(
            new double[] {p.getDeltaMovement().x, p.getDeltaMovement().y, p.getDeltaMovement().z}));
    o.addProperty("x", p.getX());
    o.addProperty("y", p.getY());
    o.addProperty("z", p.getZ());
    o.addProperty("health", p.getHealth());
    o.addProperty("max_health", p.getMaxHealth());
    o.addProperty("food", p.getFoodData().getFoodLevel());
    o.addProperty("job", ActionSummary.describe(status));
    o.add("local_execution", LocalExecutor.observe());
    JsonArray inv = new JsonArray();
    for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
      ItemStack s = p.getInventory().getItem(i);
      if (!s.isEmpty()) {
        JsonObject v = item(s);
        v.addProperty("inventory_slot", i);
        inv.add(v);
      }
    }
    o.add("inventory", inv);
    o.add("inventory_memory", InventoryMemory.summary());
    JsonArray slots = new JsonArray();
    var menu = p.containerMenu;
    for (int i = 0; i < Math.min(menu.slots.size(), 96); i++) slots.add(describeSlot(i));
    o.add("menu_slots", slots);
    o.add("menu_summary", menuSummary());
    o.addProperty("menu_slot_count", menu.slots.size());
    o.addProperty("menu_slots_truncated", menu.slots.size() > 96);
    o.add("controls", PlayerControls.context());
    o.addProperty("container_open", menu != p.inventoryMenu);
    o.addProperty("menu_type", menu.getClass().getName());
    o.addProperty("screen", mc().screen == null ? "world" : mc().screen.getTitle().getString());
    o.addProperty("menu_id", menu.containerId);
    o.addProperty("menu_state_id", menu.getStateId());
    o.addProperty("menu_kind", MenuContext.kind());
    o.add("cursor_stack", item(menu.getCarried()));
    // A passive observe must not revoke the newest click view; only inspect_menu/inspect_gui
    // capture, so the model can finish its turn before clicking.
    o.addProperty("view_id", capture ? MenuContext.view() : -1);
    o.addProperty("menu_sync", MenuContext.syncStatus());
    o.addProperty("receipt_tap_attached", ClientEvidence.attached());
    if (MenuContext.source() != null)
      o.addProperty(
          "menu_source",
          WorldMemory.key(
              mc().level.dimension().location().toString(),
              MenuContext.source().getX(),
              MenuContext.source().getY(),
              MenuContext.source().getZ()));
    if (mc().hitResult instanceof BlockHitResult h) {
      o.addProperty(
          "looking_block", mc().level.getBlockState(h.getBlockPos()).getBlock().getDescriptionId());
      o.add(
          "looking_position",
          Store.GSON.toJsonTree(
              new int[] {h.getBlockPos().getX(), h.getBlockPos().getY(), h.getBlockPos().getZ()}));
    }
    Perception.append(o);
    WorldSense.appendEntities(o);
    o.add("dynamic_scene", DynamicScene.inspect());
    o.add("permissions", PermissionView.current());
    o.add("world_objects_nearby", WorldSense.query("", "all", 32, 8));
    o.add("places", Store.GSON.toJsonTree(Store.data.places));
    o.add("visual_assistance", VisualAssist.context());
    return o;
  }

  public static JsonObject item(ItemStack s) {
    JsonObject o = new JsonObject();
    o.addProperty("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
    o.addProperty("count", s.getCount());
    if (!s.isEmpty()) {
      o.addProperty("name", s.getHoverName().getString());
      o.addProperty("fingerprint", WorldSense.fingerprint(s));
      if (s.hasTag()) o.addProperty("nbt", Rules.clean(s.getTag().toString(), 1800));
    }
    return o;
  }

  private static JsonObject describeSlot(int i) {
    var p = mc().player;
    var slot = p.containerMenu.slots.get(i);
    JsonObject v = item(slot.getItem());
    v.addProperty("menu_slot", i);
    v.addProperty(
        "role",
        slot.container == p.getInventory()
            ? (slot.getContainerSlot() >= 0 && slot.getContainerSlot() <= 40
                ? "player_"
                    + InventoryLayout.role(InventoryLayout.menuSlot(slot.getContainerSlot()))
                : "player_extra")
            : MenuRefresh.role(i));
    v.addProperty("player_inventory", slot.container == p.getInventory());
    v.addProperty("container_slot", slot.getContainerSlot());
    v.addProperty("x", slot.x);
    v.addProperty("y", slot.y);
    v.addProperty("slot_class", slot.getClass().getName());
    if (mc().screen
        instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
      int x = screen.getGuiLeft() + slot.x + 8, y = screen.getGuiTop() + slot.y + 8;
      v.addProperty("gui_x", x);
      v.addProperty("gui_y", y);
      v.addProperty("coordinate_space", "logical_gui_absolute; x/y remain container_local");
      v.addProperty(
          "visible", slot.isActive() && x >= 0 && y >= 0 && x < screen.width && y < screen.height);
    }
    v.addProperty("can_pickup", slot.mayPickup(p));
    v.addProperty("max_stack", slot.getMaxStackSize());
    if (!p.containerMenu.getCarried().isEmpty())
      v.addProperty("accepts_cursor", slot.mayPlace(p.containerMenu.getCarried()));
    return v;
  }

  public static JsonObject inspectMenu(int offset, int limit) {
    JsonObject result = inspectMenuContents(offset, limit);
    result.addProperty("menu_sync", MenuContext.syncStatus());
    return result;
  }

  private static JsonObject inspectMenuContents(int offset, int limit) {
    var menu = mc().player.containerMenu;
    JsonObject o = new JsonObject();
    o.addProperty("menu_id", menu.containerId);
    o.addProperty("menu_state_id", menu.getStateId());
    o.addProperty("view_id", MenuContext.capture(offset, limit));
    o.addProperty("menu_type", menu.getClass().getName());
    o.addProperty("menu_kind", MenuContext.kind());
    o.addProperty("total_slots", menu.slots.size());
    o.add("cursor_stack", item(menu.getCarried()));
    JsonArray slots = new JsonArray();
    for (int i = Math.max(0, offset); i < Math.min(menu.slots.size(), offset + limit); i++)
      slots.add(describeSlot(i));
    o.add("slots", slots);
    return o;
  }

  public static String rememberPlace(String name) {
    var p = mc().player;
    BlockPos b = p.blockPosition();
    String block = "站立点";
    if (mc().hitResult instanceof BlockHitResult h
        && h.getType() == HitResult.Type.BLOCK
        && p.distanceToSqr(Vec3.atCenterOf(h.getBlockPos())) < 64) {
      b = h.getBlockPos();
      block = BuiltInRegistries.BLOCK.getKey(mc().level.getBlockState(b).getBlock()).toString();
    }
    return rememberPosition(name, b);
  }

  public static String rememberPosition(String name, BlockPos b) {
    if (name.isBlank()) throw new IllegalArgumentException("地点名不能为空");
    Store.Place place = new Store.Place();
    place.name = Rules.clean(name, 80);
    place.x = b.getX();
    place.y = b.getY();
    place.z = b.getZ();
    place.block =
        mc().level.hasChunkAt(b)
            ? BuiltInRegistries.BLOCK.getKey(mc().level.getBlockState(b).getBlock()).toString()
            : "未知未加载";
    place.dimension = mc().level.dimension().location().toString();
    place.updated = Instant.now().toString();
    if (MenuContext.matches(b) && MenuContext.synchronizedNow()) {
      WorldSense.rememberInventory(b);
      place.inventory = inventorySnapshot();
    }
    Store.data.places.put(place.name, place);
    if (mc().level.hasChunkAt(b)) {
      WorldSense.record(b, mc().level.getBlockState(b));
      var point =
          Store.data.world.get(WorldMemory.key(place.dimension, b.getX(), b.getY(), b.getZ()));
      if (point != null) point.label = place.name;
    }
    Store.save();
    return "已记住 " + place.name + " " + place.dimension + " " + b.toShortString();
  }

  private static String inventorySnapshot() {
    JsonArray a = new JsonArray();
    for (var slot : mc().player.containerMenu.slots)
      if (slot.container != mc().player.getInventory() && !slot.getItem().isEmpty())
        a.add(item(slot.getItem()));
    return Rules.clean(a.toString(), 20000);
  }

  public static boolean match(ItemStack s, String filter) {
    if (s.isEmpty()) return false;
    if (filter == null || filter.isBlank() || filter.equals("*")) return true;
    String[] parts = filter.split("#", 2);
    boolean base =
        BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(parts[0])
            || s.getHoverName().getString().contains(parts[0]);
    return base
        && (parts.length == 1
            || (parts[1].startsWith("sha256:")
                ? WorldSense.fingerprint(s).equals(filter)
                : s.hasTag() && s.getTag().toString().contains(parts[1])));
  }

  public static void move(BlockPos p, Consumer<String> done) {
    move(p, false, done);
  }

  public static void move(BlockPos p, boolean exact, Consumer<String> done) {
    job =
        new Job(done) {
          final Navigator nav = new Navigator();
          boolean started;

          void tick() {
            status = (exact ? "精确前往 " : "前往 ") + p.toShortString();
            if (!started) {
              started = true;
              if (!(exact ? nav.startExact(p) : nav.start(p))) {
                finish("失败：" + nav.error());
                return;
              }
            }
            int r = nav.tick();
            if (r == 1)
              finish(
                  (exact ? "已精确落地：" : "已在目标高度附近稳定落地：")
                      + p.toShortString()
                      + "；"
                      + ArrivalReceipt.landingText(
                          mc().player.getX(),
                          mc().player.getY(),
                          mc().player.getZ(),
                          p.getX(),
                          p.getY(),
                          p.getZ(),
                          Navigator.surfaceY(p))
                      + (exact ? "（精确模式水平容差0.4格）" : "（普通移动允许水平2格接近范围）"));
            if (r < 0) finish("失败：" + nav.error());
          }

          void stop() {
            nav.stop();
            super.stop();
          }
        };
  }

  public static BlockPos target(JsonObject args) {
    if (args.has("x") && args.has("z") && !args.has("y")
        && !args.has("object") && !args.has("place")) {
      BlockPos surface = ColumnProbe.nearest(args.get("x").getAsInt(), args.get("z").getAsInt(),
          mc().player.blockPosition().getY(), 32);
      if (surface == null) throw new IllegalArgumentException("[HEIGHT_UNKNOWN] 未找到已加载安全支撑面，请column_probe或提供y");
      return surface;
    }
    if (args.has("object") || args.has("place")) {
      String name = Agent.str(args, "object", Agent.str(args, "place", ""));
      var point = Store.data.world.get(name);
      if (point != null) {
        if (!point.dimension.equals(mc().level.dimension().location().toString()))
          throw new IllegalArgumentException("对象在其他维度");
        if (!point.present) throw new IllegalArgumentException("对象已被观察为消失，请重新查询");
        return new BlockPos(point.x, point.y, point.z);
      }
      var place = Store.data.places.get(name);
      if (place == null)
        throw new IllegalArgumentException("未知地点/对象，请先 scan_world 或 query_world 查询");
      if (!place.dimension.equals(mc().level.dimension().location().toString()))
        throw new IllegalArgumentException("地点位于其他维度，请先人工带往该维度");
      return place.pos();
    }
    return new BlockPos(
        args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
  }

  public static void transfer(
      BlockPos pos, String filter, int count, boolean deposit, Consumer<String> done) {
    lastTransfer = null;
    job = new ContainerJob(pos, filter, count, deposit, done);
  }

  static void click(int slot, int button, ClickType type) {
    var menu = mc().player.containerMenu;
    mc().gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, mc().player);
  }

  public static void open(BlockPos b) {
    BlockHitResult hit = Reach.hit(b);
    if (hit == null) throw new IllegalArgumentException("目标不在可见交互距离内，不能隔墙操作；请重新选择站位");
    InteractionHand hand = InteractionHand.MAIN_HAND;
    if (!mc().player.getMainHandItem().isEmpty() && !HandPreparation.allowsHeld(b)) {
      int empty = -1;
      for (int i = 0; i < 9; i++)
        if (mc().player.getInventory().getItem(i).isEmpty()) {
          empty = i;
          break;
        }
      if (empty < 0) throw new IllegalArgumentException("打开容器需要空主手，请先准备空快捷栏");
      mc().player.getInventory().selected = empty;
    }
    look(hit.getLocation());
    MenuContext.expect(b);
    mc().gameMode.useItemOn(mc().player, hand, hit);
  }

  public static void survey(int radius, Consumer<String> done) {
    survey(radius, 0, 24, done);
  }

  public static void survey(int radius, int offset, int limit, Consumer<String> done) {
    job =
        new Job(done) {
          long ticket = -1;

          void tick() {
            status = "观察附近世界 · 半径 " + radius;
            if (ticket < 0) ticket = Perception.refresh(radius);
            if (Perception.generation() >= ticket) {
              Store.save();
              finish(WorldSense.query("", "all", radius, offset, limit).toString());
            } else if (++ticks > 400) finish("失败：扫描超时，请站定后重新环视。");
          }
        };
  }

  public static void interact(BlockPos pos, boolean requireMenu, Consumer<String> done) {
    job =
        new Job(done) {
          final Navigator nav = new Navigator();
          final HandPreparation hand = new HandPreparation(pos);
          final AimGate aim = new AimGate();
          final InventorySession proof = new InventorySession(false);
          java.util.Map<String, Integer> baseline;
          int stage, delay;
          boolean started;
          String before;

          void tick() {
            if (++ticks > 3000) {
              finish("失败：交互超时");
              return;
            }
            status = "工作中 · " + (stage == 0 ? "寻找可见站位" : "确认交互结果");
            if (stage == 0) {
              if (!started) {
                started = true;
                if (!nav.startForInteraction(pos)) {
                  finish("失败：" + nav.error());
                  return;
                }
              }
              int r = nav.tick();
              if (r < 0) {
                finish("失败：" + nav.error());
                return;
              }
              if (r > 0) stage = 1;
              return;
            }
            if (stage == 1) {
              int r = hand.tick();
              if (r < 0) {
                finish("失败：打开前准备空手未完成：" + hand.error);
                return;
              }
              if (r == 0) return;
              var hit = Reach.hit(pos);
              if (hit == null) {
                finish("失败：准备空手后目标已不可见，请重新观察");
                return;
              }
              if (!aim.ready(hit.getLocation())) return;
              before = mc().level.getBlockState(pos).toString();
              baseline = PlayerEvidence.actual("*");
              PlayerEvidence.begin();
              open(pos);
              stage = 2;
              return;
            }
            if (++delay < 6) return;
            if (mc().player.containerMenu != mc().player.inventoryMenu) {
              if (!MenuContext.matches(pos)) {
                finish("失败：菜单来源不明");
                return;
              }
              if (MenuContext.synchronizedNow()) {
                if (!WorldSense.category(mc().level.getBlockState(pos)).isEmpty())
                  WorldSense.rememberInventory(pos);
                Store.save();
                finish(observe().toString());
                return;
              }
              if (delay > 100) finish("失败：已打开菜单，但没有取得完整且稳定的服务器槽位同步；库存仍未知。");
              return;
            }
            if (!requireMenu && !before.equals(mc().level.getBlockState(pos).toString())) {
              finish(WorldSense.inspect(pos).toString());
              return;
            }
            if (!requireMenu) {
              int r = proof.tick();
              if (r == 0) return;
              if (r < 0) {
                finish("部分完成：交互已发送，" + proof.error);
                return;
              }
              if (!baseline.equals(PlayerEvidence.actual("*"))
                  && PlayerEvidence.actual("*").equals(PlayerEvidence.confirmed("*"))) {
                finish("成功：交互后的服务器背包数量变化已核对；具体物品见observe，不代表生产流程全部完成。");
                return;
              }
            }
            if (delay > 80) finish("失败：未观察到" + (requireMenu ? "容器菜单" : "方块状态或菜单变化") + "；不宣称交互已成功。");
          }

          void stop() {
            proof.close();
            hand.stop();
            nav.stop();
            super.stop();
          }
        };
  }

  public static void look(Vec3 v) {
    Vec3 d = v.subtract(mc().player.getEyePosition());
    mc().player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
    mc().player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
  }

  public static void mine(String id, int count, int radius, Consumer<String> done) {
    if (Agent.resource != null) throw new IllegalArgumentException("已有采集任务");
    Agent.resource = new ResourceTask(id, count, radius, ResourceTask.isWood(id), done);
  }
}
