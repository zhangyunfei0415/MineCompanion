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
  private long lease;
  private boolean usingSeen;
  private ItemStack before;
  private final java.util.Set<java.util.UUID> projectiles = new java.util.HashSet<>();

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
      if (!s.isEmpty() && s.is(before.getItem())) total += s.getCount();
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
      for (var entity : mc().level.getEntities(mc().player, mc().player.getBoundingBox().inflate(32),
          e -> e instanceof net.minecraft.world.entity.projectile.Projectile)) projectiles.add(entity.getUUID());
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
          "输入已执行并释放：已打开 " + mc().screen.getTitle().getString()
              + " (" + mc().screen.getClass().getName()
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
    boolean projectileSeen = !mc().level.getEntities(mc().player, mc().player.getBoundingBox().inflate(48),
        e -> e instanceof net.minecraft.world.entity.projectile.Projectile projectile
            && projectile.getOwner() == mc().player && !projectiles.contains(e.getUUID())).isEmpty();
    boolean stateChanged = stack().is(before.getItem()) && !ItemStack.isSameItemSameTags(before, stack());
    boolean sustained = usingSeen && held >= duration && before.getItem() instanceof ShieldItem;
    facts.addProperty("owned_projectile_seen", projectileSeen);
    facts.addProperty("held_item_state_changed", stateChanged);
    boolean confirmed = after < beforeCount || projectileSeen || stateChanged || sustained;
    finish(new ActionResult(confirmed ? ActionResult.Status.verified : ActionResult.Status.unverified,
        confirmed ? ActionResult.Code.NONE : ActionResult.Code.USE_UNCONFIRMED,
        "服务器背包已核对，物品特殊效果按现场继续观察", facts).json());
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
