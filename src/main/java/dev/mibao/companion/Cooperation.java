package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Physical activity yields execution, never revokes the external controller lease. */
public final class Cooperation {
  private static final Set<Integer> keys = new HashSet<>();
  private static long until;
  private static boolean menuOwned, waiting, haveMouse;
  private static double x, y;
  private static String reason = "";

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public static boolean active() {
    return Agent.enabled && Store.config.controlMode.equals("COOP");
  }

  public static void set(boolean cooperative) {
    Store.config.controlMode = cooperative ? "COOP" : "AI";
    reset();
    WatchMode.clear();
    PlayerControls.release();
    Store.save();
    Companion.notifyUser(cooperative ? "协同模式：人工输入优先，任务与Harness连接保留" : "AI接管模式");
  }

  public static void reset() {
    keys.clear();
    until = 0;
    menuOwned = false;
    waiting = false;
    haveMouse = false;
    reason = "";
  }

  public static void human(String why, boolean transaction) {
    if (!active() || Panel.managing(mc().screen) || LocalDialogue.editing) return;
    until = System.currentTimeMillis() + 1200;
    waiting = true;
    reason = why;
    PlayerControls.release();
    for (var key : mc().options.keyMappings)
      if (keys.contains(key.getKey().getValue())) key.setDown(true);
    if (transaction) {
      menuOwned = true;
      Agent.cooperativeIntervention();
      GuiContext.invalidate();
    }
  }

  public static void key(int code, int action) {
    if (!active()) return;
    if (action == GLFW.GLFW_RELEASE) keys.remove(code);
    else keys.add(code);
    human("人工键盘操作", false);
  }

  public static void poll() {
    if (!active()) {
      haveMouse = false;
      return;
    }
    double[] a = {0}, b = {0};
    GLFW.glfwGetCursorPos(mc().getWindow().getWindow(), a, b);
    if (haveMouse
        && mc().screen == null
        && GLFW.glfwGetWindowAttrib(mc().getWindow().getWindow(), GLFW.GLFW_FOCUSED) == 1
        && (Math.abs(a[0] - x) + Math.abs(b[0] - y) > 1)) human("人工调整视角", false);
    x = a[0];
    y = b[0];
    haveMouse = true;
  }

  public static boolean yielding() {
    if (CooperativePolicy.yield(
        active(),
        waiting,
        !keys.isEmpty(),
        menuOwned,
        mc().screen != null,
        Store.config.cooperativeResume.equals("MANUAL"),
        System.currentTimeMillis(),
        until)) {
      for (var key : mc().options.keyMappings)
        if (keys.contains(key.getKey().getValue())) key.setDown(true);
      return true;
    }
    if (active()) {
      waiting = false;
      menuOwned = false;
    }
    return false;
  }

  public static void resume() {
    keys.clear();
    until = 0;
    menuOwned = false;
    waiting = false;
    haveMouse = false;
    Companion.notifyUser("协同：已交回AI，重新核实现状后继续");
  }

  public static JsonObject view() {
    var o = new JsonObject();
    o.addProperty("mode", !Agent.enabled ? "MANUAL" : active() ? "COOP" : "AI");
    o.addProperty("human_yield", yielding());
    o.addProperty("reason", reason);
    o.addProperty("resume", Store.config.cooperativeResume);
    return o;
  }

  private Cooperation() {}
}
