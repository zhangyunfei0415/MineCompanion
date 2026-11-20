package dev.mibao.companion;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Crisp 1px-notched rounded surfaces, gradients, pills, switches, keycaps, and scrollbars. */
public final class UiDraw {
  private UiDraw() {}

  public static void fillRounded(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
    if (w <= 0 || h <= 0) return;
    if (w < 4 || h < 4) {
      g.fill(x, y, x + w, y + h, fill);
      return;
    }
    // Fill interior (1px-notched corners)
    g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    // Top & bottom border lines inset by 1px
    if (border != 0) {
      g.fill(x + 1, y, x + w - 1, y + 1, border);
      g.fill(x + 1, y + h - 1, x + w - 1, y + h, border);
      g.fill(x, y + 1, x + 1, y + h - 1, border);
      g.fill(x + w - 1, y + 1, x + w, y + h - 1, border);
    }
  }

  public static void fillCard(GuiGraphics g, int x, int y, int w, int h, UiTheme t) {
    fillRounded(g, x, y, w, h, t.cardBg(), t.border());
    if (w > 6 && h > 4) {
      int specular = t.light() ? 0x70ffffff : 0x12ffffff;
      g.fill(x + 2, y + 1, x + w - 2, y + 2, specular);
    }
  }

  public static void fillHGradient(
      GuiGraphics g, int x, int y, int w, int h, int leftColor, int rightColor) {
    if (w <= 0 || h <= 0) return;
    int steps = Math.max(1, Math.min(w, 32));
    for (int i = 0; i < steps; i++) {
      int x0 = x + (i * w) / steps;
      int x1 = x + ((i + 1) * w) / steps;
      if (x1 <= x0) continue;
      float ratio = steps == 1 ? 0f : (float) i / (float) (steps - 1);
      g.fill(x0, y, x1, y + h, UiTheme.lerpColor(leftColor, rightColor, ratio));
    }
  }

  public static int badgeWidth(Font font, String text) {
    return font.width(text) + 10;
  }

  public static int drawBadge(
      GuiGraphics g, Font font, int x, int y, String text, int fg, int bg, int border) {
    int w = badgeWidth(font, text);
    fillRounded(g, x, y, w, 13, bg, border);
    g.drawString(font, text, x + 5, y + 3, fg, false);
    return w;
  }

  public static void drawSwitch(
      GuiGraphics g,
      int x,
      int y,
      boolean on,
      boolean hovered,
      boolean danger,
      boolean enabled,
      UiTheme t) {
    int trackFill =
        !enabled
            ? t.inputBg()
            : on ? (danger ? t.danger() : t.accent()) : t.inputBg();
    int border =
        !enabled
            ? t.border()
            : hovered ? t.borderStrong() : (on ? (danger ? t.danger() : t.accent()) : t.borderStrong());
    fillRounded(g, x, y, 28, 14, trackFill, border);
    int knobX = on ? x + 16 : x + 2;
    int knobColor =
        !enabled
            ? t.textMuted()
            : on ? t.textOnAccent() : (hovered ? t.text() : t.textSecondary());
    fillRounded(g, knobX, y + 2, 10, 10, knobColor, 0);
  }

  public static int drawKeycap(GuiGraphics g, Font font, int x, int y, String keyText, UiTheme t) {
    int w = Math.max(20, font.width(keyText) + 12);
    fillRounded(g, x, y + 1, w, 15, t.border(), 0);
    fillRounded(g, x, y, w, 14, t.inputBg(), t.borderStrong());
    g.drawString(font, keyText, x + (w - font.width(keyText)) / 2, y + 3, t.accent(), false);
    return w;
  }

  public static void drawScrollbar(
      GuiGraphics g,
      int x,
      int y,
      int h,
      int scroll,
      int maxScroll,
      int viewH,
      boolean hovered,
      UiTheme t) {
    if (maxScroll <= 0 || h <= 16) return;
    fillRounded(g, x, y, 4, h, UiTheme.withAlpha(t.border(), 90), 0);
    int thumbH = Math.max(22, Math.min(h, (int) ((long) h * viewH / Math.max(1L, viewH + maxScroll))));
    int travel = Math.max(0, h - thumbH);
    int thumbY = y + (maxScroll == 0 ? 0 : (int) ((long) travel * scroll / maxScroll));
    int thumbColor = hovered ? t.accent() : t.borderStrong();
    fillRounded(g, x, thumbY, 4, thumbH, thumbColor, 0);
  }
}
