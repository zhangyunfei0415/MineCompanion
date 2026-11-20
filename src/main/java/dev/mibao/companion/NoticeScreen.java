package dev.mibao.companion;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Scrollable full notice, themed with G4Neon surfaces and DeskButton controls. */
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
    lines = font.split(Component.literal(text), Math.max(80, width - 56));
    addRenderableWidget(new DeskButton("返回面板", 20, height - 30, 110, 22, this::onClose));
    addRenderableWidget(
        new DeskButton("复制全文", 138, height - 30, 96, 22, () -> minecraft.keyboardHandler.setClipboard(text))
            .variant(DeskButton.Variant.PRIMARY));
  }

  @Override
  public boolean mouseScrolled(double x, double y, double delta) {
    scroll =
        Math.max(
            0,
            Math.min(Math.max(0, lines.size() - (height - 96) / 12), scroll - (int) (delta * 3)));
    return true;
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float partial) {
    UiTheme t = UiTheme.current();
    g.fill(0, 0, width, height, t.backdrop());
    UiDraw.fillRounded(g, 12, 10, width - 24, height - 20, t.windowBg(), t.border());
    UiDraw.fillHGradient(g, 13, 11, width - 26, 2, t.accent(), t.accentAlt());
    g.drawString(font, "伙伴详情与全文查看 · 滚轮翻阅", 22, 20, t.accent(), false);

    int cardY = 38, cardH = Math.max(40, height - 76);
    UiDraw.fillCard(g, 18, cardY, width - 36, cardH, t);
    g.enableScissor(22, cardY + 6, width - 28, cardY + cardH - 6);
    for (int i = scroll; i < lines.size() && cardY + 8 + (i - scroll) * 12 < cardY + cardH - 6; i++)
      g.drawString(font, lines.get(i), 26, cardY + 8 + (i - scroll) * 12, t.text(), false);
    g.disableScissor();

    int maxLines = Math.max(1, (cardH - 14) / 12);
    int maxScroll = Math.max(0, lines.size() - maxLines);
    if (maxScroll > 0)
      UiDraw.drawScrollbar(g, width - 25, cardY + 6, cardH - 12, scroll, maxScroll, maxLines, false, t);

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
