package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Native foreground reading pane. Input is opt-in through the existing Home local-input mode. */
public final class ProcessHud {
  private record Event(String id, String kind, String title, String detail) {}

  private record Hit(String id, int top, int bottom) {}

  private static List<Event> frozen;
  private static String scope = "";
  private static final Set<String> expanded = new HashSet<>();
  private static final List<Hit> hits = new ArrayList<>();
  private static int offset, maxOffset, x, y, w, h;
  private static String anchor = "";
  private static final Map<String, List<net.minecraft.util.FormattedCharSequence>> wraps =
      new HashMap<>();

  private static List<net.minecraft.util.FormattedCharSequence> wrap(String text, int width) {
    if (wraps.size() > 512) wraps.clear();
    return wraps.computeIfAbsent(
        width + ":" + text,
        k -> Minecraft.getInstance().font.split(Component.literal(text), Math.max(50, width)));
  }

  private static List<Event> events() {
    var list = new ArrayList<Event>();
    var source = Store.data.taskEvents;
    for (int i = source.size() - 1; i >= Math.max(0, source.size() - 80); i--) {
      var e = source.get(i);
      list.add(
          new Event(
              e.id,
              e.kind.equals("stream") && !StreamProgress.active(e.id) ? "stream_error" : e.kind,
              e.title,
              e.detail));
    }
    return list;
  }

  public static void reset() {
    offset = 0;
    maxOffset = 0;
    frozen = null;
    anchor = "";
    hits.clear();
  }

  public static void toggle() {
    Store.config.processDetailed = !Store.config.processDetailed;
    reset();
    Store.save();
  }

  public static void render(
      GuiGraphics g, int px, int py, int width, int bottom, StatusFeed.View status) {
    var mc = Minecraft.getInstance();
    if (!scope.equals(Store.scope())) {
      scope = Store.scope();
      reset();
    }
    x = px;
    y = py;
    w = width;
    h = Math.max(60, bottom - py);
    UiTheme t = UiTheme.current();
    int fg = t.text(), muted = t.textSecondary(), accent = t.accent();
    int bg = t.hudBg(Store.config.hudOpacity);
    UiDraw.fillRounded(g, x, y, w, h, bg, t.border());
    UiDraw.fillHGradient(g, x + 1, y, w - 2, 2, accent, t.accentAlt());
    g.drawString(
        mc.font,
        mc.font.plainSubstrByWidth(status.name + " · 工作过程", w - 18),
        x + 9,
        y + 8,
        accent,
        false);
    g.drawString(
        mc.font,
        mc.font.plainSubstrByWidth("详细 / 切为简洁 · " + Hud.controlLine(), w - 18),
        x + 9,
        y + 21,
        muted,
        false);
    g.drawString(
        mc.font, mc.font.plainSubstrByWidth(status.detail, w - 18), x + 9, y + 35, fg, false);
    if (h < 112) {
      hits.clear();
      maxOffset = 0;
      g.drawString(mc.font, "Home → Tab 查看完整过程", x + 9, y + h - 13, muted, false);
      return;
    }
    g.drawString(
        mc.font,
        mc.font.plainSubstrByWidth(
            status.task.isBlank() ? "任务目标见下方记录" : "目标 · " + status.task, w - 18),
        x + 9,
        y + 47,
        muted,
        false);
    g.fill(x + 9, y + 61, x + w - 9, y + 62, t.border());
    var live = events();
    var list = frozen == null ? live : frozen;
    hits.clear();
    int cy = y + 69 - offset;
    g.enableScissor(x + 5, y + 66, x + w - 5, y + h - 24);
    if (list.isEmpty()) g.drawString(mc.font, "等待任务 · Home 本地下令", x + 9, cy, muted, false);
    for (var e : list) {
      int start = cy;
      int color =
          e.kind.equals("stream_error")
              ? t.danger()
              : e.kind.equals("result") ? t.success() : fg;
      g.drawString(
          mc.font,
          TaskTimeline.label(e.kind) + (e.kind.equals("stream") ? " · 实时" : ""),
          x + 9,
          cy,
          accent,
          false);
      cy += 12;
      var lines = wrap(e.title, w - 24);
      int n = expanded.contains(e.id) ? Math.min(60, lines.size()) : Math.min(3, lines.size());
      int from =
          e.kind.equals("stream") && !expanded.contains(e.id) ? Math.max(0, lines.size() - n) : 0;
      for (int i = 0; i < n; i++) {
        g.drawString(mc.font, lines.get(from + i), x + 9, cy, color, false);
        cy += 11;
      }
      if (lines.size() > n) {
        g.drawString(mc.font, "… 点击展开", x + 9, cy, muted, false);
        cy += 11;
      }
      if (expanded.contains(e.id) && !e.detail.isBlank())
        for (var line : wrap(e.detail, w - 24)) {
          g.drawString(mc.font, line, x + 9, cy, muted, false);
          cy += 11;
        }
      hits.add(new Hit(e.id, start + offset, cy + offset));
      cy += 10;
    }
    maxOffset = Math.max(0, cy + offset - (y + h - 25));
    offset = Math.min(offset, maxOffset);
    g.disableScissor();
    if (maxOffset > 0 && h > 110) {
      int viewH = Math.max(16, (y + h - 24) - (y + 66));
      UiDraw.drawScrollbar(g, x + w - 6, y + 66, viewH, offset, maxOffset, viewH, false, t);
    }
    boolean changed = frozen != null && !live.isEmpty() && !live.get(0).id.equals(anchor);
    String footer =
        frozen != null
            ? (changed ? "有新进展 · 点击回到最新" : "正在翻阅 · 点击回到最新")
            : LocalDialogue.editing ? "滚轮翻阅 · 点击展开 · Tab全览" : "Home 阅读 / 下令 · 文字非执行证明";
    if (changed) {
      UiDraw.fillRounded(
          g, x + 5, y + h - 19, w - 10, 15, UiTheme.withAlpha(accent, 48), UiTheme.withAlpha(accent, 120));
      g.drawString(
          mc.font,
          mc.font.plainSubstrByWidth("▼ " + footer, w - 18),
          x + 9,
          y + h - 15,
          accent,
          false);
    } else
      g.drawString(
          mc.font, mc.font.plainSubstrByWidth(footer, w - 18), x + 9, y + h - 15, muted, false);
  }

  private static boolean inside(double mx, double my) {
    return mx >= x && mx < x + w && my >= y && my < y + h;
  }

  public static boolean click(double mx, double my, int button) {
    if (!Store.config.hud || button != 0 || !Store.config.processDetailed || !inside(mx, my))
      return false;
    if (my < y + 32) {
      toggle();
      return true;
    }
    if (my > y + h - 24) {
      reset();
      return true;
    }
    for (var hit : hits)
      if (my + offset >= hit.top && my + offset < hit.bottom) {
        if (!expanded.remove(hit.id)) expanded.add(hit.id);
        return true;
      }
    return true;
  }

  public static boolean scroll(double mx, double my, double amount) {
    if (!Store.config.hud || !Store.config.processDetailed || !inside(mx, my)) return false;
    if (amount < 0 && frozen == null) {
      frozen = events();
      anchor = frozen.isEmpty() ? "" : frozen.get(0).id;
    }
    offset = Math.max(0, Math.min(maxOffset, offset - (int) (amount * 28)));
    if (offset == 0) {
      frozen = null;
      anchor = "";
    }
    return true;
  }

  private ProcessHud() {}
}
