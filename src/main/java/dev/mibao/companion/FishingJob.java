package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Own-hook retrieval, nearby new loot and server pickup receipts credit fishing results. */
final class FishingJob extends Actions.Job {
  private final InteractionHand hand;
  private final int target, budget;
  private final long deadline;
  private final String delivery;
  private final InventorySession sync = new InventorySession(false);
  private Map<String, Integer> baseline = Map.of();
  private final Map<String, Integer> gained = new LinkedHashMap<>();
  private final Map<String, Integer> delivered = new LinkedHashMap<>();
  private final Map<String, Integer> pickedUp = new LinkedHashMap<>();
  private final Map<Integer, ItemStack> candidates = new HashMap<>();
  private final Set<Integer> preexisting = new HashSet<>();
  private InventoryMove prepare;
  private final boolean autoPrepare;
  private FishingSite site;
  private final Navigator nav = new Navigator();
  private final AimGate aim = new AimGate();
  private boolean siteReady, navigating, arrived;
  private Vec3 reelOrigin;
  private int phase, wait, caught, attempts, hookId = -1;
  private String biteSignal = "not_observed";

  FishingJob(JsonObject args, Consumer<String> done) {
    super(done);
    String selected = Agent.str(args, "hand", "main");
    if (!Set.of("main", "off").contains(selected))
      throw new IllegalArgumentException("hand为main/off");
    hand = selected.equals("off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    autoPrepare = !args.has("auto_prepare") || args.get("auto_prepare").getAsBoolean();
    target = SkillCatalog.bounded(args, "count", 3, 1, 64);
    budget = SkillCatalog.bounded(args, "seconds", 180, 10, 3600) * 20;
    deadline = System.nanoTime() + budget * 50_000_000L;
    delivery = Agent.str(args, "delivery", Store.data.resourceDelivery);
  }

  private boolean usable(ItemStack stack) {
    return stack.getItem() instanceof FishingRodItem
        && (Minecraft.getInstance().player.isCreative()
            || stack.getMaxDamage() - stack.getDamageValue() > 3);
  }

  void pickup(int entityId, int playerId, int amount) {
    var player = Minecraft.getInstance().player;
    if (phase != 3 || player == null || player.getId() != playerId) return;
    ItemStack item = candidates.remove(entityId);
    if (item != null && amount > 0)
      pickedUp.merge(WorldSense.fingerprint(item), Math.min(amount, item.getCount()), Integer::sum);
  }

  void observeLoot(int entityId) {
    var level = Minecraft.getInstance().level;
    if (phase != 3
        || wait > 10
        || level == null
        || preexisting.contains(entityId)
        || candidates.size() >= 32) return;
    if (level.getEntity(entityId) instanceof ItemEntity item
        && !item.getItem().isEmpty()
        && item.position().distanceToSqr(reelOrigin) <= 16)
      candidates.putIfAbsent(entityId, item.getItem().copy());
  }

  @Override
  void tick() {
    var mc = Minecraft.getInstance();
    ticks++;
    if (System.nanoTime() > deadline) {
      complete("钓鱼时间预算结束");
      return;
    }
    if (prepare != null) {
      int state = prepare.tick();
      if (state < 0) {
        complete(prepare.error);
        return;
      }
      if (state == 0) return;
      if (hand == InteractionHand.MAIN_HAND) mc.player.getInventory().selected = prepare.readySlot;
      prepare = null;
    }
    if (mc.screen != null) {
      complete("界面改变，钓鱼结束");
      return;
    }
    if (phase == 0 && !usable(mc.player.getItemInHand(hand))) {
      prepare =
          hand == InteractionHand.MAIN_HAND
              ? InventoryMove.ensure(this::usable)
              : new InventoryMove(this::usable, "offhand", -1);
      return;
    }
    if (phase == 0 && autoPrepare && !siteReady) {
      if (mc.player.fishing != null) {
        complete("已有浮漂，先观察当前钓鱼");
        return;
      }
      Actions.status = "钓鱼准备 · 搜索已加载水面和安全岸边";
      if (site == null) site = new FishingSite();
      if (!site.advance()) return;
      if (site.water == null) {
        complete("附近8格内没有可见水面与安全岸边组合");
        return;
      }
      if (!navigating) {
        if (!nav.startExact(site.shore)) {
          complete(nav.error());
          return;
        }
        navigating = true;
      }
      if (!arrived) {
        int moved = nav.tick();
        if (moved < 0) {
          complete(nav.error());
          return;
        }
        if (moved == 0) return;
        arrived = true;
      }
      if (!aim.ready(site.water)) return;
      siteReady = true;
    }
    Actions.status = "自动钓鱼 · " + caught + " / " + target + " · 阶段 " + phase;
    var hook = mc.player.fishing;
    wait++;
    if (phase == 0) {
      if (mc.player.getInventory().getFreeSlot() < 0) {
        if (!delivery.isBlank() && pendingCargo() != null) unload(false);
        else complete("未知钓获需要背包空位；本次没有可交付的已核实钓获");
        return;
      }
      if (hook != null) {
        complete("已有浮漂，请先完成或收回当前钓鱼");
        return;
      }
      mc.gameMode.useItem(mc.player, hand);
      phase = 1;
      wait = 0;
      attempts++;
    } else if (phase == 1) {
      if (hook != null && hook.getOwner() == mc.player) {
        hookId = hook.getId();
        phase = 2;
        wait = 0;
      } else if (wait > 60) complete("抛竿后未确认自己的浮漂，请检查朝向和水域");
    } else if (phase == 2) {
      if (hook == null || hook.getId() != hookId) {
        complete("自己的浮漂已改变");
        return;
      }
      Boolean synchronizedBite = FishingSignal.biting(hook);
      boolean bite =
          synchronizedBite != null
              ? synchronizedBite
              : hook.getDeltaMovement().y < -.04
                  && Math.abs(hook.getDeltaMovement().x) < .01
                  && Math.abs(hook.getDeltaMovement().z) < .01;
      biteSignal =
          synchronizedBite == null ? "client_motion_fallback" : "client_synced_DATA_BITING";
      if (wait > 20
          && mc.level.getFluidState(hook.blockPosition()).is(net.minecraft.tags.FluidTags.WATER)
          && bite) {
        if (!(mc.player.getItemInHand(hand).getItem() instanceof FishingRodItem)) {
          complete("收线前手持已改变，请观察当前浮漂");
          return;
        }
        reelOrigin = hook.position();
        preexisting.clear();
        candidates.clear();
        pickedUp.clear();
        for (var item :
            mc.level.getEntitiesOfClass(
                ItemEntity.class, new AABB(reelOrigin, reelOrigin).inflate(4)))
          preexisting.add(item.getId());
        baseline = PlayerEvidence.actual("*");
        mc.gameMode.useItem(mc.player, hand);
        sync.refresh();
        phase = 3;
        wait = 0;
      } else if (wait > 2400) complete("等待咬钩超时，请更换水域");
    } else if (phase == 3) {
      for (var item :
          mc.level.getEntitiesOfClass(
              ItemEntity.class, new AABB(reelOrigin, reelOrigin).inflate(4))) {
        observeLoot(item.getId());
      }
      if (wait < 40) return;
      int ready = sync.tick();
      if (ready == 0) return;
      if (ready < 0) {
        complete("收线后背包同步未确认");
        return;
      }
      var proven = FieldProof.caught(baseline, PlayerEvidence.actual("*"), pickedUp);
      sync.close();
      if (proven.isEmpty()) {
        complete("已收线，尚未取得钓获物拾取及库存共同证据");
        return;
      }
      proven.forEach((key, amount) -> gained.merge(key, amount, Integer::sum));
      caught++;
      if (caught >= target) {
        if (!delivery.isBlank()) unload(true);
        else complete("钓获目标完成");
        return;
      }
      phase = 4;
      wait = 0;
    } else if (wait > 20) {
      phase = 0;
      wait = 0;
    }
  }

  private void complete(String reason) {
    var facts = new JsonObject();
    facts.addProperty("casts", attempts);
    facts.addProperty("catches_correlated", caught);
    facts.addProperty("own_hook_id", hookId);
    facts.addProperty("bite_signal", biteSignal);
    facts.add("inventory_added", Store.GSON.toJsonTree(gained));
    facts.add("delivered_verified", Store.GSON.toJsonTree(delivered));
    facts.addProperty(
        "attribution", "own_reel_new_local_loot_server_pickup_and_exact_inventory_delta");
    boolean verified = caught >= target && (delivery.isBlank() || pendingCargo() == null);
    finish(
        new ActionResult(
                verified ? ActionResult.Status.verified : ActionResult.Status.partial,
                verified ? ActionResult.Code.NONE : ActionResult.Code.FISHING_UNCONFIRMED,
                reason,
                facts)
            .json());
  }

  private Map.Entry<String, Integer> pendingCargo() {
    return gained.entrySet().stream()
        .map(e -> Map.entry(e.getKey(), e.getValue() - delivered.getOrDefault(e.getKey(), 0)))
        .filter(e -> e.getValue() > 0)
        .findFirst()
        .orElse(null);
  }

  private void unload(boolean finishing) {
    var cargo = pendingCargo();
    if (cargo == null) {
      if (finishing) complete("钓获与指定箱交付完成");
      else {
        siteReady = navigating = arrived = false;
        phase = 0;
        wait = 0;
      }
      return;
    }
    if (System.nanoTime() > deadline) {
      complete("钓鱼预算结束，剩余钓获交付尚未完成");
      return;
    }
    int amount = Math.min(64, cargo.getValue());
    var args = new JsonObject();
    args.addProperty("place", delivery);
    args.addProperty("item", cargo.getKey());
    args.addProperty("count", amount);
    Actions.lastTransfer = null;
    child(
        "deposit",
        args,
        result -> {
          var transfer = Actions.lastTransfer;
          if (Rules.actionFailed(result)
              || transfer == null
              || transfer.epoch() != epoch
              || !transfer.deposit()
              || transfer.moved() != amount) {
            complete("钓获交付未完整核实，保留已知数量；" + result);
            return;
          }
          delivered.merge(cargo.getKey(), amount, Integer::sum);
          unload(finishing);
        });
  }

  @Override
  void stop() {
    nav.stop();
    if (prepare != null) prepare.stop();
    sync.close();
    super.stop();
  }
}
