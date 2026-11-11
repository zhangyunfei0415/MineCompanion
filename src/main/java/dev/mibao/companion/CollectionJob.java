package dev.mibao.companion;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

/** Client-synced drops -> safe nearby stance -> settled full inventory receipt. */
public final class CollectionJob extends Actions.Job {
  public record Receipt(
      long epoch,
      int gained,
      boolean inventoryVerified,
      boolean capacityBlocked,
      Map<String, Integer> inventory) {
    public Receipt {
      inventory = inventoryVerified ? Map.copyOf(inventory) : Map.of();
    }
  }

  public static Receipt lastReceipt;
  private static CollectionJob latest;
  private final DropSensor sensor = new DropSensor();
  private boolean capacityBlocked;
  private int candidates;
  private String obstacle = "";
  private final Navigator nav = new Navigator();
  private final java.util.function.Predicate<ItemEntity> scope;
  private final boolean sweep;
  private final String filter;
  private final int wanted, radius;
  private final BlockPos center;
  private final Map<String, Integer> before;
  private final TargetRecovery<UUID> recovery = new TargetRecovery<>(2, 12);
  private final InventorySession verify = new InventorySession();
  private ItemEntity target;
  private net.minecraft.world.phys.Vec3 targetPos;
  private int wait, empty, progress, previous, movingReplans, settle;
  private double bestDistance = Double.MAX_VALUE;
  private boolean ending, scopeClear, inventoryVerified;
  private String reason = "";

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public CollectionJob(String filter, int wanted, int radius, Consumer<String> done) {
    this(filter, wanted, radius, mc().player.blockPosition(), e -> true, false, done);
  }

  public CollectionJob(
      String filter,
      int wanted,
      int radius,
      BlockPos origin,
      java.util.function.Predicate<ItemEntity> scope,
      boolean sweep,
      Consumer<String> done) {
    super(done);
    lastReceipt = null;
    latest = this;
    this.scope = scope;
    this.sweep = sweep;
    this.filter = filter;
    this.wanted = wanted;
    this.radius = radius;
    center = origin;
    if (mc().gameMode.getPlayerMode() != GameType.SURVIVAL
        && mc().gameMode.getPlayerMode() != GameType.ADVENTURE)
      throw new IllegalArgumentException("拾取核验仅支持生存/冒险模式");
    if (mc().player.containerMenu != mc().player.inventoryMenu)
      throw new IllegalArgumentException("请先关闭容器");
    PlayerEvidence.begin();
    before = PlayerEvidence.actual(filter);
  }

  private int gained() {
    return PlayerEvidence.revision == 0
        ? 0
        : DeltaProof.gained(
            before, PlayerEvidence.actual(filter), PlayerEvidence.confirmed(filter));
  }

  private void end(String why, boolean clear) {
    if (ending) return;
    nav.stop();
    target = null;
    ending = true;
    scopeClear = clear;
    reason = why;
  }

  private boolean eligible(ItemEntity e) {
    return scope.test(e)
        && e.isAlive()
        && (e.getOwner() == null || e.getOwner().equals(mc().player.getUUID()))
        && Actions.match(e.getItem(), filter)
        && PickupGeometry.inArea(
            center.getX() + .5,
            center.getY(),
            center.getZ() + .5,
            e.getX(),
            e.getY(),
            e.getZ(),
            radius);
  }

  @Override
  void tick() {
    if (ending) {
      Actions.status = "收集掉落物 · 等待完整服务器背包核对";
      if (++settle < 8) return;
      int result = verify.tick();
      if (result == 0) return;
      inventoryVerified = result > 0;
      int n = gained();
      if (inventoryVerified) verify.close();
      boolean success = inventoryVerified && (sweep ? scopeClear : n >= wanted);
      finish(
          (success ? "成功：" : "部分完成：")
              + (success ? "" : capacityBlocked ? "[NO_CAPACITY] " : "[RESULT_UNCONFIRMED] ")
              + "服务器背包确认新增 "
              + n
              + " 件"
              + (sweep ? "（本次范围清理）" : "，目标 " + wanted)
              + "。"
              + reason
              + (inventoryVerified ? " 已完成最终完整背包同步；不按物品实体消失计数。" : " 最终完整背包未核实：" + verify.error));
      return;
    }
    int n = gained();
    Actions.status = "收集掉落物 · 已核实 " + n + " / " + (sweep ? "范围清理" : wanted);
    if (!sweep && n >= wanted) {
      end("整堆拾取可能超过目标。", true);
      return;
    }
    if (++ticks > 1800) {
      end("达到90秒预算，保留已拾取物品。", false);
      return;
    }
    if (mc().player.containerMenu != mc().player.inventoryMenu || mc().screen != null) {
      finish("部分完成：拾取期间菜单改变，保留现场；此前确认新增 " + n);
      return;
    }
    if (n > previous) {
      previous = n;
      progress = 0;
    } else if (++progress > 300) {
      end("15秒没有拾取或接近进展，请检查背包空间和通道。", false);
      return;
    }
    if (target == null) {
      if (ticks % 10 != 1) return;
      // Loaded item entities are structured client observations, even when terrain occludes them.
      var items =
          new ArrayList<>(sensor.observe(new AABB(center).inflate(radius), this::eligible, ticks));
      candidates = items.size();
      items.removeIf(e -> !eligible(e) || !recovery.available(e.getUUID(), ticks));
      if (items.isEmpty()) {
        if (recovery.waiting(ticks)) {
          Actions.status = "收集掉落物 · 等待目标冷却后重新观察";
          return;
        }
        if (++empty >= 4)
          end(
              (capacityBlocked ? "背包不能容纳剩余候选物品。" : "当前已加载工作范围内没有更多可用候选。")
                  + (sensor.truncated ? " 候选采样达到预算，不能宣称范围已清空。" : "")
                  + " 暂避 "
                  + recovery.size()
                  + " 个；"
                  + recovery.lastReason()
                  + " 未加载区域仍未知。",
              PickupCapacity.sweepClear(recovery.size(), sensor.truncated, capacityBlocked));
        return;
      }
      empty = 0;
      target = items.get(0);
      targetPos = target.position();
      wait = movingReplans = 0;
      bestDistance = Double.MAX_VALUE;
      if (target.isInWater()) {
        skip("水中掉落尚无安全拾取适配，不涉水追逐");
        return;
      }
      if (!capacity(target)) {
        capacityBlocked = true;
        skip("背包对这个ID/NBT变体没有空位或合并空间");
        return;
      }
      if (!nav.startForPickup(target.getBoundingBox())) skip(nav.error());
      return;
    }
    if (!eligible(target)) {
      nav.stop();
      target = null;
      return;
    }
    if (target.isInWater()) {
      skip("掉落物进入水中，停止接近");
      return;
    }
    double distance = mc().player.distanceToSqr(target);
    if (distance < bestDistance - .25) {
      bestDistance = distance;
      progress = 0;
    }
    if (target.position().distanceToSqr(targetPos) > .25 && ticks % 5 == 0) {
      if (++movingReplans > 8) {
        skip("掉落物持续移动，达到8次站位更新预算");
        return;
      }
      targetPos = target.position();
      wait = 0;
      if (!nav.startForPickup(target.getBoundingBox())) {
        skip(nav.error());
        return;
      }
    }
    if (!capacity(target)) {
      capacityBlocked = true;
      skip("背包空间已变化，无法容纳该变体");
      return;
    }
    int r = nav.tick();
    if (r < 0) {
      skip(nav.error());
      return;
    }
    if (r > 0 && ++wait > 40) skip("已接近但2秒没有入包，可能背包无法容纳或存在拾取延迟");
  }

  private void skip(String why) {
    obstacle = Rules.clean(why, 220);
    nav.stop();
    if (target != null && target.isAlive())
      recovery.reject(target.getUUID(), ticks, 40, Rules.clean(why, 220));
    target = null;
    if (recovery.full()) end("12个目标恢复预算已满。" + recovery.lastReason(), false);
  }

  private boolean capacity(ItemEntity item) {
    List<PickupCapacity.Slot> slots = new ArrayList<>();
    for (int i = 0; i < 36; i++) {
      var v = mc().player.getInventory().getItem(i);
      slots.add(
          new PickupCapacity.Slot(WorldSense.fingerprint(v), v.getCount(), v.getMaxStackSize()));
    }
    return PickupCapacity.room(
            slots, WorldSense.fingerprint(item.getItem()), item.getItem().getMaxStackSize())
        > 0;
  }

  public static com.google.gson.JsonObject diagnostic() {
    var o = new com.google.gson.JsonObject();
    var j = latest;
    boolean active = Actions.job instanceof CollectionJob;
    o.addProperty("active", active);
    if (!active || j == null) return o;
    o.addProperty("sampled_entities", j.sensor.sampled);
    o.addProperty("candidates", j.candidates);
    o.addProperty("sample_truncated", j.sensor.truncated);
    o.addProperty("capacity_blocked", j.capacityBlocked);
    o.addProperty("failed_targets", j.recovery.size());
    o.addProperty(
        "phase",
        j.ending ? "verify_inventory" : j.target == null ? "sense_select" : "approach_pickup");
    o.addProperty("navigation", j.nav.diagnostic());
    o.addProperty("last_obstacle", j.obstacle);
    return o;
  }

  @Override
  String interrupted() {
    return "收集已停止；此前确认新增 " + gained() + "，未完成最终核对。物品消失不作为成功证据。";
  }

  @Override
  void stop() {
    nav.stop();
    super.stop();
  }

  @Override
  void finish(String text) {
    lastReceipt =
        new Receipt(
            epoch,
            gained(),
            inventoryVerified,
            capacityBlocked,
            inventoryVerified ? Map.copyOf(PlayerEvidence.actual("*")) : Map.of());
    super.finish(text);
  }
}
