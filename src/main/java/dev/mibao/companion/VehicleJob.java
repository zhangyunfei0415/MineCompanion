package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Bounded mounted input, including normal sneak-to-dismount and observed position changes. */
public final class VehicleJob extends Actions.Job {
  private final String control;
  private final int duration;
  private Vec3 origin;
  private long generation;
  private boolean started;

  public VehicleJob(String control, int ticks, Consumer<String> done) {
    super(done);
    if (!Set.of("forward", "back", "left", "right", "jump", "sneak").contains(control))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] control为forward/back/left/right/jump/sneak");
    this.control = control;
    duration = InputPolicy.ticks(ticks);
  }

  void tick() {
    var mc = Minecraft.getInstance();
    if (mc.screen != null) { finish("部分完成：[SCREEN_BLOCKING] 骑乘输入中断"); return; }
    if (!started) {
      if (!mc.player.isPassenger()) { finish("失败：[NOT_MOUNTED] 当前没有骑乘，请先use_entity上车"); return; }
      origin = mc.player.position();
      generation = PlayerControls.generation;
      PlayerControls.hold(PlayerControls.motion(control));
      started = true;
    }
    if (generation != PlayerControls.generation) {
      finish("部分完成：[INPUT_INTERRUPTED] 骑乘输入已被中断");
      return;
    }
    if (++ticks < duration && mc.player.isPassenger()) return;
    PlayerControls.release();
    boolean dismounted = !mc.player.isPassenger();
    boolean moved = mc.player.position().distanceTo(origin) > .05;
    JsonObject evidence = state();
    evidence.addProperty("distance", mc.player.position().distanceTo(origin));
    evidence.addProperty("control", control);
    boolean verified = control.equals("sneak") ? dismounted : moved;
    finish(new ActionResult(verified ? ActionResult.Status.verified : ActionResult.Status.unverified,
        verified ? ActionResult.Code.NONE : ActionResult.Code.USE_UNCONFIRMED,
        "骑乘输入已释放，按实际位移或下车状态核对", evidence).json());
  }

  public static JsonObject state() {
    var player = Minecraft.getInstance().player;
    JsonObject result = new JsonObject();
    var vehicle = player.getVehicle();
    result.addProperty("mounted", vehicle != null);
    if (vehicle != null) {
      result.addProperty("entity_id", vehicle.getId());
      result.addProperty("uuid", vehicle.getUUID().toString());
      result.addProperty("type", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString());
    }
    return result;
  }
}
