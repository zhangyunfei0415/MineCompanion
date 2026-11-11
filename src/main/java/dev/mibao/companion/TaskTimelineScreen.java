package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native task reading surface; text wraps at current GUI scale, detail expands in place. */
public final class TaskTimelineScreen extends Screen {
  private final java.util.Map<String, java.util.List<net.minecraft.util.FormattedCharSequence>>
      wraps = new java.util.HashMap<>();

  private java.util.List<net.minecraft.util.FormattedCharSequence> wrapped(
      String key, String text, int width) {
    if (wraps.size() > 1200) wraps.clear();
    return wraps.computeIfAbsent(
        key + ":" + width + ":" + text,
        k -> font.split(Component.literal(text), Math.max(80, width)));
  }

  private final Screen parent;
  private final Set<String> expanded = new HashSet<>();
  private int scroll, maxScroll;
  private java.util.List<TaskTimeline.Entry> frozen;
  private boolean tools = true;
  private final List<String> hits = new ArrayList<>();
  private final List<Integer> hitYs = new ArrayList<>();

  public TaskTimelineScreen(Screen parent) {
    super(Component.literal("伙伴工作过程"));
    this.parent = parent;
  }

  protected void init() {
    addRenderableWidget(
        Button.builder(Component.literal("返回"), b -> onClose()).bounds(12, 12, 48, 20).build());
    addRenderableWidget(
        Button.builder(
                Component.literal("操作记录"),
                b -> {
                  tools = !tools;
                  scroll = 0;
                  frozen = null;
                })
            .bounds(68, 12, 76, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.literal("最新进展"),
                b -> {
                  scroll = 0;
                  frozen = null;
                })
            .bounds(width - 92, 12, 80, 20)
            .build());
  }

  public void render(GuiGraphics g, int mx, int my, float partial) {
    g.fill(0, 0, width, height, 0xf0101d28);
    g.drawString(font, "任务 · 计划 · 实际操作", 16, 42, 0xffe5eef4, false);
    g.drawString(font, "点击一行展开回执；工作说明不是完成证明", 16, 56, 0xff9bb4c7, false);
    g.enableScissor(12, 76, width - 12, height - 16);
    hits.clear();
    hitYs.clear();
    int y = 82 - scroll;
    var events = new ArrayList<>(frozen == null ? Store.data.taskEvents : frozen);
    Collections.reverse(events);
    if (events.isEmpty())
      g.drawString(font, "还没有任务。从本地对话派一项工作，过程将在这里出现。", 18, y, 0xffc8d7e2, false);
    for (var e : events) {
      if (!tools && (e.kind.equals("action") || e.kind.equals("result"))) continue;
      int top = y;
      String label = TaskTimeline.label(e.kind);
      int color =
          e.kind.equals("note") ? 0xffe5d6ae : e.kind.equals("result") ? 0xffa4d0bb : 0xffd7e6ee;
      for (var line : wrapped(e.id, label + " · " + e.title, width - 48)) {
        g.drawString(font, line, 20, y, color);
        y += 12;
      }
      if (expanded.contains(e.id) && !e.detail.isBlank())
        for (var line : wrapped(e.id + "detail", e.detail, width - 64)) {
          g.drawString(font, line, 32, y, 0xff9bb4c7);
          y += 12;
        }
      hits.add(e.id);
      hitYs.add(top + scroll);
      g.fill(20, y + 5, width - 20, y + 6, 0xff273b49);
      y += 18;
    }
    maxScroll = Math.max(0, y + scroll - (height - 20));
    g.disableScissor();
    super.render(g, mx, my, partial);
  }

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

  public boolean isPauseScreen() {
    return false;
  }

  public void onClose() {
    minecraft.setScreen(parent);
  }
}
