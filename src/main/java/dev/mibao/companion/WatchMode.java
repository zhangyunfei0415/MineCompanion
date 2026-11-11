package dev.mibao.companion;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Physical-input gate only. AI packets/KeyMappings still use their existing permission paths. */
public final class WatchMode {
  private static boolean watching;
  public static boolean keyHookSeen, mouseHookSeen, charHookSeen;

  public static boolean active() {
    return watching && Agent.enabled && !Cooperation.active();
  }

  public static void toggle() {
    if (Cooperation.active()) {
      Companion.notifyUser("协同模式保留人工操作；切回AI模式后可开启观看");
      return;
    }
    var mc = Minecraft.getInstance();
    if (!Agent.enabled) {
      Companion.notifyUser("先开启AI接管，再切换脱手观看");
      return;
    }
    if (!keyHookSeen) {
      Companion.notifyUser("物理输入桥尚未生效，不能安全启用观看；请查看日志并反馈");
      return;
    }
    watching = !watching;
    AgentPointer.clear();
    if (watching) {
      PlayerControls.isolatePhysical();
      mc.mouseHandler.releaseMouse();
    } else if (!LocalDialogue.editing && mc.screen == null) {
      mc.mouseHandler.grabMouse();
      PlayerControls.reapply();
    }
    Companion.notifyUser(watching ? "脱手观看：手动鼠标/游戏按键已隔离；本地对话与急停仍可用" : "已退出脱手观看");
  }

  public static void clear() {
    AgentPointer.clear();
    watching = false;
  }

  public static boolean blocksWorldMouse() {
    mouseHookSeen = true;
    return active() || LocalDialogue.editing;
  }

  public static boolean blockButtons() {
    var screen = Minecraft.getInstance().screen;
    return active() && !Panel.managing(screen) && !(screen instanceof TaskTimelineScreen);
  }

  public static boolean key(long window, int key, int scan, int action, int mods) {
    if (GameInputJob.injecting) return false;
    if (action == GLFW.GLFW_RELEASE) Cooperation.key(key, action);
    keyHookSeen = true;
    var mc = Minecraft.getInstance();
    if (mc.player == null || window != mc.getWindow().getWindow()) return false;
    if (Companion.QUICK_START.matches(key, scan)) {
      if (action == GLFW.GLFW_PRESS) startWatching();
      return true;
    }
    // Handle emergency control before an EditBox or a machine can consume its rebound key.
    if ((Agent.enabled || LocalDialogue.editing) && Companion.STOP.matches(key, scan)) {
      if (action == GLFW.GLFW_PRESS) Agent.stop("物理急停键");
      return true;
    }
    if (mc.screen instanceof TaskTimelineScreen) return false;
    if (Companion.DIALOGUE.matches(key, scan) && !(Panel.managing(mc.screen))) {
      if (action == GLFW.GLFW_PRESS) LocalDialogue.toggle();
      return true;
    }
    if ((Agent.enabled || LocalDialogue.editing) && Companion.WATCH.matches(key, scan)) {
      if (action == GLFW.GLFW_PRESS) toggle();
      return true;
    }
    if (LocalDialogue.editing && Companion.PANEL.matches(key, scan)) {
      if (action == GLFW.GLFW_PRESS) {
        LocalDialogue.close();
        mc.setScreen(new WorkbenchScreen());
      }
      return true;
    }
    if (LocalDialogue.editing) {
      if (action != GLFW.GLFW_RELEASE) LocalDialogue.key(key, scan, mods);
      return true;
    }
    if (Companion.COOP.matches(key, scan)) {
      if (action == GLFW.GLFW_PRESS) {
        if (!Agent.enabled) Agent.start();
        Cooperation.set(!Cooperation.active());
      }
      return true;
    }
    if (!Panel.managing(mc.screen)) Cooperation.key(key, action);
    if (!active() || Panel.managing(mc.screen)) return false;
    if (Companion.PANEL.matches(key, scan)) {
      if (action == GLFW.GLFW_PRESS) mc.setScreen(new WorkbenchScreen());
      return true;
    }
    if (Companion.HUD.matches(key, scan)) {
      if (action == GLFW.GLFW_PRESS) {
        Store.config.hud = !Store.config.hud;
        Store.save();
      }
      return true;
    }
    if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
      boolean itemScreen =
          mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
      boolean safe = itemScreen && InventorySession.canClose();
      Agent.manualPause();
      if (safe) {
        mc.player.closeContainer();
        GuiContext.invalidate();
      }
      if (mc.screen == null) mc.mouseHandler.grabMouse();
      return true;
    }
    return true;
  }

  public static boolean character(long window, int code, int mods) {
    if (GameInputJob.injecting) return false;
    charHookSeen = true;
    var mc = Minecraft.getInstance();
    if (mc.player == null || window != mc.getWindow().getWindow()) return false;
    if (LocalDialogue.editing) return LocalDialogue.character(code, mods);
    return active() && !(Panel.managing(mc.screen));
  }

  public static void startWatching() {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) return;
    LocalDialogue.close();
    if (!Agent.enabled) Agent.start();
    if (Cooperation.active()) Cooperation.set(false);
    if (Agent.paused) Agent.pause(false);
    if (!active()) toggle();
  }
}
