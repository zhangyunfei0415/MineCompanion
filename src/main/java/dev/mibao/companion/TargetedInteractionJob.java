package dev.mibao.companion;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;

/** One normal face-specific action; left tap never continues mining. */
public final class TargetedInteractionJob extends Actions.Job {
  private final BlockPos pos;
  private final Direction face;
  private final String item;
  private final boolean left, sneak;
  private final InteractionHand hand;
  private final Navigator nav = new Navigator();
  private final AimGate aim = new AimGate();
  private final SneakHold sneakHold = new SneakHold();
  private InventoryMove prepare;
  private InteractionOutcome outcome;
  private int stage, delay;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public TargetedInteractionJob(
      BlockPos pos,
      String face,
      String item,
      boolean left,
      boolean sneak,
      String hand,
      Consumer<String> done) {
    this(pos, face, item, left, sneak, hand, 3, done);
  }

  public TargetedInteractionJob(
      BlockPos pos,
      String face,
      String item,
      boolean left,
      boolean sneak,
      String hand,
      int stanceRadius,
      Consumer<String> done) {
    super(done);
    this.pos = pos;
    this.face = Direction.byName(face.equals("top") ? "up" : face);
    if (this.face == null) throw new IllegalArgumentException("face须为六个方向或top");
    this.item = item;
    this.left = left;
    this.sneak = sneak;
    if (!hand.equals("main") && !hand.equals("off"))
      throw new IllegalArgumentException("hand为main/off");
    this.hand = hand.equals("off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    nav.exactStance(true).maxRadius(stanceRadius);
  }

  @Override
  void tick() {
    Actions.status = stage == 0 ? "背包准备 · 定向交互" : stage == 1 ? "前往目标并瞄准指定面" : "界面交互 · 核对可复核证据";
    if (++ticks > 1400) {
      finish("失败：定向交互超时；已发动作不重放");
      return;
    }
    if (stage == 0) {
      if (!InventorySession.canClose() || mc().player.containerMenu != mc().player.inventoryMenu) {
        finish("失败：先收好鼠标并关闭菜单");
        return;
      }
      if (prepare == null) {
        if (item.isBlank() || item.equals("empty_hand")) {
          if (hand == InteractionHand.MAIN_HAND) {
            if (!HandPreparation.allowsHeld(pos) || item.equals("empty_hand"))
              prepare = InventoryMove.emptyHand();
          } else if (!mc().player.getOffhandItem().isEmpty())
            throw new IllegalArgumentException("副手需为空，或指定item");
        } else
          prepare =
              hand == InteractionHand.MAIN_HAND
                  ? InventoryMove.ensure(s -> Actions.match(s, item))
                  : new InventoryMove(s -> Actions.match(s, item), "offhand", -1);
      }
      if (prepare != null) {
        int r = prepare.tick();
        if (r < 0) {
          finish("失败：" + prepare.error);
          return;
        }
        if (r == 0) return;
        if (hand == InteractionHand.MAIN_HAND)
          mc().player.getInventory().selected = prepare.readySlot;
      }
      if (!nav.startForFace(pos, face)) {
        finish(navFailure());
        return;
      }
      stage = 1;
      return;
    }
    if (stage == 1) {
      int r = nav.tick();
      if (r < 0) {
        finish(navFailure());
        return;
      }
      if (r == 0) return;
      var hit = Reach.faceFrom(pos, mc().player.getEyePosition(), face);
      if (hit == null) {
        finish("失败：指定面不可见");
        return;
      }
      if (!aim.ready(hit.getLocation())) return;
      var state = mc().level.getBlockState(pos);
      if (left
          && !InteractionPolicy.leftTap(
              mc().gameMode.getPlayerMode() == GameType.SURVIVAL,
              mc().player.getMainHandItem().isEmpty(),
              hand == InteractionHand.MAIN_HAND ? "main" : "off",
              net.minecraft.core.registries.BuiltInRegistries.BLOCK
                  .getKey(state.getBlock())
                  .toString(),
              state.getDestroyProgress(mc().player, mc().level, pos))) {
        finish("失败：非破坏左键仅支持生存空主手Functional Storage抽屉");
        return;
      }
      outcome = InteractionOutcome.begin(pos);
      if (sneak) sneakHold.hold();
      Actions.look(hit.getLocation());
      MenuContext.expect(pos);
      if (left) {
        mc().gameMode.startDestroyBlock(pos, face);
        mc().gameMode.stopDestroyBlock();
      } else mc().gameMode.useItemOn(mc().player, hand, hit);
      stage = 2;
      return;
    }
    if (stage == 2) {
      outcome.tick();
      if (++delay < 8) return;
      if (mc().player.containerMenu != mc().player.inventoryMenu) {
        if (MenuContext.synchronizedNow()) {
          finish(
              !item.isBlank()
                      && !item.equals("empty_hand")
                      && mc().player.getItemInHand(hand).getItem()
                          instanceof net.minecraft.world.item.BlockItem
                  ? "部分完成：[MENU_OPENED_NOT_PLACED] 打开了目标菜单，不是放置成功；用place_block指定目标空格与支撑面"
                  : "成功：指定面交互打开已同步菜单；物资变化另核对");
          return;
        }
        if (delay > 100) finish("部分完成：菜单同步未完成，不重放");
        return;
      }
      stage = 3;
      sneakHold.release();
    } else {
      outcome.tick();
    }
    if (!outcome.ready()) return;
    String evidence = outcome.detail();
    finish(
        outcome.confirmed()
            ? "成功：指定面交互已取得可复核证据；目标="
                + targetText()
                + "；手持="
                + handText()
                + "；"
                + evidence
                + "；动作只发送一次，不重放"
            : "部分完成：未确认效果；目标="
                + targetText()
                + "；手持="
                + handText()
                + "；"
                + evidence
                + "；"
                + InteractionReceipt.readout(outcome.evidence())
                + "；动作只发送一次，不重放");
  }

  /** Target position plus the requested face, for receipts. */
  private String targetText() {
    return pos.toShortString() + "/" + face.getName();
  }

  /** Actual held item after the interaction, for receipts. */
  private String handText() {
    var stack = mc().player.getItemInHand(hand);
    String name =
        stack.isEmpty()
            ? "空手"
            : net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem())
                    .toString()
                + "×"
                + stack.getCount();
    return name + (hand == InteractionHand.OFF_HAND ? "(副手)" : "");
  }

  /** Navigation failures keep the target and the stance-candidate diagnosis. */
  private String navFailure() {
    String candidates = nav.candidateReport();
    return "失败：无法到达交互站位；目标="
        + targetText()
        + "；"
        + nav.error()
        + (candidates.isEmpty() || nav.error().contains("candidates=") ? "" : "；" + candidates);
  }

  @Override
  void stop() {
    sneakHold.release();
    nav.stop();
    if (prepare != null) prepare.stop();
    super.stop();
  }
}
