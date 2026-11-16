package dev.mibao.companion;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

/** Normal nearby right-click; does not chase, attack, guess trades or read server internals. */
public final class EntityUseJob extends Actions.Job {
  private final int id;
  private final InteractionHand hand;
  private final AimGate aim = new AimGate();
  private boolean issued;
  private InteractionOutcome evidence;
  private net.minecraft.world.item.ItemStack heldBefore;
  private int heldSlot;
  private java.util.UUID targetUuid;
  private String targetBefore;

  public EntityUseJob(int id, String hand, Consumer<String> done) {
    super(done);
    this.id = id;
    var target = Minecraft.getInstance().level.getEntity(id);
    if (target == null || !target.isAlive())
      throw new IllegalArgumentException("[ENTITY_NOT_FOUND] 目标实体已失效，请重新inspect_scene");
    targetUuid = target.getUUID();
    if (!hand.equals("main") && !hand.equals("off"))
      throw new IllegalArgumentException("hand为main/off");
    this.hand = hand.equals("off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
  }

  @Override
  void tick() {
    var mc = Minecraft.getInstance();
    if (++ticks > 100) {
      finish("失败：实体交互超时");
      return;
    }
    if (issued) {
      evidence.tick();
      var target = mc.level.getEntity(id);
      boolean mounted =
          mc.player.getVehicle() != null && mc.player.getVehicle().getUUID().equals(targetUuid);
      boolean targetChanged =
          target != null
              && target.getUUID().equals(targetUuid)
              && !clientState(target).equals(targetBefore);
      if (mounted) {
        finish("成功：已观察到正常骑乘目标实体；控制与下车用vehicle_control");
        return;
      }
      if (mc.player.containerMenu != mc.player.inventoryMenu) {
        if (MenuContext.synchronizedNow()) finish("成功：实体交互已打开并取得同步菜单；请inspect_gui读取报价或槽位");
      } else if (evidence.ready()) {
        var current = mc.player.getInventory().getItem(heldSlot);
        boolean consumed =
            PlayerEvidence.slotConfirmed(heldSlot)
                && net.minecraft.world.item.ItemStack.matches(
                    current, PlayerEvidence.inventorySlot(heldSlot))
                && (current.isEmpty()
                    || net.minecraft.world.item.ItemStack.isSameItemSameTags(current, heldBefore))
                && current.getCount() < heldBefore.getCount();
        finish(
            (evidence.confirmed() || consumed || targetChanged
                    ? "成功："
                    : "部分完成：[ENTITY_USE_UNCONFIRMED] ")
                + "实体交互仅派发一次；"
                + evidence.detail()
                + (consumed ? "；指定手物品消耗已由服务器确认，繁殖/驯服等最终效果继续观察" : "")
                + (targetChanged ? "；目标可见同步状态已改变：" + clientState(target) : ""));
      }
      return;
    }
    if (mc.screen != null || mc.player.containerMenu != mc.player.inventoryMenu) {
      finish("失败：先正常退出菜单");
      return;
    }
    var e = mc.level.getEntity(id);
    if (e == null || !e.isAlive() || e == mc.player || !e.getUUID().equals(targetUuid)) {
      finish("失败：实体已失效");
      return;
    }
    var eye = mc.player.getEyePosition();
    double distance = MeleeOps.distance(eye, e.getBoundingBox());
    double reach = mc.player.getEntityReach();
    if (distance > reach) {
      finish("失败：[ENTITY_OUT_OF_REACH] eye_to_aabb=" + distance + " reach=" + reach + "；请先接近");
      return;
    }
    var box = e.getBoundingBox();
    net.minecraft.world.phys.Vec3 point = null;
    for (double height :
        new double[] {
          Math.max(.01, Math.min(.99, (eye.y - box.minY) / box.getYsize())), .5, .8, .2
        }) {
      var candidate =
          new net.minecraft.world.phys.Vec3(
              Math.max(box.minX + .01, Math.min(box.maxX - .01, eye.x)),
              box.minY + box.getYsize() * height,
              Math.max(box.minZ + .01, Math.min(box.maxZ - .01, eye.z)));
      var obstruction =
          mc.level.clip(
              new net.minecraft.world.level.ClipContext(
                  eye,
                  candidate,
                  net.minecraft.world.level.ClipContext.Block.COLLIDER,
                  net.minecraft.world.level.ClipContext.Fluid.NONE,
                  mc.player));
      if (eye.distanceTo(candidate) <= reach
          && obstruction.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
        point = candidate;
        break;
      }
    }
    if (point == null) {
      finish("失败：[ENTITY_OCCLUDED] 实体采样点被遮挡或超出范围；eye_to_aabb=" + distance);
      return;
    }
    if (!aim.ready(point)) return;
    evidence = InteractionOutcome.begin();
    heldBefore = mc.player.getItemInHand(hand).copy();
    targetUuid = e.getUUID();
    targetBefore = clientState(e);
    heldSlot = hand == InteractionHand.MAIN_HAND ? mc.player.getInventory().selected : 40;
    var result =
        mc.gameMode.interactAt(
            mc.player, e, new net.minecraft.world.phys.EntityHitResult(e, point), hand);
    if (!result.consumesAction()) mc.gameMode.interact(mc.player, e, hand);
    mc.player.swing(hand);
    issued = true;
  }

  private static String clientState(net.minecraft.world.entity.Entity entity) {
    if (entity instanceof net.minecraft.world.entity.animal.Sheep sheep)
      return "sheared=" + sheep.isSheared() + ",color=" + sheep.getColor().getName();
    if (entity instanceof net.minecraft.world.entity.TamableAnimal pet)
      return "tamed="
          + pet.isTame()
          + ",sitting="
          + pet.isInSittingPose()
          + ",owner="
          + pet.getOwnerUUID();
    if (entity instanceof net.minecraft.world.entity.animal.horse.AbstractHorse horse)
      return "tamed=" + horse.isTamed() + ",saddled=" + horse.isSaddled();
    return "no_special_client_state";
  }
}
