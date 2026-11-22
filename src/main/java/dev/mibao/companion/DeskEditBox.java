package dev.mibao.companion;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Theme-adaptive text input without vanilla's hardcoded pitch-black box and white border. */
public final class DeskEditBox extends EditBox {
  private final Font boxFont;
  private String placeholder = "";
  private boolean dirty;
  private int clipTop = -1, clipBottom = -1;

  public DeskEditBox(Font font, int x, int y, int width, int height, Component message) {
    super(font, x, y, width, height, message);
    this.boxFont = font;
    setBordered(false);
  }

  public DeskEditBox placeholder(String text) {
    this.placeholder = text == null ? "" : text;
    return this;
  }

  public DeskEditBox dirty(boolean dirty) {
    this.dirty = dirty;
    return this;
  }

  public DeskEditBox clip(int top, int bottom) {
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
  public void onClick(double mx, double my) {
    int ox = getX(), ow = getWidth();
    int padX = ow > 20 ? 6 : 2;
    setX(ox + padX);
    setWidth(Math.max(8, ow - padX * 2));
    try {
      super.onClick(mx, my);
    } finally {
      setX(ox);
      setWidth(ow);
    }
  }

  @Override
  public void renderWidget(GuiGraphics g, int mx, int my, float delta) {
    if (!visible) return;
    UiTheme t = UiTheme.current();
    setTextColor(t.text());
    setTextColorUneditable(t.textMuted());

    int ox = getX(), oy = getY(), ow = getWidth(), oh = getHeight();
    boolean hovered = active && isHovered() && inClip(my);
    int fill = isFocused() ? t.cardBg() : hovered ? t.cardHover() : t.inputBg();
    int border =
        dirty
            ? t.warning()
            : isFocused() ? t.accent() : hovered ? t.borderStrong() : t.border();
    UiDraw.fillRounded(g, ox, oy, ow, oh, fill, border);
    if (oh >= 8 && (dirty || isFocused())) {
      g.fill(ox, oy + 3, ox + 2, oy + oh - 3, dirty ? t.warning() : t.accent());
    }

    int padX = ow > 20 ? 6 : 2;
    int padY = Math.max(2, (oh - 8) / 2);
    int innerW = Math.max(8, ow - padX * 2);

    if (getValue().isEmpty() && !placeholder.isEmpty()) {
      String hint = boxFont.plainSubstrByWidth(placeholder, innerW);
      g.drawString(boxFont, hint, ox + padX, oy + padY, t.textMuted(), false);
    }

    setX(ox + padX);
    setY(oy + padY);
    setWidth(innerW);
    try {
      super.renderWidget(g, mx, my, delta);
    } finally {
      setX(ox);
      setY(oy);
      setWidth(ow);
    }
  }
}
