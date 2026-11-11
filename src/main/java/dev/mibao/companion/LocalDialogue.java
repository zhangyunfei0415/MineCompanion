package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Local overlay: never opens ChatScreen, sends chat packets or replaces the active machine Screen.
 */
public final class LocalDialogue {
  public record Line(long id, String role, String text) {}

  private static final Deque<Line> lines = new ArrayDeque<>();
  private static long sequence, lastMessage;
  private static EditBox box;
  public static boolean editing;
  private static int width, height, offset;
  private static String draft = "";

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public static void add(String role, String text) {
    if (text == null || text.isBlank() || text.startsWith("{") || text.startsWith("[")) return;
    lines.addLast(new Line(++sequence, role, Rules.clean(text, 1200)));
    lastMessage = System.currentTimeMillis();
    if (offset > 0) offset++;
    while (lines.size() > 100) lines.removeFirst();
  }

  public static List<Line> messages() {
    return List.copyOf(lines);
  }

  public static void reset() {
    lines.clear();
    editing = false;
    box = null;
    draft = "";
    offset = 0;
    ProcessHud.reset();
  }

  public static void toggle() {
    if (mc().player == null || Panel.managing(mc().screen)) return;
    if (!WatchMode.keyHookSeen) {
      Companion.notifyUser("本地输入桥未生效，请先检查启动日志");
      return;
    }
    editing = !editing;
    if (editing) {
      Store.config.localDialogue = true;
      layout();
      box.setFocused(true);
      PlayerControls.isolatePhysical();
      mc().mouseHandler.releaseMouse();
    } else close();
  }

  public static void close() {
    if (box != null) draft = box.getValue();
    editing = false;
    if (!WatchMode.active() && mc().screen == null) {
      mc().mouseHandler.grabMouse();
      PlayerControls.reapply();
    }
  }

  private static void layout() {
    int w = mc().getWindow().getGuiScaledWidth(), h = mc().getWindow().getGuiScaledHeight();
    var r = Hud.layout(w, h).dialogue();
    if (box == null || width != w || height != h) {
      if (box != null) draft = box.getValue();
      box =
          new EditBox(
              mc().font,
              r.x() + 6,
              r.bottom() - 28,
              Math.max(20, r.width() - 12),
              18,
              Component.literal("本地伙伴对话"));
      box.setMaxLength(2000);
      box.setValue(draft);
    }
    width = w;
    height = h;
    box.setX(r.x() + 6);
    box.setY(r.bottom() - 28);
    box.setWidth(Math.max(20, r.width() - 12));
    box.setFocused(editing);
  }

  public static boolean key(int key, int scan, int mods) {
    if (!editing) return false;
    layout();
    if (key == GLFW.GLFW_KEY_TAB) {
      close();
      mc().setScreen(new TaskTimelineScreen(null));
      return true;
    }
    if (key == GLFW.GLFW_KEY_ESCAPE) {
      close();
      return true;
    }
    if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
      String text = box.getValue().trim();
      box.setValue("");
      draft = "";
      offset = 0;
      if (!text.isBlank()) submit(text);
      return true;
    }
    VirtualInput.physicalText(() -> box.keyPressed(key, scan, mods));
    return true;
  }

  public static boolean character(int code, int mods) {
    if (!editing) return false;
    layout();
    for (char c : Character.toChars(code)) box.charTyped(c, mods);
    return true;
  }

  private static void submit(String text) {
    add("你", text);
    HarnessController.userMessage(text);
    switch (text) {
      case "/stop", "停止" -> Agent.stop("本地对话急停");
      case "/pause", "暂停" -> Agent.pause(true);
      case "/resume", "继续" -> Agent.pause(false);
      case "/watch" -> WatchMode.toggle();
      case "/ok" -> Hud.FEED.acknowledge();
      default -> {
        Hud.FEED.acknowledge();
        if (!Agent.enabled) Agent.start();
        Agent.submit(text, true);
      }
    }
  }

  public static boolean mouse(int button, int action) {
    if (!editing) return false;
    layout();
    double x = mc().mouseHandler.xpos() * width / mc().getWindow().getScreenWidth(),
        y = mc().mouseHandler.ypos() * height / mc().getWindow().getScreenHeight();
    if (action == GLFW.GLFW_PRESS) {
      if (ProcessHud.click(x, y, button)) return true;
      if (!Store.config.processDetailed && Store.config.hud && button == 0) {
        int sw = OverlayLayout.sidebarWidth(width, Store.config.hudWidth),
            sx = Store.config.hudLeft ? 8 : width - sw - 8,
            sy =
                Math.min(
                    Store.config.hudY, Math.max(8, Hud.layout(width, height).hudBottom() - 80));
        if (x >= sx && x < sx + sw && y >= sy + 18 && y < sy + 32) {
          ProcessHud.toggle();
          return true;
        }
      }
      var r = Hud.layout(width, height).dialogue();
      String a = "推理强度:" + PlanningPolicy.title(Store.config.planningEffort),
          b = "执行风格:" + (ExecutionStyle.fluent(Store.config.executionStyle) ? "快速" : "严谨");
      int split = r.x() + 7 + mc().font.width(a) + 12, end = split + mc().font.width(b) + 8;
      if (button == 0 && y >= r.bottom() - 11 && y < r.bottom() && x >= r.x() + 7 && x < end) {
        if (x < split)
          Store.config.planningEffort = PlanningPolicy.next(Store.config.planningEffort);
        else
          Store.config.executionStyle =
              ExecutionStyle.fluent(Store.config.executionStyle) ? "STRICT" : "FLUENT";
        Store.save();
        return true;
      }
      box.mouseClicked(x, y, button);
    }
    return true;
  }

  public static boolean scroll(double delta) {
    if (!editing) return false;
    layout();
    double mx = mc().mouseHandler.xpos() * width / mc().getWindow().getScreenWidth(),
        my = mc().mouseHandler.ypos() * height / mc().getWindow().getScreenHeight();
    if (ProcessHud.scroll(mx, my, delta)) return true;
    offset = Math.max(0, offset + (delta > 0 ? 1 : -1));
    return true;
  }

  public static void render(GuiGraphics g) {
    if (mc().player == null
        || !Store.config.localDialogue
        || mc().options.hideGui
        || mc().screen != null && !editing) return;
    layout();
    var r = Hud.layout(width, height).dialogue();
    int size = r.width(), x = r.x(), y = r.y();
    if (size < 100) return;
    boolean recent = System.currentTimeMillis() - lastMessage < 15_000;
    if (!editing && !recent) {
      if (WatchMode.active())
        g.drawString(
            mc().font,
            Companion.DIALOGUE.getTranslatedKeyMessage().getString() + " 本地交流",
            x + 7,
            r.bottom() - 10,
            0xff9cb9cf,
            false);
      return;
    }
    g.fill(x, y, r.right(), r.bottom(), 0x99101b29);
    // Keep styled wrapping intact; at most four visible lines regardless of message size.
    var wrapped = new ArrayList<net.minecraft.util.FormattedCharSequence>();
    for (var line : lines)
      wrapped.addAll(
          mc().font.split(Component.literal(line.role() + "：" + line.text()), size - 14));
    offset = Math.min(offset, Math.max(0, wrapped.size() - 1));
    int end = Math.max(0, wrapped.size() - offset), start = Math.max(0, end - (editing ? 3 : 2));
    int yy = y + 4;
    for (int i = start; i < end; i++) {
      g.drawString(mc().font, wrapped.get(i), x + 7, yy, 0xffe3eef7, false);
      yy += 10;
    }
    if (editing) {
      box.render(g, 0, 0, 0);
      String a = "推理强度:" + PlanningPolicy.title(Store.config.planningEffort),
          b = "执行风格:" + (ExecutionStyle.fluent(Store.config.executionStyle) ? "快速" : "严谨");
      g.drawString(mc().font, a, x + 7, r.bottom() - 9, 0xff73c9f5, false);
      g.drawString(
          mc().font, b, x + 7 + mc().font.width(a) + 12, r.bottom() - 9, 0xff73c9f5, false);
      int startX = x + 7 + mc().font.width(a) + 12 + mc().font.width(b) + 12;
      if (r.right() - startX > 70)
        g.drawString(
            mc().font,
            mc().font
                .plainSubstrByWidth(
                    "仅本地，不发公屏（公屏回复"
                        + (Store.config.publicReply ? "已单独开启" : "关闭")
                        + "）· Enter发送 · Esc收起",
                    r.right() - startX - 5),
            startX,
            r.bottom() - 9,
            0xffa4cddd,
            false);
    } else
      g.drawString(
          mc().font,
          Companion.DIALOGUE.getTranslatedKeyMessage().getString() + " 本地交流",
          x + 7,
          r.bottom() - 9,
          0xff9cb9cf,
          false);
  }
}
