package dev.mibao.companion;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/** One jump; completion needs observed ascent and landing, never just a key pulse. */
public final class JumpJob extends Actions.Job {
  private double base, peak;
  private boolean started, airborne;
  private int pulse;

  public JumpJob(Consumer<String> done) {
    super(done);
  }

  @Override
  void tick() {
    var mc = Minecraft.getInstance();
    var p = mc.player;
    Actions.status = "跳跃 · " + (airborne ? "腾空，等待落地" : "等待地面并起跳");
    if (mc.screen != null || p.isInWater() || p.isInLava() || p.onClimbable()) {
      finish("失败：当前界面/液体/攀爬状态不适合普通跳跃");
      return;
    }
    if (++ticks > 80) {
      finish("部分完成：跳跃4秒内没有完整起跳落地证据；已释放");
      return;
    }
    if (!started) {
      if (!p.onGround()) {
        if (ticks > 20) finish("失败：1秒内未落地，不在空中重复按跳");
        return;
      }
      started = true;
      base = peak = p.getY();
      PlayerControls.hold(mc.options.keyJump);
      return;
    }
    peak = Math.max(peak, p.getY());
    if (!p.onGround() && peak - base > .08) airborne = true;
    if (++pulse >= 2) PlayerControls.motionState(false, false);
    if (airborne && p.onGround()) {
      finish(
          "成功：客户端观察到起跳与落地，高度变化 "
              + String.format(java.util.Locale.ROOT, "%.2f", peak - base)
              + " 格；不是服务端位置验收。");
      return;
    }
    if (!airborne && pulse > 10) finish("失败：跳跃输入后没有观察到起跳，可能头顶阻挡/状态限制；未重复按跳");
  }
}
