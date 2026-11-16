package dev.mibao.companion;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;

/** One activation, optional bounded sustain, release, then authoritative inventory observation. */
public final class UseHandJob extends Actions.Job {
  private final InteractionHand hand;
  private final int duration, oldSlot;
  private final String mode, filter;
  private InventoryMove prepare;
  private final InventorySession initial = new InventorySession(false);
  private final InventorySession verify = new InventorySession(false);
  private boolean baselineReady;
  private int phase, held, beforeCount;
  private int beforeItemTotal;
  private long lease;
  private boolean usingSeen;
  private ItemStack before;
  private long started;
  private net.minecraft.world.phys.Vec3 usedTarget;
  private boolean expectedUseSeen;

  void suspend() {
    releaseUse();
    if (phase == 1) phase = 2;
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public UseHandJob(String item, String hand, String mode, int ticks, Consumer<String> done) {
    super(done);
    filter = item;
    this.hand =
        switch (hand) {
          case "main" -> InteractionHand.MAIN_HAND;
          case "off" -> InteractionHand.OFF_HAND;
          default -> throw new IllegalArgumentException("[INVALID_HAND] hand为main/off");
        };
    if (!java.util.Set.of("tap", "hold").contains(mode))
      throw new IllegalArgumentException("[INVALID_MODE] mode为tap/hold");
    this.mode = mode;
    duration = mode.equals("tap") ? 1 : InputPolicy.ticks(ticks);
    oldSlot = mc().player.getInventory().selected;
  }

  private ItemStack stack() {
    return mc().player.getItemInHand(hand);
  }

  private int count() {
    int total = 0;
    for (int i = 0; i <= 40; i++) {
      var s = mc().player.getInventory().getItem(i);
      if (ItemStack.isSameItemSameTags(s, before)) total += s.getCount();
    }
    return total;
  }

  private int itemTotal() {
    int total = 0;
    for (int i = 0; i <= 40; i++) {
      var stack = mc().player.getInventory().getItem(i);
      if (stack.is(before.getItem())) total += stack.getCount();
    }
    return total;
  }

  @Override
  void tick() {
    if (++ticks > 400) {
      finish("部分完成：[USE_TIMEOUT] 已停止输入，未获得完整使用结果。");
      return;
    }
    if (phase == 0) {
      if (prepare != null) {
        int r = prepare.tick();
        if (r < 0) {
          finish("失败：" + prepare.error);
          return;
        }
        if (r == 0) return;
        if (hand == InteractionHand.MAIN_HAND)
          mc().player.getInventory().selected = prepare.readySlot;
        prepare = null;
      }
      if (!Actions.match(stack(), filter)) {
        if (filter.equals("*") || filter.isBlank()) {
          finish("失败：[EMPTY_HAND] 手中没有可使用物品，请先move_inventory。");
          return;
        }
        prepare =
            new InventoryMove(
                s -> Actions.match(s, filter),
                hand == InteractionHand.MAIN_HAND ? "hotbar" : "offhand",
                -1);
        return;
      }
      if (!baselineReady) {
        int r = initial.tick();
        if (r < 0) {
          finish("失败：" + initial.error);
          return;
        }
        if (r == 0) return;
        initial.close();
        baselineReady = true;
      }
      if (mc().screen != null || mc().player.containerMenu != mc().player.inventoryMenu) {
        finish("失败：[WRONG_CONTEXT] 使用物品前请关闭界面。");
        return;
      }
      if (stack().getItem() instanceof BlockItem) {
        finish("失败：[USE_PLACE_SEPARATE] 方块请用place_block，打开现有设备请用use_block。");
        return;
      }
      before = stack().copy();
      beforeCount = count();
      beforeItemTotal = itemTotal();
      started = System.nanoTime();
      usedTarget = mc().player.pick(5, 1, true).getLocation();
      mc().gameMode.useItem(mc().player, hand);
      usingSeen = mc().player.isUsingItem();
      if (usingSeen) {
        lease = PlayerControls.generation;
        PlayerControls.holdFood();
        phase = 1;
      } else phase = 2;
      return;
    }
    Actions.status =
        "使用物品 · " + (phase == 1 ? "维持 " + held + " / " + duration + "刻" : "已释放，核对实际结果");
    if (phase == 1) {
      expectedUseSeen |=
          mc().player.isUsingItem()
              && mc().player.getUsedItemHand() == hand
              && ItemStack.isSameItemSameTags(mc().player.getUseItem(), before);
      boolean changed = !stack().is(before.getItem()) || stack().getCount() < before.getCount();
      if (mc().screen != null) {
        finish("部分完成：[INPUT_INTERRUPTED] 使用期间界面改变，已释放。");
        return;
      }
      if (changed || !mc().player.isUsingItem() || ++held >= duration) {
        releaseUse();
        phase = 2;
        return;
      }
      if (lease != PlayerControls.generation) {
        finish("部分完成：[INPUT_INTERRUPTED] 持续使用已中断，未重新按住。");
        return;
      }
      return;
    }
    if (mc().screen != null) {
      releaseUse();
      finish(
          "输入已执行并释放：已打开 "
              + mc().screen.getTitle().getString()
              + " ("
              + mc().screen.getClass().getName()
              + ")；请inspect_gui/quest_book_state核对，界面打开不代表奖励或生产完成，不重复使用。");
      return;
    }
    int r = verify.tick();
    if (r < 0) {
      finish("部分完成：" + verify.error + "；输入已释放，效果未知。");
      return;
    }
    if (r == 0) return;
    int after = count();
    String id = BuiltInRegistries.ITEM.getKey(before.getItem()).toString();
    verify.close();
    var facts = new com.google.gson.JsonObject();
    facts.addProperty("item", id);
    facts.addProperty("count_before", beforeCount);
    facts.addProperty("count_after", after);
    facts.addProperty("using_seen", usingSeen);
    facts.addProperty("held_ticks", held);
    facts.addProperty("input_released", true);
    boolean projectileSeen =
        EntitySpawnEvidence.ownedProjectile(
            projectileTypes(before.getItem()), mc().player.getId(), started);
    boolean stateChanged =
        before.getItem() instanceof CrossbowItem
            && !CrossbowItem.isCharged(before)
            && stack().is(before.getItem())
            && CrossbowItem.isCharged(stack());
    boolean sustained =
        expectedUseSeen
            && held >= duration
            && (before.getItem() instanceof ShieldItem || before.getItem() instanceof SpyglassItem);
    facts.addProperty("owned_projectile_seen", projectileSeen);
    facts.addProperty("held_item_state_changed", stateChanged);
    boolean consumed =
        FieldProof.consumed(
            before.isDamageableItem(), beforeCount, after, beforeItemTotal, itemTotal());
    facts.addProperty("consumption_observed", consumed);
    facts.addProperty("item_type_total_before", beforeItemTotal);
    facts.addProperty("item_type_total_after", itemTotal());
    facts.addProperty("crossbow_loaded", stateChanged);
    facts.addProperty("sustained_use_observed", sustained);
    facts.addProperty("effect_scope", "使用/装填/消耗证据；命中、伤害和特殊效果需另行观察");
    boolean vehicle =
        before.getItem() instanceof BoatItem
            && EntitySpawnEvidence.placedEntity(
                before,
                usedTarget,
                started,
                Math.max(0, beforeCount - after),
                mc().player.isCreative());
    facts.addProperty("new_vehicle_spawn_correlated", vehicle);
    boolean confirmed =
        (consumed && !(before.getItem() instanceof BoatItem))
            || vehicle
            || projectileSeen
            || stateChanged
            || sustained;
    finish(
        new ActionResult(
                confirmed ? ActionResult.Status.verified : ActionResult.Status.unverified,
                confirmed ? ActionResult.Code.NONE : ActionResult.Code.USE_UNCONFIRMED,
                "已核对指定物品的使用证据与同步背包",
                facts)
            .json());
  }

  private static java.util.Set<net.minecraft.world.entity.EntityType<?>> projectileTypes(
      Item item) {
    var arrow = net.minecraft.world.entity.EntityType.ARROW;
    var spectral = net.minecraft.world.entity.EntityType.SPECTRAL_ARROW;
    if (item instanceof CrossbowItem)
      return java.util.Set.of(
          arrow, spectral, net.minecraft.world.entity.EntityType.FIREWORK_ROCKET);
    if (item instanceof BowItem) return java.util.Set.of(arrow, spectral);
    if (item instanceof TridentItem)
      return java.util.Set.of(net.minecraft.world.entity.EntityType.TRIDENT);
    if (item instanceof FishingRodItem)
      return java.util.Set.of(net.minecraft.world.entity.EntityType.FISHING_BOBBER);
    if (item instanceof SnowballItem)
      return java.util.Set.of(net.minecraft.world.entity.EntityType.SNOWBALL);
    if (item instanceof EggItem) return java.util.Set.of(net.minecraft.world.entity.EntityType.EGG);
    if (item instanceof EnderpearlItem)
      return java.util.Set.of(net.minecraft.world.entity.EntityType.ENDER_PEARL);
    if (item instanceof ThrowablePotionItem)
      return java.util.Set.of(net.minecraft.world.entity.EntityType.POTION);
    if (item instanceof ExperienceBottleItem)
      return java.util.Set.of(net.minecraft.world.entity.EntityType.EXPERIENCE_BOTTLE);
    return java.util.Set.of();
  }

  private void releaseUse() {
    PlayerControls.release();
    if (mc().player != null && mc().gameMode != null && mc().player.isUsingItem())
      mc().gameMode.releaseUsingItem(mc().player);
  }

  @Override
  void stop() {
    releaseUse();
    if (prepare != null) prepare.stop();
    if (mc().player != null) mc().player.getInventory().selected = oldSlot;
    super.stop();
  }

  @Override
  String interrupted() {
    return "使用物品已停止并释放；已经发生的消耗/释放效果请核对。";
  }
}
