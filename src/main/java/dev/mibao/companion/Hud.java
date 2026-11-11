package dev.mibao.companion;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.*;

public final class Hud {
  public static final StatusFeed FEED = new StatusFeed();
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

  public static void notice(String text) {
    FEED.add(text, StatusFeed.level(text), System.currentTimeMillis());
  }

  public static StatusFeed.View localView() {
    var mc = Minecraft.getInstance();
    var v = new StatusFeed.View();
    v.name = Store.config.name;
    v.scope = Store.scope();
    v.theme = Store.config.theme;
    v.updated = System.currentTimeMillis();
    v.detail =
        Actions.job != null
            ? Actions.status
            : Agent.production != null && RestockTask.latest != null
                ? RestockTask.latest.phase
                : Agent.state;
    v.detail = ActionSummary.describe(v.detail);
    if (Cooperation.active())
      v.detail = (Cooperation.yielding() ? "协同 · 人工操作中，连接保留 · " : "协同 · AI执行 · ") + v.detail;
    v.kind =
        StatusFeed.classify(
            Agent.enabled, !Agent.pendingTool.isBlank(), Agent.busy, Actions.job != null, v.detail);
    if (VisualAssist.recording) {
      v.kind = StatusFeed.Kind.SENSING;
      v.detail = "本地示范记录中 · 人工操作 · 不上传/不自动执行";
    }
    v.plan = ProgressBoard.snapshot();
    v.task = Agent.task;
    v.notices = FEED.snapshot();
    v.requests = Store.data.requests;
    v.inputTokens = Store.data.inputTokens;
    v.outputTokens = Store.data.outputTokens;
    v.attention = FEED.attention();
    if (!StatusFeed.keepAttention(
        v.attention, !Agent.pendingTool.isBlank(), System.currentTimeMillis())) v.attention = null;
    v.taskCard = RestockTask.latest == null ? null : RestockTask.latest.copy();
    if (!Agent.enabled && v.kind == StatusFeed.Kind.REST)
      v.detail = StatusFeed.restDetail(Agent.state);
    if (Agent.paused) {
      v.detail = "已暂停 · " + v.detail;
      if (v.taskCard != null) v.taskCard.outcome = "paused";
    }
    if (v.taskCard != null
        && v.taskCard.outcome.equals("blocked")
        && Agent.enabled
        && !Agent.busy
        && Actions.job == null
        && v.attention != null) v.kind = StatusFeed.Kind.HELP;
    v.scanMillis = Perception.lastMillis();
    v.scanAge = Perception.age();
    if (mc.player != null) {
      v.player = mc.player.getGameProfile().getName();
      v.health = mc.player.getHealth();
      v.food = mc.player.getFoodData().getFoodLevel();
    }
    return v;
  }

  public static void render(GuiGraphics g, int width, int height) {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.options.hideGui || !Store.config.hud || Panel.managing(mc.screen))
      return;
    var peer = Agent.enabled ? null : StatusLink.remote();
    var v = peer != null ? peer : localView();
    boolean linked = peer != null;
    boolean stale = linked && System.currentTimeMillis() - v.updated >= 7000;
    int w = OverlayLayout.sidebarWidth(width, Store.config.hudWidth),
        x = Store.config.hudLeft ? 8 : width - w - 8;
    int limit = layout(width, height).hudBottom();
    int y = Math.min(Store.config.hudY, Math.max(8, limit - 80));
    if (Store.config.processDetailed && !linked) {
      ProcessHud.render(g, x, y, w, limit, v);
      return;
    }
    boolean light = Store.config.theme.equals("纸白"), green = Store.config.theme.equals("终端");
    int fg = light ? 0xff172a3b : green ? 0xffbef5cb : 0xffe2edf8,
        muted = light ? 0xff506879 : 0xff9bb0c7;
    int accent =
        v.kind == StatusFeed.Kind.HELP
            ? 0xffff7885
            : v.kind == StatusFeed.Kind.WAITING ? 0xffffc76a : green ? 0xff70dc9b : 0xff73c9f5;
    int bg =
        (Rules.bounded(Store.config.hudOpacity, 40, 100) * 255 / 100) << 24
            | (light ? 0xe5edf3 : green ? 0x091910 : 0x0c1828);
    var lines = new ArrayList<Text>();
    // Live control mode and the real planner connection come first.
    if (!linked) lines.add(new Text(controlLine(), fg));
    if (Store.config.hudAlerts && v.attention != null && v.attention.text != null)
      wrap(
          lines,
          ("alert".equals(v.attention.level) ? "需处理 · " : "待处理 · ") + v.attention.text,
          w - 20,
          "alert".equals(v.attention.level) ? 0xffff8c99 : 0xffffc76a,
          3);
    TaskCard card = Store.config.hudTask ? v.taskCard : null;
    if (card != null && card.ended > 0 && System.currentTimeMillis() - card.ended > 15_000)
      card = null;
    if (card != null) {
      wrap(lines, card.title, w - 20, fg, 2);
      wrap(lines, card.phase + (card.outcome.equals("paused") ? " · 已暂停" : ""), w - 20, accent, 2);
      wrap(
          lines,
          (card.progress != null && !card.progress.isBlank()
                  ? card.progress
                  : card.current < 0
                      ? "库存待打开确认"
                      : ("production".equals(card.kind)
                          ? "已确认制作 " + card.current + " / " + card.target + " 批"
                          : "已确认 " + card.current + " / 目标 " + card.target))
              + " · "
              + card.item,
          w - 20,
          fg,
          2);
      if ("storage".equals(card.kind)) {
        lines.add(new Text("已核实送达 " + card.delivered + " · " + card.trips + " 趟", muted));
        lines.add(new Text(card.uncertain ? "当前携带数量待重新核实" : "本任务账面携带 " + card.carrying, muted));
      }
      if (!card.next.isBlank() && !Store.config.hudCompact)
        wrap(lines, "下一步 · " + card.next, w - 20, muted, 2);
      if (!card.problem.isBlank()) wrap(lines, card.problem, w - 20, 0xffff8c99, 1);
    }
    // (attention is shown at the top of the sidebar)
    if (Store.config.hudTask && v.plan != null && v.plan.has("goal")) {
      String goal = v.plan.get("goal").getAsString(),
          current = v.plan.get("current").getAsString(),
          next = v.plan.get("next").getAsString();
      if (Agent.enabled || linked) {
        if (!goal.isBlank()) wrap(lines, "计划 · " + goal, w - 20, fg, 2);
        if (!current.isBlank()) wrap(lines, "当前计划 · " + current, w - 20, accent, 2);
        if (!next.isBlank() && !Store.config.hudCompact)
          wrap(lines, "下一步 · " + next, w - 20, muted, 2);
      }
    }
    wrap(lines, stale ? "状态同步已中断；小号可能已退出或暂停。" : v.detail, w - 20, muted, 2);
    if (!Store.config.hudCompact && !v.task.isBlank()) wrap(lines, "任务 · " + v.task, w - 20, fg, 2);
    if (!linked) lines.add(new Text(RuntimeStrategy.label(), muted));
    if (v.kind != StatusFeed.Kind.REST)
      lines.add(
          new Text("生命 " + Math.round(v.health) + "  饥饿 " + v.food + "  ·  " + v.player, muted));
    if (Store.config.hudMetrics) {
      lines.add(
          new Text("请求 " + v.requests + "  已知Token " + (v.inputTokens + v.outputTokens), muted));
      lines.add(
          new Text(
              String.format(
                  Locale.ROOT,
                  "扫描 %.2f ms · 缓存 %s",
                  v.scanMillis,
                  v.scanAge < 0 ? "准备中" : v.scanAge / 1000 + "s"),
              muted));
    }
    if (linked)
      lines.add(
          new Text(
              "本机同步 · " + Math.max(0, (System.currentTimeMillis() - v.updated) / 1000) + "s 前",
              muted));
    if (!linked) {
      String current = StreamProgress.latest();
      if (!current.isBlank()) wrap(lines, "工作说明 · " + current, w - 20, fg, 2);
    }
    if (v.notices != null && !v.notices.isEmpty()) {
      boolean noticeTitle = false;
      int start = Math.max(0, v.notices.size() - Rules.bounded(Store.config.hudMessages, 1, 5));
      for (int i = v.notices.size() - 1; i >= start; i--) {
        var n = v.notices.get(i);
        if (n == null || n.text == null) continue;
        int alpha = StatusFeed.opacity(System.currentTimeMillis() - n.at, n.level);
        if (alpha <= 8) continue;
        if (!noticeTitle) {
          lines.add(new Text("给主人", accent));
          noticeTitle = true;
        }
        String time = CLOCK.format(Instant.ofEpochMilli(n.at).atZone(ZoneId.systemDefault()));
        wrap(
            lines,
            time + "  " + n.text + (n.repeats > 1 ? " ×" + n.repeats : ""),
            w - 20,
            (alpha << 24)
                | (("alert".equals(n.level) ? 0xffff8c99 : "ask".equals(n.level) ? 0xffffcc77 : fg)
                    & 0xffffff),
            Store.config.hudCompact ? 2 : 3);
      }
    }
    int header = card == null ? 34 : 40;
    int maxLines = Math.max(1, (limit - y - header - 20) / 11);
    boolean clipped = lines.size() > maxLines;
    int shown = Math.min(lines.size(), maxLines), h = header + 10 + shown * 11;
    g.fill(x + 2, y + 3, x + w + 2, y + h + 3, 0x30000000);
    g.fill(x, y, x + w, y + h, bg);
    g.fill(x, y, x + 3, y + h, accent);
    g.renderItem(new ItemStack(icon(v.kind)), x + 9, y + 8);
    g.drawString(
        mc.font,
        mc.font.plainSubstrByWidth(v.name + " · " + (stale ? "连接中断" : v.kind.title), w - 42),
        x + 30,
        y + 8,
        accent,
        false);
    g.drawString(mc.font, linked ? "伙伴小号" : "简洁  /  切为详细", x + 30, y + 20, muted, false);
    if (card != null) {
      g.fill(x + 10, y + 32, x + w - 10, y + 35, 0x55394e68);
      g.fill(x + 10, y + 32, x + 10 + (w - 20) * card.percent() / 100, y + 35, accent);
    }
    int cy = y + header;
    for (int i = 0; i < shown; i++) {
      var line = lines.get(i);
      g.drawString(mc.font, line.text, x + 10, cy, line.color, false);
      cy += 11;
    }
    g.drawString(
        mc.font,
        mc.font.plainSubstrByWidth(
            clipped
                ? "更多：面板 → 账本 → 历史记录"
                : Companion.HUD.getTranslatedKeyMessage().getString()
                    + " 收起 · "
                    + Companion.PANEL.getTranslatedKeyMessage().getString()
                    + " 面板 · "
                    + Companion.STOP.getTranslatedKeyMessage().getString()
                    + " 停止",
            w - 20),
        x + 10,
        y + h - 10,
        muted,
        false);
  }

  private static long linkCheckedAt;
  private static boolean harnessLinked;

  /** Control mode plus actual planner link; Harness status is sampled at most once per second. */
  public static String controlLine() {
    long now = System.currentTimeMillis();
    if (now - linkCheckedAt > 1000) {
      linkCheckedAt = now;
      try {
        harnessLinked = HarnessController.status().get("lease_active").getAsBoolean();
      } catch (Exception e) {
        harnessLinked = false;
      }
    }
    return StatusFeed.controlLine(
        Agent.enabled, Agent.paused, Cooperation.active(), Store.config.driver, harnessLinked);
  }

  public static OverlayLayout.Layout layout(int width, int height) {
    var p = Minecraft.getInstance().player;
    int rows =
        p == null
            ? 1
            : (int)
                Math.ceil(
                    Math.max(p.getMaxHealth(), p.getHealth()) / 20.0
                        + p.getAbsorptionAmount() / 20.0);
    var layout =
        OverlayLayout.calculate(
            width,
            height,
            Store.config.hud,
            Store.config.hudLeft,
            Store.config.hudWidth,
            rows,
            LocalDialogue.editing);
    if (Store.config.avatarX >= 0 && Store.config.avatarY >= 0) {
      int size = Rules.bounded(Store.config.avatarSize, 32, 100);
      int x = Rules.bounded(Store.config.avatarX, 0, Math.max(0, width - size)),
          y = Rules.bounded(Store.config.avatarY, 0, Math.max(0, layout.hudBottom() - size - 14));
      var rect = new OverlayLayout.Rect(x, y, size, size + 12);
      if (!rect.overlaps(layout.dialogue()))
        layout = new OverlayLayout.Layout(layout.dialogue(), rect, layout.hudBottom());
    }
    return layout;
  }

  private record Text(FormattedCharSequence text, int color) {
    Text(String text, int color) {
      this(FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY), color);
    }
  }

  private static void wrap(List<Text> out, String text, int width, int color, int max) {
    var lines = Minecraft.getInstance().font.split(Component.literal(text), width);
    for (int i = 0; i < Math.min(max, lines.size()); i++) out.add(new Text(lines.get(i), color));
  }

  private static Item icon(StatusFeed.Kind kind) {
    return switch (kind) {
      case REST -> Items.WHITE_BED;
      case IDLE -> Items.COMPASS;
      case THINKING -> Items.CLOCK;
      case WALKING -> Items.MAP;
      case INTERFACE -> Items.BOOK;
      case EATING -> Items.BREAD;
      case MINING -> Items.IRON_PICKAXE;
      case COMBAT -> Items.IRON_SWORD;
      case STORAGE -> Items.CHEST;
      case SENSING -> Items.SPYGLASS;
      case WORKING -> Items.CRAFTING_TABLE;
      case WAITING -> Items.PAPER;
      case HELP -> Items.REDSTONE_TORCH;
    };
  }
}
