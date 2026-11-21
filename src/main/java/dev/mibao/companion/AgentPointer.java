package dev.mibao.companion;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Window-local render/poll bridge. It never moves or presses the real OS mouse. */
public final class AgentPointer {
  private static final PointerState state = new PointerState();
  private static final PointerState.HoverProbe probe = new PointerState.HoverProbe();
  private static double physicalX, physicalY;
  public static boolean xHookSeen, yHookSeen;

  public static boolean active() {
    var mc = Minecraft.getInstance();
    return Agent.enabled
        && !Agent.paused
        && Store.config.fullGameControl
        && !Cooperation.yielding()
        && !LocalDialogue.editing
        && !Panel.managing(mc.screen)
        && state.active(
            mc.screen,
            Agent.epoch,
            mc.getWindow().getGuiScaledWidth(),
            mc.getWindow().getGuiScaledHeight(),
            System.nanoTime());
  }

  public static void begin(double x, double y) {
    var mc = Minecraft.getInstance();
    state.begin(
        mc.screen,
        Agent.epoch,
        mc.getWindow().getGuiScaledWidth(),
        mc.getWindow().getGuiScaledHeight(),
        x,
        y,
        System.nanoTime());
    var px = new double[1];
    var py = new double[1];
    org.lwjgl.glfw.GLFW.glfwGetCursorPos(mc.getWindow().getWindow(), px, py);
    physicalX = px[0];
    physicalY = py[0];
  }

  public static void move(double x, double y) {
    state.move(x, y, System.nanoTime());
  }

  public static void beat() {
    state.beat(System.nanoTime());
  }

  public static void button(int button, boolean down) {
    state.button(button, down);
  }

  public static void clear() {
    state.clear();
  }

  public static boolean probeActive() {
    var mc = Minecraft.getInstance();
    return Agent.enabled
        && !Agent.paused
        && !Cooperation.yielding()
        && !LocalDialogue.editing
        && !Panel.managing(mc.screen)
        && probe.active(
            mc.screen,
            Agent.epoch,
            mc.getWindow().getGuiScaledWidth(),
            mc.getWindow().getGuiScaledHeight(),
            System.nanoTime());
  }

  public static void beginProbe(double x, double y) {
    var mc = Minecraft.getInstance();
    if (mc.screen == null || Panel.managing(mc.screen)) {
      throw new IllegalArgumentException("[WRONG_CONTEXT] 需要非管理类游戏GUI界面");
    }
    var px = new double[1];
    var py = new double[1];
    org.lwjgl.glfw.GLFW.glfwGetCursorPos(mc.getWindow().getWindow(), px, py);
    probe.begin(
        mc.screen,
        Agent.epoch,
        mc.getWindow().getGuiScaledWidth(),
        mc.getWindow().getGuiScaledHeight(),
        x,
        y,
        px[0],
        py[0],
        System.nanoTime());
  }

  public static void endProbe() {
    probe.clear();
  }

  public static void intervene() {
    if (probeActive() && !WatchMode.active()) endProbe();
    if (!active() || WatchMode.active()) return;
    clear();
    if (Actions.job instanceof GameInputJob)
      Actions.job.finish("输入已释放：人工鼠标操作介入，请重新观察。");
  }

  public static void tick() {
    if (WatchMode.active()) return;
    var mc = Minecraft.getInstance();
    if (probeActive() || active()) {
      var px = new double[1];
      var py = new double[1];
      org.lwjgl.glfw.GLFW.glfwGetCursorPos(mc.getWindow().getWindow(), px, py);
      if (probeActive()) {
        probe.checkPhysicalMoved(px[0], py[0]);
      }
      if (active() && Math.abs(px[0] - physicalX) + Math.abs(py[0] - physicalY) > 1) {
        clear();
        Cooperation.human("人工移动鼠标，结束虚拟指针手势", false);
        if (Actions.job instanceof GameInputJob) Actions.job.finish("输入已释放：人工鼠标介入，请重新观察。");
      }
    }
  }

  public static double mouseX(double real) {
    xHookSeen = true;
    if (active()) {
      return state.x()
          * Minecraft.getInstance().getWindow().getScreenWidth()
          / Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }
    if (probeActive()) {
      return probe.x()
          * Minecraft.getInstance().getWindow().getScreenWidth()
          / Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }
    return real;
  }

  public static double mouseY(double real) {
    yHookSeen = true;
    if (active()) {
      return state.y()
          * Minecraft.getInstance().getWindow().getScreenHeight()
          / Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }
    if (probeActive()) {
      return probe.y()
          * Minecraft.getInstance().getWindow().getScreenHeight()
          / Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }
    return real;
  }

  public static boolean mouseButton(boolean real, int button) {
    return active() ? state.down(button) : real;
  }

  public static JsonObject view() {
    var o = new JsonObject();
    o.addProperty("active", active());
    o.addProperty("probe_active", probeActive());
    o.addProperty("x", active() ? state.x() : probeActive() ? probe.x() : 0);
    o.addProperty("y", active() ? state.y() : probeActive() ? probe.y() : 0);
    o.addProperty("left", state.down(0));
    o.addProperty("right", state.down(1));
    o.addProperty("render_hook_seen", xHookSeen && yHookSeen);
    return o;
  }

  public static void render(GuiGraphics g) {
    boolean full = active();
    boolean probing = !full && probeActive();
    if ((!full && !probing) || !Store.config.agentPointerVisible) return;
    int x = (int) (full ? state.x() : probe.x()), y = (int) (full ? state.y() : probe.y());
    g.pose().pushPose();
    g.pose().translate(0, 0, 1000);
    int color = full ? 0xff68e9e2 : 0xff38f2e6;
    g.fill(x - 5, y - 1, x + 6, y + 1, color);
    g.fill(x - 1, y - 5, x + 1, y + 6, color);
    g.drawString(Minecraft.getInstance().font, full ? "AI" : "?", x + 7, y + 4, color, false);
    g.pose().popPose();
  }
}
