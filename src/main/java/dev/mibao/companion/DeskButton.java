package dev.mibao.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Quiet outlined controls shared by the desk and legacy detail pages. */
public final class DeskButton extends Button {
  public boolean selected;

  public DeskButton(String text, int x, int y, int w, Runnable action) {
    super(x, y, w, 22, Component.literal(text), b -> action.run(), DEFAULT_NARRATION);
  }

  @Override
  protected void renderWidget(GuiGraphics g, int mx, int my, float delta) {
    boolean light = Store.config.theme.equals("纸白");
    int accent =
        light
            ? 0xff246f78
            : Store.config.theme.equals("终端")
                ? 0xff8ce5a8
                : Store.config.theme.equals("原版") ? 0xffd8b46d : 0xff7bd6c5;
    g.fill(
        getX(),
        getY(),
        getX() + width,
        getY() + height,
        selected
            ? light ? 0xffc2e1df : 0xff1d4145
            : isHoveredOrFocused()
                ? light ? 0xffd9e7e9 : 0xff243d48
                : light ? 0xffeaf0f2 : 0xff142b38);
    g.fill(
        getX(),
        getY() + height - 1,
        getX() + width,
        getY() + height,
        active ? selected ? accent : light ? 0xffaabdc5 : 0xff355464 : 0xff53616a);
    if (selected) g.fill(getX(), getY(), getX() + 2, getY() + height, accent);
    var f = Minecraft.getInstance().font;
    String s = f.plainSubstrByWidth(getMessage().getString(), Math.max(8, width - 12));
    g.drawString(
        f, s, getX() + 7, getY() + 7, active ? light ? 0xff172e3b : 0xffdcecf0 : 0xff788b94, false);
  }
}
