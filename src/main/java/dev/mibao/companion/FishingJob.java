package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.FishingRodItem;

/** Client-owned bobber motion drives a bounded cast/reel loop through normal item use. */
final class FishingJob extends Actions.Job {
  private final InteractionHand hand;
  private final int target, budget;
  private final InventorySession sync = new InventorySession(false);
  private Map<String, Integer> baseline = Map.of();
  private final Map<String, Integer> gained = new LinkedHashMap<>();
  private int phase, wait, caught, attempts, hookId = -1;

  FishingJob(JsonObject args, Consumer<String> done) {
    super(done);
    String selected = Agent.str(args, "hand", "main");
    if (!Set.of("main", "off").contains(selected)) throw new IllegalArgumentException("hand为main/off");
    hand = selected.equals("off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    target = SkillCatalog.bounded(args, "count", 3, 1, 64);
    budget = SkillCatalog.bounded(args, "seconds", 180, 10, 3600) * 20;
  }

  @Override void tick() {
    var mc = Minecraft.getInstance();
    if (++ticks > budget) { complete("钓鱼时间预算结束"); return; }
    if (mc.screen != null) { complete("界面改变，钓鱼结束"); return; }
    var rod = mc.player.getItemInHand(hand);
    if (!(rod.getItem() instanceof FishingRodItem)) { complete("请将鱼竿准备到指定手中"); return; }
    if (rod.getMaxDamage() - rod.getDamageValue() <= 3 && !mc.player.isCreative()) {
      complete("鱼竿剩余耐久偏低"); return;
    }
    Actions.status = "自动钓鱼 · " + caught + " / " + target + " · 阶段 " + phase;
    var hook = mc.player.fishing;
    wait++;
    if (phase == 0) {
      if (mc.player.getInventory().getFreeSlot() < 0) { complete("背包需要空位"); return; }
      if (hook != null) { complete("已有浮漂，请先完成或收回当前钓鱼"); return; }
      baseline = PlayerEvidence.actual("*");
      mc.gameMode.useItem(mc.player, hand);
      phase = 1; wait = 0; attempts++;
    } else if (phase == 1) {
      if (hook != null && hook.getOwner() == mc.player) {
        hookId = hook.getId(); phase = 2; wait = 0;
      } else if (wait > 60) complete("抛竿后未确认自己的浮漂，请检查朝向和水域");
    } else if (phase == 2) {
      if (hook == null || hook.getId() != hookId) { complete("自己的浮漂已改变"); return; }
      if (wait > 20 && mc.level.getFluidState(hook.blockPosition()).is(net.minecraft.tags.FluidTags.WATER) && hook.getDeltaMovement().y < -.04
          && Math.abs(hook.getDeltaMovement().x) < .01 && Math.abs(hook.getDeltaMovement().z) < .01) {
        mc.gameMode.useItem(mc.player, hand);
        sync.refresh();
        phase = 3; wait = 0;
      } else if (wait > 2400) complete("等待咬钩超时，请更换水域");
    } else if (phase == 3) {
      if (wait < 30) return;
      int ready = sync.tick();
      if (ready == 0) return;
      if (ready < 0) { complete("收线后背包同步未确认"); return; }
      var after = PlayerEvidence.actual("*");
      int added = 0;
      for (var entry : after.entrySet()) {
        int difference = entry.getValue() - baseline.getOrDefault(entry.getKey(), 0);
        if (difference > 0 && !entry.getKey().startsWith("minecraft:fishing_rod")) {
          gained.merge(entry.getKey(), difference, Integer::sum); added += difference;
        }
      }
      sync.close();
      if (added == 0) { complete("已收线，未确认钓获入包"); return; }
      caught++;
      if (caught >= target) { complete("钓获目标完成"); return; }
      phase = 4; wait = 0;
    } else if (wait > 20) { phase = 0; wait = 0; }
  }

  private void complete(String reason) {
    var facts = new JsonObject();
    facts.addProperty("casts", attempts); facts.addProperty("catches_correlated", caught);
    facts.addProperty("own_hook_id", hookId);
    facts.add("inventory_added", Store.GSON.toJsonTree(gained));
    facts.addProperty("attribution", "own_bobber_motion_reel_and_inventory_window; external_supply_can_overlap");
    finish(new ActionResult(caught >= target ? ActionResult.Status.verified : ActionResult.Status.partial,
        ActionResult.Code.NONE, reason, facts).json());
  }

  @Override void stop() {
    sync.close();
    super.stop();
  }
}
