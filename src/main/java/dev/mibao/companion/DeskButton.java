package dev.mibao.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Multi-variant interactive control used across the G4Neon Workbench and screens. */
public final class DeskButton extends Button {
  public enum Variant {
    DEFAULT,
    PRIMARY,
    DANGER,
    NAV,
    TOGGLE,
    SEGMENT,
    SWATCH,
    COLLAPSE
  }

  public boolean selected;
  private Variant variant = Variant.DEFAULT;
  private boolean checked;
  private boolean dangerTone;
  private String badge = "";
  private int badgeColor;
  private String subtext = "";
  private String swatchTheme = "";
  private int clipTop = -1, clipBottom = -1;

  public DeskButton(String text, int x, int y, int w, Runnable action) {
    this(text, x, y, w, 22, action);
  }

  public DeskButton(String text, int x, int y, int w, int h, Runnable action) {
    super(x, y, w, h, Component.literal(text), b -> action.run(), DEFAULT_NARRATION);
  }

  public DeskButton variant(Variant v) {
    this.variant = v;
    return this;
  }

  public DeskButton checked(boolean on) {
    this.checked = on;
    return this;
  }

  public DeskButton dangerTone(boolean d) {
    this.dangerTone = d;
    return this;
  }

  public DeskButton badge(String text, int color) {
    this.badge = text == null ? "" : text;
    this.badgeColor = color;
    return this;
  }

  public DeskButton subtext(String sub) {
    this.subtext = sub == null ? "" : sub;
    return this;
  }

  public DeskButton swatch(String themeId) {
    this.variant = Variant.SWATCH;
    this.swatchTheme = themeId == null ? "" : themeId;
    return this;
  }

  public DeskButton clip(int top, int bottom) {
    this.clipTop = top;
    this.clipBottom = bottom;
    return this;
  }

  private boolean inClip(double my) {
    return clipBottom <= clipTop || (my >= clipTop && my <= clipBottom);
  }

  @Override
  public boolean isMouseOver(double mx, double my) {
    return inClip(my) && super.isMouseOver(mx, my);
  }

  @Override
  protected boolean clicked(double mx, double my) {
    return inClip(my) && super.clicked(mx, my);
  }

  @Override
  protected void renderWidget(GuiGraphics g, int mx, int my, float delta) {
    UiTheme t = UiTheme.current();
    var f = Minecraft.getInstance().font;
    boolean hovered = isHoveredOrFocused() && active && inClip(my);
    int x = getX(), y = getY(), w = width, h = height;

    switch (variant) {
      case NAV -> renderNav(g, f, t, x, y, w, h, hovered);
      case TOGGLE -> renderToggle(g, f, t, x, y, w, h, hovered);
      case SEGMENT -> renderSegment(g, f, t, x, y, w, h, hovered);
      case SWATCH -> renderSwatch(g, f, t, x, y, w, h, hovered);
      case COLLAPSE -> renderCollapse(g, f, t, x, y, w, h, hovered);
      case PRIMARY, DANGER, DEFAULT -> renderButton(g, f, t, x, y, w, h, hovered);
    }
  }

  private void renderNav(
      GuiGraphics g,
      net.minecraft.client.gui.Font f,
      UiTheme t,
      int x,
      int y,
      int w,
      int h,
      boolean hovered) {
    int fill =
        selected
            ? UiTheme.withAlpha(t.accent(), t.light() ? 38 : 42)
            : hovered ? t.cardHover() : 0x00000000;
    int border = selected ? UiTheme.withAlpha(t.accent(), 140) : (hovered ? t.border() : 0);
    if (fill != 0 || border != 0) UiDraw.fillRounded(g, x, y, w, h, fill, border);
    if (selected) g.fill(x, y + 4, x + 3, y + h - 4, t.accent());
    int fg = !active ? t.textMuted() : selected ? t.text() : hovered ? t.text() : t.textSecondary();
    String s = f.plainSubstrByWidth(getMessage().getString(), Math.max(8, w - 16));
    g.drawString(f, s, x + 10, y + (h - 8) / 2, fg, false);
  }

  private void renderToggle(
      GuiGraphics g,
      net.minecraft.client.gui.Font f,
      UiTheme t,
      int x,
      int y,
      int w,
      int h,
      boolean hovered) {
    int fill = hovered ? t.cardHover() : t.inputBg();
    int border = hovered ? t.borderStrong() : t.border();
    UiDraw.fillRounded(g, x, y, w, h, fill, border);
    if (checked && dangerTone) g.fill(x, y + 3, x + 2, y + h - 3, t.danger());

    int rightReserve = 36;
    if (!badge.isEmpty()) {
      int bw = UiDraw.badgeWidth(f, badge);
      int bc = badgeColor == 0 ? t.accent() : badgeColor;
      UiDraw.drawBadge(
          g,
          f,
          x + w - rightReserve - bw - 4,
          y + (h - 13) / 2,
          badge,
          bc,
          UiTheme.withAlpha(bc, 34),
          UiTheme.withAlpha(bc, 110));
      rightReserve += bw + 10;
    }
    UiDraw.drawSwitch(
        g, x + w - 34, y + (h - 14) / 2, checked, hovered, dangerTone, active, t);

    int fg = !active ? t.textMuted() : t.text();
    String s = f.plainSubstrByWidth(getMessage().getString(), Math.max(12, w - rightReserve - 12));
    if (subtext.isEmpty() || h < 30) {
      g.drawString(f, s, x + 8, y + (h - 8) / 2, fg, false);
    } else {
      g.drawString(f, s, x + 8, y + 5, fg, false);
      String sub = f.plainSubstrByWidth(subtext, Math.max(12, w - rightReserve - 12));
      g.drawString(f, sub, x + 8, y + 17, t.textMuted(), false);
    }
  }

  private void renderSegment(
      GuiGraphics g,
      net.minecraft.client.gui.Font f,
      UiTheme t,
      int x,
      int y,
      int w,
      int h,
      boolean hovered) {
    int fill = selected ? t.accent() : hovered ? t.cardHover() : t.inputBg();
    int border = selected ? t.accent() : hovered ? t.borderStrong() : t.border();
    UiDraw.fillRounded(g, x, y, w, h, fill, border);
    int fg = !active ? t.textMuted() : selected ? t.textOnAccent() : t.textSecondary();
    String s = f.plainSubstrByWidth(getMessage().getString(), Math.max(8, w - 10));
    int tx = x + Math.max(4, (w - f.width(s)) / 2);
    g.drawString(f, s, tx, y + (h - 8) / 2, fg, false);
  }

  private void renderSwatch(
      GuiGraphics g,
      net.minecraft.client.gui.Font f,
      UiTheme t,
      int x,
      int y,
      int w,
      int h,
      boolean hovered) {
    UiTheme preview = UiTheme.of(swatchTheme);
    int fill =
        selected
            ? UiTheme.withAlpha(t.accent(), t.light() ? 34 : 38)
            : hovered ? t.cardHover() : t.inputBg();
    int border = selected ? t.accent() : hovered ? t.borderStrong() : t.border();
    UiDraw.fillRounded(g, x, y, w, h, fill, border);
    if (selected) g.fill(x, y + 4, x + 2, y + h - 4, t.accent());
    int px = x + 6, py = y + 5, ph = Math.max(14, h - 10), pw = 28;
    UiDraw.fillRounded(g, px, py, pw, ph, preview.windowBg(), preview.border());
    g.fill(px + 2, py + 5, px + 8, py + ph - 2, preview.railBg());
    g.fill(px + 9, py + 6, px + pw - 2, py + ph - 2, preview.cardBg());
    UiDraw.fillHGradient(g, px + 2, py + 2, 12, 2, preview.accent(), preview.auroraMid());
    UiDraw.fillHGradient(g, px + 14, py + 2, 12, 2, preview.auroraMid(), preview.accentAlt());
    g.fill(px + pw - 8, py + 9, px + pw - 4, py + 13, preview.accent());
    g.fill(px + 11, py + 9, px + 17, py + 11, preview.text());

    String title = preview.label();
    String modeTag = preview.light() ? "亮" : "暗";
    int modeW = UiDraw.badgeWidth(f, modeTag);
    int rightSpace = (selected ? 38 : 6) + modeW + 6;
    g.drawString(
        f,
        f.plainSubstrByWidth(title, Math.max(12, w - 42 - rightSpace)),
        px + pw + 7,
        y + (h - 8) / 2,
        selected ? t.text() : t.textSecondary(),
        false);
    int modeX = x + w - modeW - (selected ? 40 : 6);
    UiDraw.drawBadge(
        g,
        f,
        modeX,
        y + (h - 13) / 2,
        modeTag,
        preview.light() ? t.textSecondary() : t.textMuted(),
        UiTheme.withAlpha(t.borderStrong(), 36),
        UiTheme.withAlpha(t.border(), 120));
    if (selected) {
      UiDraw.drawBadge(
          g, f, x + w - 36, y + (h - 13) / 2, "当前", t.textOnAccent(), t.accent(), t.accent());
    }
  }

  private void renderCollapse(
      GuiGraphics g,
      net.minecraft.client.gui.Font f,
      UiTheme t,
      int x,
      int y,
      int w,
      int h,
      boolean hovered) {
    int fill = hovered ? t.cardHover() : t.headerBg();
    int border = hovered ? t.borderStrong() : t.border();
    UiDraw.fillRounded(g, x, y, w, h, fill, border);
    int rightReserve = 10;
    if (!badge.isEmpty()) {
      int bw = UiDraw.badgeWidth(f, badge);
      int bc = badgeColor == 0 ? t.accent() : badgeColor;
      UiDraw.drawBadge(
          g,
          f,
          x + w - bw - 6,
          y + (h - 13) / 2,
          badge,
          bc,
          UiTheme.withAlpha(bc, 32),
          UiTheme.withAlpha(bc, 110));
      rightReserve += bw + 8;
    }
    String s = f.plainSubstrByWidth(getMessage().getString(), Math.max(10, w - rightReserve - 10));
    g.drawString(f, s, x + 8, y + (h - 8) / 2, active ? t.text() : t.textMuted(), false);
  }

  private void renderButton(
      GuiGraphics g,
      net.minecraft.client.gui.Font f,
      UiTheme t,
      int x,
      int y,
      int w,
      int h,
      boolean hovered) {
    int fill;
    int border;
    int fg;
    if (variant == Variant.PRIMARY) {
      fill =
          selected
              ? t.accent()
              : hovered
                  ? UiTheme.withAlpha(t.accent(), t.light() ? 38 : 58)
                  : UiTheme.withAlpha(t.accent(), t.light() ? 22 : 34);
      border = t.accent();
      fg = !active ? t.textMuted() : selected ? t.textOnAccent() : t.text();
    } else if (variant == Variant.DANGER || dangerTone) {
      fill =
          hovered
              ? UiTheme.withAlpha(t.danger(), t.light() ? 52 : 55)
              : UiTheme.withAlpha(t.danger(), t.light() ? 24 : 26);
      border = hovered ? t.danger() : UiTheme.withAlpha(t.danger(), 150);
      fg = !active ? t.textMuted() : t.danger();
    } else {
      fill = selected ? UiTheme.withAlpha(t.accent(), 42) : hovered ? t.cardHover() : t.cardBg();
      border = selected ? t.accent() : hovered ? t.borderStrong() : t.border();
      fg = !active ? t.textMuted() : selected ? t.text() : t.text();
    }
    UiDraw.fillRounded(g, x, y, w, h, fill, border);
    if (selected && variant == Variant.DEFAULT) g.fill(x, y + 3, x + 2, y + h - 3, t.accent());

    int rightReserve = 8;
    if (!badge.isEmpty()) {
      int bw = UiDraw.badgeWidth(f, badge);
      int bc = badgeColor == 0 ? t.accent() : badgeColor;
      UiDraw.drawBadge(
          g,
          f,
          x + w - bw - 6,
          y + (h - 13) / 2,
          badge,
          bc,
          UiTheme.withAlpha(bc, 32),
          UiTheme.withAlpha(bc, 110));
      rightReserve += bw + 8;
    }
    String s =
        f.plainSubstrByWidth(
            getMessage().getString(),
            badge.isEmpty() && w <= 34 ? Math.max(10, w - 8) : Math.max(8, w - rightReserve - 8));
    int tx = badge.isEmpty() && w <= 34 ? x + Math.max(2, (w - f.width(s)) / 2) : x + 8;
    g.drawString(f, s, tx, y + (h - 8) / 2, fg, false);
  }
}
