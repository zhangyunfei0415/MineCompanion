package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Readable history and identity notebook; importing text never replays game actions. */
public final class CompanionHistoryScreen extends Screen {
  private final Screen parent;
  private List<net.minecraft.util.FormattedCharSequence> lines = List.of();
  private int scroll;
  private String content = "";

  public CompanionHistoryScreen(Screen parent) {
    super(Component.literal("伙伴手记"));
    this.parent = parent;
  }

  @Override
  protected void init() {
    var data = CompanionIdentity.view();
    var text =
        new StringBuilder(
            "伙伴人设\n"
                + CompanionIdentity.persona()
                + "\n\n共享偏好\n"
                + CompanionIdentity.preferences()
                + "\n\n本世界交流记录\n");
    if (data.has("history"))
      for (var element : data.getAsJsonArray("history")) {
        var e = element.getAsJsonObject();
        String kind = Agent.str(e, "kind", "");
        text.append(
                switch (kind) {
                  case "user" -> "你";
                  case "assistant" -> Store.config.name;
                  case "halt" -> "中断";
                  default -> "任务记录";
                })
            .append(" · ")
            .append(java.time.Instant.ofEpochMilli(e.get("at").getAsLong()))
            .append("\n")
            .append(Agent.str(e, "text", ""))
            .append("\n\n");
      }
    if (data.has("history_error")) text.append(data.get("history_error").getAsString());
    content = text.toString();
    lines = font.split(Component.literal(content), Math.max(80, width - 64));
    addRenderableWidget(new DeskButton("返回面板", 20, height - 30, 96, 22, this::onClose));
    addRenderableWidget(
        new DeskButton("复制手记", 122, height - 30, 90, 22, () -> minecraft.keyboardHandler.setClipboard(content)));
    addRenderableWidget(
        new DeskButton(
                "本地导出",
                218,
                height - 30,
                90,
                22,
                () -> {
                  try {
                    CompanionIdentity.export();
                    Companion.notifyUser("手记已导出到companion/exports，不包含API设置。");
                  } catch (Exception e) {
                    Companion.notifyUser(e.getMessage());
                  }
                })
            .variant(DeskButton.Variant.PRIMARY));
  }

  @Override
  public boolean mouseScrolled(double x, double y, double delta) {
    scroll =
        Math.max(
            0,
            Math.min(Math.max(0, lines.size() - (height - 110) / 12), scroll - (int) (delta * 3)));
    return true;
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float partial) {
    UiTheme t = UiTheme.current();
    g.fill(0, 0, width, height, t.backdrop());
    UiDraw.fillRounded(g, 10, 8, width - 20, height - 16, t.windowBg(), t.border());
    UiDraw.fillHGradient(g, 11, 9, width - 22, 2, t.accent(), t.accentAlt());

    g.drawString(font, "伙伴手记 · 人设与本世界交流", 22, 18, t.accent(), false);
    g.drawString(font, "人设与偏好跨世界共享 · 对话按世界保存 · 旧动作不重放", 22, 32, t.textSecondary(), false);

    int cardY = 48, cardH = Math.max(40, height - 88);
    UiDraw.fillCard(g, 18, cardY, width - 36, cardH, t);
    g.fill(18, cardY + 3, 21, cardY + cardH - 3, t.accentAlt());
    g.enableScissor(24, cardY + 8, width - 28, cardY + cardH - 8);
    for (int i = scroll; i < lines.size() && cardY + 10 + (i - scroll) * 12 < cardY + cardH - 8; i++)
      g.drawString(font, lines.get(i), 28, cardY + 10 + (i - scroll) * 12, t.text(), false);
    g.disableScissor();

    int maxLines = Math.max(1, (cardH - 18) / 12);
    int maxScroll = Math.max(0, lines.size() - maxLines);
    if (maxScroll > 0)
      UiDraw.drawScrollbar(g, width - 25, cardY + 8, cardH - 16, scroll, maxScroll, maxLines, false, t);

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
