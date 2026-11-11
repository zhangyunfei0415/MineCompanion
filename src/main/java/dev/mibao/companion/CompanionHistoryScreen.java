package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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
    lines = font.split(Component.literal(content), width - 56);
    addRenderableWidget(
        Button.builder(Component.literal("返回面板"), b -> onClose())
            .bounds(18, height - 28, 95, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.literal("复制手记"), b -> minecraft.keyboardHandler.setClipboard(content))
            .bounds(120, height - 28, 90, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.literal("本地导出"),
                b -> {
                  try {
                    CompanionIdentity.export();
                    Companion.notifyUser("手记已导出到companion/exports，不包含API设置。");
                  } catch (Exception e) {
                    Companion.notifyUser(e.getMessage());
                  }
                })
            .bounds(217, height - 28, 85, 20)
            .build());
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
    g.fill(0, 0, width, height, 0xff0b1723);
    g.fill(12, 48, width - 12, height - 38, 0xff162f3d);
    g.fill(12, 48, 15, height - 38, 0xffc8ac79);
    g.drawString(font, "伙伴手记", 20, 16, 0xffe6c886, false);
    g.drawString(font, "人设与偏好共享 · 对话按世界保存 · 旧动作不重放", 20, 32, 0xff9fbdc9, false);
    g.enableScissor(22, 58, width - 22, height - 46);
    for (int i = scroll; i < lines.size() && 58 + (i - scroll) * 12 < height - 46; i++)
      g.drawString(font, lines.get(i), 26, 58 + (i - scroll) * 12, 0xffe1ecee, false);
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
