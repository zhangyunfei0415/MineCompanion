package dev.mibao.companion;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

/** Eat one ordinary food, then verify a later full inventory and server food update. */
public final class EatJob extends Actions.Job {
  private final InventoryMove prepare;
  private final InventorySession verify = new InventorySession(false);
  private final int oldSlot;
  private int phase, count, food, usingTicks;
  private long vitals, lease;
  private String fingerprint;
  private boolean used, alwaysEdible;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public EatJob(String filter, Consumer<String> done) {
    super(done);
    oldSlot = mc().player.getInventory().selected;
    prepare =
        InventoryMove.ensure(
            s ->
                !s.isEmpty()
                    && s.getFoodProperties(mc().player) != null
                    && (!filter.equals("*") || ControlPolicy.FOOD.contains(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString()))
                    && Actions.match(s, filter));
  }

  @Override
  void tick() {
    Actions.status = "进食 · " + (phase == 0 ? "准备快捷栏" : phase == 1 ? "正常使用食物" : "核对服务器背包与饱食度");
    if (++ticks > 240) {
      finish("失败：进食12秒内未完成核验；请检查物品和服务器状态。");
      return;
    }
    if (phase == 0) {
      int r = prepare.tick();
      if (r < 0) {
        finish("失败：" + prepare.error);
        return;
      }
      if (r == 0) return;
      mc().player.getInventory().selected = prepare.readySlot;
      var stack = mc().player.getMainHandItem();
      var properties = stack.getFoodProperties(mc().player);
      alwaysEdible = properties != null && properties.canAlwaysEat();
      if (mc().player.getFoodData().getFoodLevel() >= 20 && !alwaysEdible) {
        finish("[FOOD_NOT_NEEDED] 饱食度已满，当前食物无需进食。");
        return;
      }
      fingerprint = WorldSense.fingerprint(stack);
      PlayerEvidence.begin();
      count = PlayerEvidence.total(PlayerEvidence.actual(fingerprint));
      food = mc().player.getFoodData().getFoodLevel();
      vitals = InventoryEvidence.vitalsRevision;
      lease = PlayerControls.generation;
      mc().gameMode.useItem(mc().player, InteractionHand.MAIN_HAND);
      if (!mc().player.isUsingItem()) {
        finish("失败：食物未进入正常使用状态，停止输入。");
        return;
      }
      PlayerControls.holdFood();
      phase = 1;
      return;
    }
    if (phase == 1) {
      usingTicks++;
      used |= mc().player.isUsingItem();
      int current = PlayerEvidence.total(PlayerEvidence.actual(fingerprint));
      if (current < count || (used && !mc().player.isUsingItem())) {
        PlayerControls.release();
        mc().gameMode.releaseUsingItem(mc().player);
        phase = 2;
        return;
      }
      if (lease != PlayerControls.generation
          || mc().screen != null
          || mc().player.getInventory().selected != prepare.readySlot) {
        finish("部分完成：进食输入被中断，请重新观察；没有确认吃完。");
        return;
      }
      if (usingTicks > 80) {
        finish("失败：食物未正常使用或消耗。");
        return;
      }
      return;
    }
    int r = verify.tick();
    if (r < 0) {
      finish("失败：" + verify.error);
      return;
    }
    if (r == 0) return;
    boolean consumedAlwaysEdible = alwaysEdible
        && food == 20
        && PlayerEvidence.total(PlayerEvidence.actual(fingerprint)) == count - 1
        && PlayerEvidence.total(PlayerEvidence.confirmed(fingerprint)) == count - 1;
    if (consumedAlwaysEdible || ControlPolicy.ate(
        count,
        PlayerEvidence.total(PlayerEvidence.actual(fingerprint)),
        PlayerEvidence.total(PlayerEvidence.confirmed(fingerprint)),
        food,
        mc().player.getFoodData().getFoodLevel(),
        InventoryEvidence.food,
        true,
        InventoryEvidence.vitalsRevision > vitals)) {
      verify.close();
      finish("成功：服务器确认消耗1件食物，饱食度 " + food + " → " + InventoryEvidence.food + "。");
    } else if (ticks > 180) finish("部分完成：已有背包快照，但消耗与饱食度未取得共同证明，请检查现场。");
  }

  @Override
  void stop() {
    PlayerControls.release();
    if (mc().player != null && mc().gameMode != null && mc().player.isUsingItem())
      mc().gameMode.releaseUsingItem(mc().player);
    prepare.stop();
    if (mc().player != null) mc().player.getInventory().selected = oldSlot;
    super.stop();
  }

  @Override
  String interrupted() {
    return "进食已停止；已发生的消耗不回滚，请核对背包和饱食度。";
  }
}
