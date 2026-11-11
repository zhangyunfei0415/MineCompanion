package dev.mibao.companion;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Scrollable full notice, including messages longer than the compact HUD preview. */
public final class NoticeScreen extends Screen {
  private final Screen parent;
  private final String text;
  private List<FormattedCharSequence> lines = List.of();
  private int scroll;

  public NoticeScreen(Screen parent, String text) {
    super(Component.literal("伙伴消息"));
    this.parent = parent;
    this.text = text;
  }

  @Override
  protected void init() {
    lines = font.split(Component.literal(text), Math.max(80, width - 44));
    addRenderableWidget(
        Button.builder(Component.literal("返回面板"), b -> onClose())
            .bounds(20, height - 29, 115, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.literal("复制全文"), b -> minecraft.keyboardHandler.setClipboard(text))
            .bounds(142, height - 29, 90, 20)
            .build());
  }

  @Override
  public boolean mouseScrolled(double x, double y, double delta) {
    scroll =
        Math.max(
            0,
            Math.min(Math.max(0, lines.size() - (height - 88) / 12), scroll - (int) (delta * 3)));
    return true;
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float partial) {
    g.fill(0, 0, width, height, 0xff0c1828);
    g.drawString(font, "伙伴消息 · 滚轮查看全文", 20, 16, 0xff73c9f5, false);
    g.enableScissor(20, 38, width - 20, height - 42);
    for (int i = scroll; i < lines.size() && 38 + (i - scroll) * 12 < height - 42; i++)
      g.drawString(font, lines.get(i), 22, 38 + (i - scroll) * 12, 0xffe2edf8, false);
    g.disableScissor();
    super.render(g, mx, my, partial);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }
}
