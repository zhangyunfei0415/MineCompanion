package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native task reading surface; text wraps at current GUI scale, detail expands in place. */
public final class TaskTimelineScreen extends Screen {
  private final Map<String, List<net.minecraft.util.FormattedCharSequence>> wraps = new HashMap<>();

  private List<net.minecraft.util.FormattedCharSequence> wrapped(
      String key, String text, int width) {
    if (wraps.size() > 1200) wraps.clear();
    return wraps.computeIfAbsent(
        key + ":" + width + ":" + text,
        k -> font.split(Component.literal(text), Math.max(80, width)));
  }

  private final Screen parent;
  private final Set<String> expanded = new HashSet<>();
  private int scroll, maxScroll;
  private List<TaskTimeline.Entry> frozen;
  private boolean tools = true;
  private final List<String> hits = new ArrayList<>();
  private final List<Integer> hitYs = new ArrayList<>();

  public TaskTimelineScreen(Screen parent) {
    super(Component.literal("伙伴工作过程"));
    this.parent = parent;
  }

  @Override
  protected void init() {
    addRenderableWidget(new DeskButton("返回", 18, 16, 56, 20, this::onClose));
    var filterBtn =
        new DeskButton(
            tools ? "操作记录：显示" : "操作记录：隐藏",
            80,
            16,
            106,
            20,
            () -> {
              tools = !tools;
              scroll = 0;
              frozen = null;
              rebuildWidgets();
            });
    filterBtn.selected = tools;
    addRenderableWidget(filterBtn);
    addRenderableWidget(
        new DeskButton(
                "回到最新",
                width - 96,
                16,
                78,
                20,
                () -> {
                  scroll = 0;
                  frozen = null;
                })
            .variant(DeskButton.Variant.PRIMARY));
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float partial) {
    UiTheme t = UiTheme.current();
    g.fill(0, 0, width, height, t.backdrop());
    UiDraw.fillRounded(g, 10, 8, width - 20, height - 16, t.windowBg(), t.border());
    UiDraw.fillHGradient(g, 11, 9, width - 22, 2, t.accent(), t.accentAlt());

    g.drawString(font, "任务 · 计划 · 实际操作时间线", 20, 44, t.text(), false);
    g.drawString(font, "点击事件卡片展开回执细节 · 工作说明不是完成证明", 20, 58, t.textSecondary(), false);
    g.fill(18, 72, width - 18, 73, t.border());

    g.enableScissor(16, 76, width - 22, height - 16);
    hits.clear();
    hitYs.clear();
    int y = 82 - scroll;
    var events = new ArrayList<>(frozen == null ? Store.data.taskEvents : frozen);
    Collections.reverse(events);
    if (events.isEmpty())
      g.drawString(font, "还没有任务。从本地对话派一项工作，过程将在这里出现。", 22, y, t.textSecondary(), false);
    for (var e : events) {
      if (!tools && (e.kind.equals("action") || e.kind.equals("result"))) continue;
      int top = y;
      String label = TaskTimeline.label(e.kind);
      int color =
          e.kind.equals("note")
              ? t.warning()
              : e.kind.equals("result") ? t.success() : t.text();
      for (var line : wrapped(e.id, label + " · " + e.title, width - 60)) {
        g.drawString(font, line, 24, y, color, false);
        y += 12;
      }
      if (expanded.contains(e.id) && !e.detail.isBlank())
        for (var line : wrapped(e.id + "detail", e.detail, width - 76)) {
          g.drawString(font, line, 36, y, t.textSecondary(), false);
          y += 12;
        }
      hits.add(e.id);
      hitYs.add(top + scroll);
      g.fill(22, y + 4, width - 26, y + 5, UiTheme.withAlpha(t.border(), 140));
      y += 14;
    }
    int viewH = Math.max(1, height - 96);
    maxScroll = Math.max(0, y + scroll - (height - 20));
    g.disableScissor();

    if (maxScroll > 0)
      UiDraw.drawScrollbar(g, width - 20, 76, viewH, scroll, maxScroll, viewH, false, t);

    super.render(g, mx, my, partial);
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    if (y >= 76 && y < height - 16 && button == 0) {
      for (int i = hitYs.size() - 1; i >= 0; i--)
        if (y + scroll >= hitYs.get(i)) {
          String id = hits.get(i);
          if (!expanded.remove(id)) expanded.add(id);
          return true;
        }
    }
    return super.mouseClicked(x, y, button);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double amount) {
    if (amount < 0 && frozen == null) {
      frozen = new ArrayList<>();
      for (var e : Store.data.taskEvents) {
        var copy = new TaskTimeline.Entry();
        copy.id = e.id;
        copy.kind = e.kind;
        copy.title = e.title;
        copy.detail = e.detail;
        frozen.add(copy);
      }
    }
    scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (amount * 28)));
    if (scroll == 0) frozen = null;
    return true;
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
