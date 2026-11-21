package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** G4Neon Studio Command Deck: unified Ledger & Settings workbench for single-player companion control. */
public final class WorkbenchScreen extends Screen {
  record SegmentOption(String label, boolean selected, Runnable action) {}

  record BadgeItem(String text, int color) {}

  private record DrawLine(String text, int x, int y, int color) {}

  private record DrawCard(int x, int y, int w, int h, String title, String badge, int badgeColor, boolean alert) {}

  private record DrawBadge(String text, int x, int y, int color) {}

  private record DrawKeycap(String action, String key, int x, int y, int w) {}

  private record ScrollWidget(AbstractWidget widget, int baseY) {}

  private static final ResourceLocation ART =
      new ResourceLocation(Companion.ID, "textures/gui/logistics-desk-20261031.png");
  private static final String[] SETTINGS_NAV = {"模型与连接", "权限与安全", "输入与感知", "显示与陪伴", "数据与维护"};
  private static final String[] LEDGER_NAV = {"当前任务", "基地与库存", "技能与流程", "知识与配方", "任务历史"};

  private final Screen previous;
  private final Object previousMenu;
  private final WorkbenchPages pages = new WorkbenchPages(this);
  private final WorkbenchSettingsViews settingsViews = new WorkbenchSettingsViews(this);

  private boolean settings;
  private int page, scroll, y, maxY;
  private DeskLayout box;
  private int winX, winY, winW, winH, headerBottom, viewLeft, viewRight;

  private String query = "", selected = "", command = "", status = "", miningGoal = "", settingsSearch = "";
  private String lastFingerprint = "";
  private BagPolicy bagDraft;
  private EditBox searchBox;
  private int section = -1, parentScroll, ticks;
  private boolean draggingScrollbar;
  private int dragStartMouseY, dragStartScroll;

  private final Map<String, String> drafts = new HashMap<>();
  private final Set<String> dirtyKeys = new HashSet<>();
  private final Map<String, EditBox> fields = new LinkedHashMap<>();
  private final Map<String, Integer> scrolls = new HashMap<>();
  private final List<DrawLine> lines = new ArrayList<>();
  private final List<DrawCard> cards = new ArrayList<>();
  private final List<DrawBadge> badges = new ArrayList<>();
  private final List<DrawKeycap> keycaps = new ArrayList<>();
  private final List<AbstractWidget> chromeWidgets = new ArrayList<>();
  private final List<ScrollWidget> contentWidgets = new ArrayList<>();

  public WorkbenchScreen() {
    super(Component.literal("后勤伙伴 G4Neon · 工作台"));
    var mc = net.minecraft.client.Minecraft.getInstance();
    previous = mc.screen;
    previousMenu = mc.player == null ? null : mc.player.containerMenu;
  }

  UiTheme theme() {
    return UiTheme.current();
  }

  String settingsQuery() {
    return settingsSearch;
  }

  void clearSettingsQuery() {
    settingsSearch = "";
    drafts.remove("settings/search");
    dirtyKeys.remove("settings/search");
    if (searchBox != null && !searchBox.getValue().isEmpty()) searchBox.setValue("");
    scroll = 0;
    rebuild();
  }

  void clearSettingsQueryOnly() {
    settingsSearch = "";
    drafts.remove("settings/search");
    dirtyKeys.remove("settings/search");
    if (searchBox != null && !searchBox.getValue().isEmpty()) searchBox.setValue("");
  }

  void setStatus(String s) {
    status = s == null ? "" : s;
  }

  void openNotice(String content) {
    minecraft.setScreen(new NoticeScreen(this, content));
  }

  boolean hasDraftPrefix(String prefix) {
    return dirtyKeys.stream().anyMatch(k -> k.startsWith(prefix));
  }

  void putDraft(String key, String value) {
    drafts.put(key, value);
  }

  String value(String key, String fallback) {
    return drafts.getOrDefault(key, fallback);
  }

  @Override
  protected void init() {
    rebuild();
  }

  private void computeGeometry() {
    // Keep DeskLayout for column math while padding into an elevated G4Neon command window on wide screens.
    int marginX = width >= 620 ? 18 : 0;
    int marginY = height >= 360 ? 12 : 0;
    winX = marginX;
    winY = marginY;
    winW = Math.max(300, width - marginX * 2);
    winH = Math.max(200, height - marginY * 2);
    DeskLayout base = DeskLayout.of(winW, winH);
    int navW = Math.min(132, Math.max(base.nav(), width >= 680 ? 128 : base.nav()));
    int padLeft = winX + navW + 14;
    int padRight = winX + winW - 18;
    int contentW = Math.max(160, padRight - padLeft);
    headerBottom = winY + 52;
    int bottom = winY + winH - 26;
    box = new DeskLayout(navW, padLeft, contentW, headerBottom + 8, bottom);
    viewLeft = box.left() - 2;
    viewRight = box.left() + box.width() + 2;
  }

  private <T extends AbstractWidget> T addChrome(T widget) {
    chromeWidgets.add(widget);
    return addRenderableWidget(widget);
  }

  private <T extends AbstractWidget> T addContent(T widget, int baseY) {
    if (widget instanceof DeskButton db) db.clip(box.top(), box.bottom());
    contentWidgets.add(new ScrollWidget(widget, baseY));
    int py = baseY - scroll;
    widget.setY(py);
    widget.visible = py + widget.getHeight() >= box.top() && py <= box.bottom();
    return addRenderableWidget(widget);
  }

  private void repositionContentWidgets() {
    for (var sw : contentWidgets) {
      int py = sw.baseY() - scroll;
      sw.widget().setY(py);
      sw.widget().visible = py + sw.widget().getHeight() >= box.top() && py <= box.bottom();
    }
  }

  private int maxScroll() {
    return Math.max(0, maxY - box.bottom() + 8);
  }

  // --- Layout DSL ---

  void card(String title, String badge, int badgeColor, Runnable body) {
    renderCardBox(title, badge, badgeColor, false, body);
  }

  void alertCard(String title, String badge, int badgeColor, Runnable body) {
    renderCardBox(title, badge, badgeColor, true, body);
  }

  void collapsibleCard(
      String key, String title, String badge, int badgeColor, boolean defaultOpen, Runnable body) {
    boolean open =
        drafts.getOrDefault("group/" + key, Boolean.toString(defaultOpen)).equals("true");
    var header =
        new DeskButton(
                (open ? "▾ " : "▸ ") + title,
                box.left(),
                y - scroll,
                box.width(),
                22,
                () ->
                    safe(
                        () -> {
                          drafts.put("group/" + key, Boolean.toString(!open));
                          rebuild();
                        }))
            .variant(DeskButton.Variant.COLLAPSE)
            .badge(badge, badgeColor);
    addContent(header, y);
    y += 26;
    maxY = Math.max(maxY, y);
    if (open) {
      renderCardBox("", "", 0, false, body);
    } else {
      y += 4;
      maxY = Math.max(maxY, y);
    }
  }

  private void renderCardBox(
      String title, String badge, int badgeColor, boolean alert, Runnable body) {
    int cardX = box.left();
    int cardW = box.width();
    int startY = y;
    boolean hasHeader = title != null && !title.isEmpty();
    y += hasHeader ? 24 : 8;
    // Temporarily inset horizontal box for card children
    DeskLayout saved = box;
    box = new DeskLayout(saved.nav(), cardX + 8, Math.max(120, cardW - 16), saved.top(), saved.bottom());
    body.run();
    box = saved;
    y += 6;
    int cardH = Math.max(24, y - startY);
    cards.add(new DrawCard(cardX, startY, cardW, cardH, title == null ? "" : title, badge == null ? "" : badge, badgeColor, alert));
    y += 8;
    maxY = Math.max(maxY, y);
  }

  void text(String s) {
    coloredText(s, theme().text());
  }

  void mutedText(String s) {
    coloredText(s, theme().textSecondary());
  }

  void coloredText(String s, int color) {
    if (s == null || s.isEmpty()) return;
    for (var line : font.split(Component.literal(s), Math.max(40, box.width() - 6))) {
      lines.add(new DrawLine(lineText(line), box.left() + 3, y, color));
      y += 11;
    }
    y += 4;
    maxY = Math.max(maxY, y);
  }

  private static String lineText(net.minecraft.util.FormattedCharSequence line) {
    var s = new StringBuilder();
    line.accept(
        (i, style, cp) -> {
          s.appendCodePoint(cp);
          return true;
        });
    return s.toString();
  }

  DeskButton button(String label, Runnable run) {
    return makeButton(label, DeskButton.Variant.DEFAULT, run);
  }

  DeskButton primaryButton(String label, Runnable run) {
    return makeButton(label, DeskButton.Variant.PRIMARY, run);
  }

  DeskButton dangerButton(String label, Runnable run) {
    return makeButton(label, DeskButton.Variant.DANGER, run);
  }

  private DeskButton makeButton(String label, DeskButton.Variant variant, Runnable run) {
    var b =
        new DeskButton(label, box.left(), y - scroll, box.width(), 22, () -> safe(run))
            .variant(variant);
    addContent(b, y);
    y += 26;
    maxY = Math.max(maxY, y);
    return b;
  }

  void row(String a, Runnable ar, String b, Runnable br) {
    if (box.columns() == 1) {
      button(a, ar);
      button(b, br);
      return;
    }
    int cw = box.columnWidth();
    var aButton = new DeskButton(a, box.left(), y - scroll, cw, 22, () -> safe(ar));
    var bButton = new DeskButton(b, box.left() + cw + 8, y - scroll, cw, 22, () -> safe(br));
    addContent(aButton, y);
    addContent(bButton, y);
    y += 26;
    maxY = Math.max(maxY, y);
  }

  void toggle(String title, BooleanSupplier get, Consumer<Boolean> set) {
    toggleRow(title, get, set, false, "", 0);
  }

  void toggleRow(
      String label,
      BooleanSupplier get,
      Consumer<Boolean> set,
      boolean danger,
      String badge,
      int badgeColor) {
    boolean on = get.getAsBoolean();
    var b =
        new DeskButton(
                label,
                box.left(),
                y - scroll,
                box.width(),
                22,
                () ->
                    safe(
                        () -> {
                          set.accept(!get.getAsBoolean());
                          Store.saveSettingsOnly();
                          rebuild();
                        }))
            .variant(DeskButton.Variant.TOGGLE)
            .checked(on)
            .dangerTone(danger)
            .badge(badge, badgeColor);
    addContent(b, y);
    y += 26;
    maxY = Math.max(maxY, y);
  }

  void segmentRow(List<SegmentOption> options) {
    if (options.isEmpty()) return;
    int count = options.size();
    int gap = 4;
    int totalW = box.width();
    int segW = (totalW - gap * (count - 1)) / count;
    if (segW < 52 && count > 2) {
      // Wrap to two lines on very narrow windows
      int half = (count + 1) / 2;
      segmentRow(options.subList(0, half));
      segmentRow(options.subList(half, count));
      return;
    }
    int curX = box.left();
    for (int i = 0; i < count; i++) {
      var opt = options.get(i);
      int w = i == count - 1 ? (box.left() + totalW - curX) : segW;
      var seg =
          new DeskButton(opt.label(), curX, y - scroll, w, 20, () -> safe(opt.action()))
              .variant(DeskButton.Variant.SEGMENT);
      seg.selected = opt.selected();
      addContent(seg, y);
      curX += w + gap;
    }
    y += 24;
    maxY = Math.max(maxY, y);
  }

  void swatchRow(List<String> themeIds) {
    int cols = box.width() >= 280 ? 2 : 1;
    int colW = cols == 2 ? (box.width() - 8) / 2 : box.width();
    for (int i = 0; i < themeIds.size(); i += cols) {
      for (int c = 0; c < cols && i + c < themeIds.size(); c++) {
        String id = themeIds.get(i + c);
        int bx = box.left() + c * (colW + 8);
        var b =
            new DeskButton(
                    id,
                    bx,
                    y - scroll,
                    colW,
                    34,
                    () ->
                        safe(
                            () -> {
                              Store.config.theme = id;
                              Store.saveSettingsOnly();
                              rebuild();
                            }))
                .swatch(id);
        b.selected = Store.config.theme.equals(id);
        addContent(b, y);
      }
      y += 38;
      maxY = Math.max(maxY, y);
    }
  }

  void badgeGrid(List<BadgeItem> items) {
    int curX = box.left();
    int rowH = 16;
    for (var item : items) {
      int w = UiDraw.badgeWidth(font, item.text());
      if (curX > box.left() && curX + w > box.left() + box.width()) {
        curX = box.left();
        y += rowH;
      }
      badges.add(new DrawBadge(item.text(), curX, y, item.color()));
      curX += w + 4;
    }
    y += rowH + 2;
    maxY = Math.max(maxY, y);
  }

  void keybindRow(String actionLabel, String keyText) {
    keycaps.add(new DrawKeycap(actionLabel, keyText, box.left(), y, box.width()));
    y += 19;
    maxY = Math.max(maxY, y);
  }

  EditBox field(String key, String label, String value, Consumer<String> responder) {
    return flowField(key, label, value, key.equals("command") ? 4000 : 512, responder);
  }

  EditBox flowField(String key, String label, String value, int max, Consumer<String> responder) {
    mutedText(label);
    int py = y - scroll;
    var e = new EditBox(font, box.left() + 2, py, box.width() - 4, 20, Component.literal(label));
    e.setMaxLength(max);
    e.setValue(drafts.getOrDefault(key, value));
    if (!Objects.equals(e.getValue(), value)) dirtyKeys.add(key);
    else dirtyKeys.remove(key);
    e.setResponder(
        v -> {
          drafts.put(key, v);
          if (!Objects.equals(v, value)) dirtyKeys.add(key);
          else dirtyKeys.remove(key);
          responder.accept(v);
        });
    responder.accept(e.getValue());
    fields.put(key, e);
    addContent(e, y);
    y += 25;
    maxY = Math.max(maxY, y);
    return e;
  }

  // --- Navigation & State ---

  private void safe(Runnable run) {
    try {
      run.run();
    } catch (Exception e) {
      status = Rules.clean(e.getMessage(), 180);
      rebuild();
    }
  }

  void go(boolean root, int target) {
    saveDrafts();
    scrolls.put(settings + ":" + page + ":" + selected, scroll);
    settings = root;
    page = target;
    section = -1;
    selected = "";
    scroll = scrolls.getOrDefault(settings + ":" + page + ":", 0);
    status = "";
    rebuild();
  }

  void saveDrafts() {
    fields.forEach((key, value) -> drafts.put(key, value.getValue()));
  }

  private void detail(String id) {
    saveDrafts();
    scrolls.put("list", scroll);
    selected = id;
    scroll = 0;
    rebuild();
  }

  void section(int tab, boolean root) {
    saveDrafts();
    if (section < 0) {
      var owner = DeskRoute.of(tab, root);
      parentScroll =
          settings == owner.settings() && page == owner.page()
              ? scroll
              : scrolls.getOrDefault(owner.settings() + ":" + owner.page() + ":", 0);
    }
    pages.open(tab, root);
    componentChanged(tab, root);
  }

  void componentChanged(int tab, boolean root) {
    saveDrafts();
    var route = DeskRoute.of(tab, root);
    if (section != tab) {
      scroll = 0;
    }
    section = tab;
    settings = route.settings();
    page = route.page();
    rebuild();
  }

  void flowButton(String label, Runnable run) {
    button(label, run);
  }

  void flowRow(String a, Runnable ar, String b, Runnable br) {
    row(a, ar, b, br);
  }

  void flowText(net.minecraft.util.FormattedCharSequence line) {
    text(lineText(line));
  }

  int flowWidth() {
    return box.width();
  }

  void clearDrafts(String prefix) {
    drafts.keySet().removeIf(k -> k.startsWith(prefix));
    dirtyKeys.removeIf(k -> k.startsWith(prefix));
  }

  private void task(String text) {
    if (text.isBlank()) {
      status = "请先填写任务";
      return;
    }
    onClose();
    Agent.submit(text, true);
  }

  void rebuild() {
    clearWidgets();
    chromeWidgets.clear();
    contentWidgets.clear();
    lines.clear();
    cards.clear();
    badges.clear();
    keycaps.clear();
    fields.clear();

    computeGeometry();
    y = box.top();
    maxY = y;
    lastFingerprint = liveFingerprint();

    // Left Rail: Root Segment (账本 / 设置)
    int navX = winX + 8;
    int navInner = box.nav() - 16;
    int half = (navInner - 4) / 2;
    var ledger =
        new DeskButton("账本", navX, winY + 34, half, 20, () -> go(false, 0))
            .variant(DeskButton.Variant.SEGMENT);
    ledger.selected = !settings;
    addChrome(ledger);
    var cfg =
        new DeskButton("设置", navX + half + 4, winY + 34, navInner - half - 4, 20, () -> go(true, 0))
            .variant(DeskButton.Variant.SEGMENT);
    cfg.selected = settings;
    addChrome(cfg);

    // Settings instant search field inside Rail when on Settings (persistent instance preserves cursor/IME)
    int navTop = winY + 62;
    if (settings && winH >= 240) {
      if (searchBox == null) {
        searchBox =
            new EditBox(
                font,
                navX + 1,
                navTop,
                navInner - 2,
                18,
                Component.literal("搜索设置项"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("搜索设置..."));
        searchBox.setResponder(
            v -> {
              if (!Objects.equals(settingsSearch, v)) {
                settingsSearch = v;
                scroll = 0;
                rebuild();
                setFocused(searchBox);
              }
            });
      } else {
        searchBox.setX(navX + 1);
        searchBox.setY(navTop);
        searchBox.setWidth(navInner - 2);
      }
      if (!searchBox.getValue().equals(settingsSearch)) searchBox.setValue(settingsSearch);
      fields.put("settings/search", searchBox);
      addChrome(searchBox);
      navTop += 24;
    }

    String[] nav = settings ? SETTINGS_NAV : LEDGER_NAV;
    for (int i = 0; i < nav.length; i++) {
      int target = i;
      var b =
          new DeskButton(nav[i], navX, navTop + i * 25, navInner, 22, () -> go(settings, target))
              .variant(DeskButton.Variant.NAV);
      b.selected = page == i && (!settings || settingsSearch.isBlank());
      addChrome(b);
    }

    // Bottom rail quick actions
    int bottomBtnY = winY + winH - 28;
    addChrome(
        new DeskButton("返回游戏", navX, bottomBtnY, navInner, 20, this::onClose)
            .variant(DeskButton.Variant.DEFAULT));

    // Top-right header quick action (Stop / AI Takeover)
    int actionW = 76;
    var quickBtn =
        new DeskButton(
                Agent.enabled ? "紧急停止" : "AI 接管",
                winX + winW - actionW - 12,
                winY + 14,
                actionW,
                20,
                () ->
                    safe(
                        () -> {
                          if (Agent.enabled) Agent.stop("工作台急停");
                          else Agent.start();
                          rebuild();
                        }))
            .variant(Agent.enabled ? DeskButton.Variant.DANGER : DeskButton.Variant.PRIMARY);
    addChrome(quickBtn);

    if (!status.isBlank()) {
      alertCard(
          "操作提示",
          "最新",
          theme().accent(),
          () -> {
            text(status);
            button(
                "清除提示",
                () -> {
                  status = "";
                  rebuild();
                });
          });
    }

    if (section >= 0 && (!settings || settingsSearch.isBlank())) {
      button(
          "‹ 返回" + (settings ? "设置" : "账本"),
          () -> {
            saveDrafts();
            section = -1;
            scroll = parentScroll;
            rebuild();
          });
      card(DeskRoute.of(section, settings).title(), "子配置", theme().accentAlt(), pages::render);
      scroll = Math.min(scroll, maxScroll());
      repositionContentWidgets();
      return;
    }

    if (settings) {
      settingsViews.render(page);
    } else {
      switch (page) {
        case 0 -> home();
        case 1 -> world();
        case 2 -> library();
        case 3 -> knowledge();
        default -> history();
      }
    }
    scroll = Math.min(scroll, maxScroll());
    repositionContentWidgets();
  }

  /** Home: Hero + urgent approvals + live plan/execution + local command + primary controls + collapsible modules. */
  private void home() {
    UiTheme t = theme();
    if (status.isBlank()) {
      y += 66;
      maxY = Math.max(maxY, y);
    }

    if (!Agent.pendingTool.isBlank()) {
      alertCard(
          "等待人工审批 · " + Agent.pendingTool,
          "需处理",
          t.warning(),
          () -> {
            row(
                "批准一次",
                () -> {
                  onClose();
                  Agent.approve(true);
                },
                "批准本次连续流程",
                () -> {
                  onClose();
                  Agent.approveTask();
                });
            dangerButton(
                "拒绝本次操作",
                () -> {
                  Agent.approve(false);
                  rebuild();
                });
          });
    }

    var attention = Hud.FEED.attention();
    if (attention != null && !"info".equals(attention.level)) {
      alertCard(
          "alert".equals(attention.level) ? "需处理异常" : "待确认事项",
          "置顶",
          "alert".equals(attention.level) ? t.danger() : t.warning(),
          () -> {
            text(attention.text);
            button(
                "知道了（记录仍保留在历史）",
                () -> {
                  Hud.FEED.acknowledge();
                  rebuild();
                });
          });
    }

    // Live Task Card
    var p = ProgressBoard.snapshot();
    card(
        "当前运行状态",
        Agent.enabled ? (Agent.paused ? "已暂停" : "运行中") : "待命",
        Agent.enabled ? (Agent.paused ? t.warning() : t.success()) : t.textMuted(),
        () -> {
          if (p.has("goal")) {
            text("计划 · " + p.get("goal").getAsString());
            mutedText("当前 · " + p.get("current").getAsString());
            mutedText("下一步 · " + p.get("next").getAsString());
          }
          text(
              "实际执行 · "
                  + (Agent.enabled
                      ? ActionSummary.describe(Actions.job != null ? Actions.status : Agent.state)
                      : StatusFeed.restDetail(Agent.state)));
          if (RestockTask.latest != null)
            mutedText("核验进度 · " + RestockTask.latest.progress + "\n" + RestockTask.latest.phase);
        });

    // Quick Local Command Card
    card(
        "本地派单（仅本地，不发公屏）",
        "",
        0,
        () -> {
          field("command", "输入任务指令或口令", command, v -> command = v);
          row(
              "发送任务",
              () -> task(command),
              "打开底部对话",
              () -> {
                onClose();
                LocalDialogue.toggle();
              });
        });

    // Primary Controls Card
    card(
        "控制中心",
        Cooperation.active() ? "协同模式" : (Agent.enabled ? "AI 接管" : "人工"),
        Cooperation.active() ? t.accentAlt() : (Agent.enabled ? t.success() : t.textMuted()),
        () -> {
          row(
              Agent.enabled ? "交还人工" : "AI 接管",
              () -> {
                if (Agent.enabled) Agent.stop("玩家交还人工");
                else Agent.start();
                rebuild();
              },
              Agent.paused ? "继续当前任务" : "暂停当前任务",
              () -> {
                Agent.pause(!Agent.paused);
                rebuild();
              });
          row(
              "查看伙伴工作过程",
              () -> minecraft.setScreen(new TaskTimelineScreen(this)),
              WatchMode.active() ? "退出观看" : "脱手观看",
              () -> {
                WatchMode.toggle();
                rebuild();
              });
          row(
              "控制模式 · " + (!Agent.enabled ? "人工" : Cooperation.active() ? "协同" : "AI接管"),
              () -> {
                if (!Agent.enabled) Agent.start();
                else Cooperation.set(!Cooperation.active());
                rebuild();
              },
              "打开设置中心",
              () -> go(true, 0));
          if (Cooperation.active()) {
            row(
                "协同：交回 AI",
                () -> {
                  Cooperation.resume();
                  onClose();
                },
                "协同续接 · " + Store.config.cooperativeResume,
                () -> {
                  Store.config.cooperativeResume =
                      Store.config.cooperativeResume.equals("AUTO") ? "MANUAL" : "AUTO";
                  Store.saveSettingsOnly();
                  rebuild();
                });
          }
        });

    collapsibleCard(
        "home/logistics",
        "后勤委托与库存",
        "常用",
        t.accent(),
        true,
        () -> {
          row("统一委托 / 自动交付", () -> section(19, false), "多人任务队列", () -> section(18, false));
          row("常备背包", () -> section(20, false), "持续库存维护", () -> section(21, false));
          button("委托结果、常用任务与恢复记录", () -> section(22, false));
        });

    collapsibleCard(
        "home/actions",
        "直接动作",
        "",
        0,
        false,
        () -> {
          row("原版补给与搬运", () -> section(9, false), "制作与熔炼", () -> section(13, false));
          row("预制动作与农作", () -> section(10, false), "背包操作", () -> section(12, false));
        });

    var followTarget = FollowControl.savedTarget();
    collapsibleCard(
        "home/follow",
        "跟随玩家",
        FollowControl.active() ? "跟随中" : (followTarget == null ? "未选" : followTarget.name()),
        FollowControl.active() ? t.success() : t.accentAlt(),
        false,
        () -> {
          mutedText(followTarget == null ? "请选择要跟随的其他玩家" : "已选择 " + followTarget.name());
          if (FollowControl.active()) text(FollowControl.shortStatus());
          mutedText("本地对话：跟随 玩家名 / 停止跟随 · 聊天：@" + Store.config.name + " 跟我");
          row(
              "选择玩家",
              () -> minecraft.setScreen(new FollowTargetScreen(this)),
              FollowControl.active() ? "停止跟随" : "开始跟随",
              () -> {
                onClose();
                FollowControl.toggle();
              });
          button(
              "陪伴预设 · " + FollowControl.profileLabel(Store.data.followProfile),
              () -> {
                Store.data.followProfile =
                    switch (Store.data.followProfile) {
                      case "follow" -> "nether";
                      case "nether" -> "end";
                      default -> "follow";
                    };
                Store.saveWorld();
                rebuild();
              });
          if (Store.data.followProfile.equals("end")) {
            button(
                "允许跟着进出已确认的末地门 · " + (Store.data.followEndPortal ? "开" : "关"),
                () -> {
                  Store.data.followEndPortal = !Store.data.followEndPortal;
                  Store.saveWorld();
                  rebuild();
                });
          }
        });

    collapsibleCard(
        "home/mining",
        "采矿委托",
        "",
        0,
        false,
        () -> {
          field(
              "mining_goal",
              "例如：挖 16 个粗铁送回交付箱，或从这里挖台阶下到 Y=16",
              miningGoal,
              v -> miningGoal = v);
          primaryButton(
              "交给 AI 规划并执行",
              () -> task(miningGoal.isBlank() ? "" : "采矿委托：" + miningGoal.strip()));
        });

    collapsibleCard(
        "home/advanced",
        "推理强度、执行风格与自主",
        "",
        0,
        false,
        () -> {
          strategyControls(false);
          toggle("空闲自主工作", () -> Agent.autonomous, v -> Agent.autonomous = v);
          toggle(
              "合成手势批量分配",
              () -> Store.config.craftGestures,
              v -> Store.config.craftGestures = v);
        });
  }

  private void world() {
    UiTheme t = theme();
    card(
        "世界对象与库存记忆",
        Store.data.places.size() + " 地点 · " + Store.data.world.objects.size() + " 对象",
        t.accent(),
        () -> {
          mutedText("地图与库存按当前世界隔离。历史库存需要打开箱子后重新核实。");
          row("地点与仓库", () -> section(1, false), "附近世界与资源", () -> section(8, false));
          row(
              "库存记忆与变化",
              () -> minecraft.setScreen(new NoticeScreen(this, InventoryMemory.human())),
              "世界记忆与纠错",
              () -> section(2, false));
        });
    if (!Store.data.places.isEmpty()) {
      card(
          "已记命名地点",
          "",
          0,
          () -> {
            int count = 0;
            for (var e : Store.data.places.entrySet()) {
              text(
                  e.getKey()
                      + " · "
                      + e.getValue().dimension
                      + " ("
                      + e.getValue().x
                      + ", "
                      + e.getValue().y
                      + ", "
                      + e.getValue().z
                      + ")");
              if (++count >= 25) break;
            }
          });
    }
  }

  private void library() {
    UiTheme t = theme();
    if (selected.startsWith("@bundle:")) {
      String bid = selected.substring(8);
      var eb = BundleLibrary.effectiveEntries.get(bid);
      if (eb == null) {
        selected = "";
        library();
        return;
      }
      button(
          "‹ 返回开放记忆中心",
          () -> {
            selected = "";
            scroll = scrolls.getOrDefault("list", 0);
            rebuild();
          });
      SkillBundle b = eb.effective();
      var installed = new LinkedHashMap<String, String>();
      net.minecraftforge.fml.ModList.get()
          .getMods()
          .forEach(m -> installed.put(m.getModId(), m.getVersion().toString()));
      String mcVer = net.minecraft.SharedConstants.getCurrentVersion().getName();
      String envKey = EnvironmentKey.current();
      var ts = BundleLibrary.buildTrackStatusJson(b, eb.adaptation(), installed.keySet(), envKey);
      String activeTrack = ts.get("active_track").getAsString();
      String adaptStatus =
          BundleLibrary.adaptationStatus(eb.upstream(), eb.adaptation(), envKey);
      var compat = BundleLibrary.compatibilityIssues(b, installed, mcVer);
      boolean disabled = Store.config.disabledPackages.contains(b.bundleId);

      String trackBadge =
          switch (activeTrack) {
            case "track_b_reuse" -> "轨道B复用中";
            case "track_a_verified" -> "轨道A已核验";
            case "track_a_reverify" -> "环境变动待复核";
            default -> "外部待验证";
          };
      int trackColor =
          switch (activeTrack) {
            case "track_b_reuse" -> t.accent();
            case "track_a_verified" -> t.success();
            case "track_a_reverify" -> t.warning();
            default -> t.textMuted();
          };

      card(
          b.name,
          trackBadge,
          trackColor,
          () -> {
            mutedText(
                b.bundleId
                    + " · v"
                    + b.version
                    + " · 作者 "
                    + b.author
                    + " · 协议 "
                    + b.license);
            if (!b.description.isBlank()) text(b.description);
            if (!compat.blockingIssues().isEmpty()) {
              coloredText("兼容阻塞：" + compat.blockingIssues(), t.danger());
            } else if (!compat.advisories().isEmpty()) {
              coloredText("版本建议：" + compat.advisories(), t.warning());
            } else {
              coloredText("依赖环境检查通过", t.success());
            }
            if (!b.playbook.overview().isBlank()) text("玩法概述：" + b.playbook.overview());
            if (!b.playbook.manualWorkflow().isBlank()) {
              mutedText("轨道A（通用/手操）：" + b.playbook.manualWorkflow());
            }
            if (!b.playbook.ae2Delegation().isBlank()) {
              mutedText("轨道B（AE2 委派晋级）：" + b.playbook.ae2Delegation());
            }
            if (!b.playbook.pitfalls().isEmpty()) {
              mutedText("常见避坑：" + String.join("；", b.playbook.pitfalls()));
            }
          });

      card(
          "嵌套双轨进度与本地适配",
          adaptStatus.equals("active") ? "已本地适配" : adaptStatus,
          eb.adapted() ? t.accentAlt() : t.textMuted(),
          () -> {
            text(
                "轨道A核验="
                    + ts.get("track_a_verified").getAsBoolean()
                    + "（运行 "
                    + ts.get("track_a_runs").getAsInt()
                    + " 次） · 轨道B晋级="
                    + ts.get("track_b_promoted").getAsBoolean()
                    + "（运行 "
                    + ts.get("track_b_runs").getAsInt()
                    + " 次）");
            var missing = ts.getAsJsonArray("missing_track_b_preconditions");
            if (b.hasTrackB() && !missing.isEmpty()) {
              mutedText("距轨道B晋级尚缺前置：" + missing);
            }
            row(
                disabled ? "启用此记忆包" : "停用此记忆包",
                () -> {
                  if (disabled) Store.config.disabledPackages.remove(b.bundleId);
                  else Store.config.disabledPackages.add(b.bundleId);
                  Store.save();
                  SkillLibrary.refresh();
                  rebuild();
                },
                "导出脱敏 .mcskill.json",
                () -> {
                  try {
                    var req = new JsonObject();
                    req.addProperty("bundle_id", b.bundleId);
                    req.addProperty("include_local_adaptation", true);
                    var receipt = BundleLibrary.pack(req);
                    String exportedText =
                        java.nio.file.Files.readString(
                            java.nio.file.Path.of(receipt.get("file").getAsString()));
                    minecraft.keyboardHandler.setClipboard(exportedText);
                    status =
                        "已导出脱敏记忆包（并复制到剪贴板）："
                            + java.nio.file.Path.of(receipt.get("file").getAsString())
                                .getFileName();
                    rebuild();
                  } catch (Exception e) {
                    throw new IllegalArgumentException(e.getMessage());
                  }
                });
            if (eb.adaptation() != null) {
              row(
                  "查看本地适配详情",
                  () ->
                      minecraft.setScreen(
                          new NoticeScreen(this, Store.GSON.toJson(eb.adaptation().toJson()))),
                  "清除本地适配",
                  () -> {
                    try {
                      var req = new JsonObject();
                      req.addProperty("reset", true);
                      BundleLibrary.adapt(b.bundleId, req);
                      SkillLibrary.refresh();
                      status = "已恢复上游记忆包原貌";
                      rebuild();
                    } catch (Exception e) {
                      throw new IllegalArgumentException(e.getMessage());
                    }
                  });
            }
          });

      card(
          "界面映射与可执行技能",
          b.uiProfiles.size() + " 界面 / " + b.skills.size() + " 技能",
          t.accent(),
          () -> {
            for (UiProfile up : b.uiProfiles) {
              mutedText(
                  "UI["
                      + up.name
                      + "] · 槽位 "
                      + up.slots.size()
                      + " · 控件 "
                      + up.controls.size()
                      + " · 签名数 "
                      + up.match.portableSignatures().size());
            }
            for (SkillPackage sp : b.skills) {
              button("运行/查看技能 · " + sp.name, () -> detail(sp.id));
            }
            button(
                "查看完整包结构 (JSON)",
                () ->
                    minecraft.setScreen(
                        new NoticeScreen(
                            this,
                            Store.GSON.toJson(
                                BundleLibrary.inspect(
                                    b.bundleId, installed, mcVer, envKey)))));
          });
      return;
    }
    if (selected.startsWith("@legacy:")) {
      String name = selected.substring(8);
      var old = Store.data.skills.get(name);
      if (old == null) {
        selected = "";
        library();
        return;
      }
      button(
          "‹ 返回技能目录",
          () -> {
            selected = "";
            scroll = scrolls.getOrDefault("list", 0);
            rebuild();
          });
      card(
          name,
          "既有技能",
          t.accentAlt(),
          () -> {
            text(Store.skillCheck(name).status + " · 步骤 " + old.steps.size());
            row(
                "运行既有技能",
                () -> task("技能 " + name),
                "复制技能定义",
                () -> minecraft.keyboardHandler.setClipboard(Store.GSON.toJson(old)));
            button(
                "查看步骤与当前世界经验",
                () ->
                    minecraft.setScreen(
                        new NoticeScreen(
                            this,
                            Store.GSON.toJson(old)
                                + "\n"
                                + Store.GSON.toJson(Store.skillCheck(name)))));
          });
      return;
    }
    if (!selected.isBlank()) {
      var p = SkillLibrary.entries.get(selected);
      if (p == null) {
        selected = "";
        library();
        return;
      }
      button(
          "‹ 返回技能目录",
          () -> {
            selected = "";
            scroll = scrolls.getOrDefault("list", 0);
            rebuild();
          });
      card(
          p.name,
          p.id,
          t.accent(),
          () -> {
            text(SkillPackage.string(p.definition, "description", ""));
            mutedText("依赖 · " + p.requires + "\n兼容问题 · " + SkillLibrary.issues(p));
            var params =
                p.definition.has("parameters")
                    ? p.definition.getAsJsonObject("parameters")
                    : new JsonObject();
            for (var e : params.entrySet()) {
              String key = e.getKey();
              var spec = e.getValue().getAsJsonObject();
              String value = spec.has("default") ? spec.get("default").getAsString() : "";
              field(
                  p.id + "/" + key,
                  key + " · " + SkillPackage.string(spec, "type", "string"),
                  value,
                  v -> {});
            }
            primaryButton(
                "执行技能（仍按当前权限）",
                () -> {
                  var values = new JsonObject();
                  for (var e : params.entrySet()) {
                    String val =
                        drafts.getOrDefault(
                            p.id + "/" + e.getKey(),
                            e.getValue().getAsJsonObject().has("default")
                                ? e.getValue().getAsJsonObject().get("default").getAsString()
                                : "");
                    String type =
                        SkillPackage.string(e.getValue().getAsJsonObject(), "type", "string");
                    if (type.equals("integer")) values.addProperty(e.getKey(), Integer.parseInt(val));
                    else if (type.equals("boolean")) {
                      if (!Set.of("true", "false").contains(val))
                        throw new IllegalArgumentException("布尔参数true/false");
                      values.addProperty(e.getKey(), Boolean.parseBoolean(val));
                    } else values.addProperty(e.getKey(), val);
                  }
                  p.expand(values);
                  var a = new JsonObject();
                  a.addProperty("id", p.id);
                  a.add("parameters", values);
                  onClose();
                  if (!Agent.enabled) Agent.start();
                  Agent.execute("run_library_skill", a, Companion::notifyUser);
                });
            row(
                Store.config.disabledPackages.contains(p.id) ? "启用此技能" : "停用此技能",
                () -> {
                  if (Store.config.disabledPackages.contains(p.id))
                    Store.config.disabledPackages.remove(p.id);
                  else Store.config.disabledPackages.add(p.id);
                  Store.save();
                  rebuild();
                },
                "导出定义",
                () -> {
                  try {
                    status = "已导出 " + SkillLibrary.export(p.id).getFileName();
                    rebuild();
                  } catch (Exception e) {
                    throw new IllegalArgumentException(e.getMessage());
                  }
                });
            button(
                "查看定义、核验与恢复说明",
                () -> minecraft.setScreen(new NoticeScreen(this, Store.GSON.toJson(p.definition))));
          });
      return;
    }

    card(
        "开放技能记忆中心 (Bundle Hub)",
        BundleLibrary.effectiveEntries.size() + " 包",
        t.accent(),
        () -> {
          field("library-query", "搜索名称、包ID、Mod 或标签", query, v -> query = v);
          row(
              "刷新全部记忆包",
              () -> {
                SkillLibrary.refresh();
                scroll = 0;
                rebuild();
              },
              "打开 bundles 目录",
              () ->
                  net.minecraft.Util.getPlatform()
                      .openFile(
                          BundleLibrary.bundlesDir(SkillLibrary.root()).toFile()));
          row(
              "从剪贴板导入 .mcskill.json",
              () -> {
                try {
                  var imported =
                      BundleLibrary.importJson(minecraft.keyboardHandler.getClipboard());
                  SkillLibrary.refresh();
                  status = "已导入待验证记忆包：" + imported.effective().name;
                  detail("@bundle:" + imported.effective().bundleId);
                } catch (Exception e) {
                  throw new IllegalArgumentException(
                      "记忆包导入失败：" + Rules.clean(e.getMessage(), 180));
                }
              },
              "查看开放格式说明",
              () ->
                  minecraft.setScreen(
                      new NoticeScreen(
                          this,
                          "Open Skill Memory Hub (schema=2)：\n"
                              + "1. 将 *.mcskill.json 放入 library/bundles/ 或复制 JSON 后点击「从剪贴板导入」。\n"
                              + "2. 外来记忆包默认以待验证方式加载，必须经本世界服务端回执核验。\n"
                              + "3. 轨道B（AE2 委派）嵌套于轨道A内：轨道A核验通过且 AE2 条件满足后晋级复用；环境变动将自动降级复核。\n"
                              + "4. Agent 可调用 bundle_adapt 在 library/adaptations/ 做本地偏移/参数适配，并用 bundle_pack 脱敏导出回传站点。")));
          var listJson = BundleLibrary.list(query, "", "", "").getAsJsonArray("bundles");
          if (listJson.isEmpty()) {
            mutedText("暂无匹配记忆包。可放入 .mcskill.json 或让 Agent 通过 ui_profile_draft + bundle_pack 沉淀新包。");
          }
          for (var el : listJson) {
            var r = el.getAsJsonObject();
            String bid = r.get("bundle_id").getAsString();
            var ts = r.getAsJsonObject("track_status");
            String activeTrack = ts.get("active_track").getAsString();
            boolean hasIssues = !r.getAsJsonArray("issues").isEmpty();
            boolean en = !Store.config.disabledPackages.contains(bid);
            String badge =
                !en
                    ? "已停用"
                    : hasIssues
                        ? "缺依赖"
                        : switch (activeTrack) {
                          case "track_b_reuse" -> "轨道B复用中";
                          case "track_a_verified" -> "轨道A已核验";
                          case "track_a_reverify" -> "待复核";
                          default -> "待验证";
                        };
            int bColor =
                !en
                    ? t.textMuted()
                    : hasIssues
                        ? t.danger()
                        : switch (activeTrack) {
                          case "track_b_reuse" -> t.accent();
                          case "track_a_verified" -> t.success();
                          case "track_a_reverify" -> t.warning();
                          default -> t.accentAlt();
                        };
            button(r.get("name").getAsString(), () -> detail("@bundle:" + bid))
                .badge(badge, bColor);
            mutedText(
                bid
                    + " · UI "
                    + r.get("ui_profile_count").getAsInt()
                    + " / 技能 "
                    + r.get("skill_count").getAsInt()
                    + (r.get("adapted").getAsBoolean() ? " · 已本地适配" : ""));
          }
          for (String err : BundleLibrary.errors) coloredText(err, t.danger());
        });

    var localProfiles = UiProfileStore.listLocal();
    if (!localProfiles.isEmpty() || !UiProfileStore.errors.isEmpty()) {
      collapsibleCard(
          "library/ui-profiles",
          "界面映射草稿 (UiProfile)",
          localProfiles.size() + " 项",
          t.accentAlt(),
          false,
          () -> {
            mutedText("Agent 探索陌生容器后提交的结构化界面草稿。确认后可用于 gui_put_role 与 bundle_pack。");
            for (var entry : localProfiles) {
              var up = entry.profile();
              text(
                  up.name
                      + " ("
                      + up.id
                      + ") · "
                      + (entry.confirmed() ? "已确认可用" : "待人工确认")
                      + " · 槽位 "
                      + up.slots.size()
                      + " / 控件 "
                      + up.controls.size());
              row(
                  entry.confirmed() ? "重新保存确认" : "确认为本地可用",
                  () -> {
                    try {
                      UiProfileStore.confirmDraft(up.id);
                      status = "已确认界面映射：" + up.id;
                      rebuild();
                    } catch (Exception e) {
                      throw new IllegalArgumentException(e.getMessage());
                    }
                  },
                  "删除草稿",
                  () -> {
                    try {
                      UiProfileStore.deleteLocal(up.id);
                      status = "已删除草稿：" + up.id;
                      rebuild();
                    } catch (Exception e) {
                      throw new IllegalArgumentException(e.getMessage());
                    }
                  });
            }
            for (String err : UiProfileStore.errors) coloredText(err, t.danger());
          });
    }

    card(
        "技能包目录 (schema=1)",
        "",
        0,
        () -> {
          row(
              "搜索 / 刷新目录",
              () -> {
                SkillLibrary.refresh();
                scroll = 0;
                rebuild();
              },
              "打开技能文件夹",
              () ->
                  net.minecraft.Util.getPlatform()
                      .openFile(SkillLibrary.root().resolve("skills").toFile()));
          mutedText("放入 JSON 后刷新；启用不代表运行或验证。地点参数填写当前世界命名地点。");
          var rows = SkillLibrary.list(query).getAsJsonArray("skills");
          for (var el : rows) {
            var r = el.getAsJsonObject();
            String id = r.get("id").getAsString();
            boolean en = r.get("enabled").getAsBoolean();
            button(r.get("name").getAsString(), () -> detail(id))
                .badge(en ? "启用" : "停用", en ? t.success() : t.textMuted());
            mutedText(
                r.get("verification").getAsString()
                    + " · "
                    + (r.getAsJsonArray("issues").isEmpty()
                        ? "依赖检查通过"
                        : r.get("issues").toString()));
          }
          for (String error : SkillLibrary.errors) coloredText(error, t.danger());
        });

    card(
        "既有技能库",
        Store.data.skills.size() + " 项",
        t.accentAlt(),
        () -> {
          row(
              "导出既有技能库",
              () -> {
                try {
                  var p = Store.backupSkills();
                  minecraft.keyboardHandler.setClipboard(java.nio.file.Files.readString(p));
                  status = "已备份并复制技能定义";
                  rebuild();
                } catch (Exception e) {
                  throw new IllegalArgumentException(e.getMessage());
                }
              },
              "导入既有技能库",
              () -> {
                try {
                  Agent.stop("导入技能前停止");
                  Store.importSkills(minecraft.keyboardHandler.getClipboard());
                  status = "技能已合并，世界记录独立";
                  rebuild();
                } catch (Exception e) {
                  throw new IllegalArgumentException(e.getMessage());
                }
              });
          for (var entry : Store.data.skills.entrySet()) {
            if (!entry.getKey().contains(query)) continue;
            String name = entry.getKey();
            button(name + " · " + Store.skillCheck(name).status, () -> detail("@legacy:" + name));
          }
        });
  }

  private void knowledge() {
    UiTheme t = theme();
    card(
        "配方与参考资料",
        "仅作参考",
        t.accent(),
        () -> {
          mutedText("资料是参考，不能提高权限或代替实际核验。JEI 状态：" + RecipeBridge.status());
          row(
              "打开配方工坊",
              () -> minecraft.setScreen(new RecipeBrowserScreen(this)),
              "导入参考资料 (JSON)",
              () -> {
                try {
                  var j =
                      JsonParser.parseString(minecraft.keyboardHandler.getClipboard())
                          .getAsJsonObject();
                  KnowledgeNotes.put(
                      j.get("id").getAsString(),
                      j.get("source").getAsString(),
                      j.get("text").getAsString());
                  status = "参考资料已导入，待验证";
                  rebuild();
                } catch (Exception e) {
                  throw new IllegalArgumentException("需要JSON id/source/text");
                }
              });
          mutedText("共享知识目录 · " + SkillLibrary.root().resolve("knowledge"));
          row(
              "打开知识目录",
              () ->
                  net.minecraft.Util.getPlatform()
                      .openFile(SkillLibrary.root().resolve("knowledge").toFile()),
              "查看本地经验",
              () ->
                  minecraft.setScreen(
                      new NoticeScreen(this, Store.GSON.toJson(Learning.book.outcomes))));
          for (var e : KnowledgeNotes.list()) mutedText(e.toString());
        });
  }

  private void history() {
    card(
        "任务与交流历史",
        "",
        0,
        () -> {
          row(
              "任务与交付历史",
              () -> section(15, false),
              "伙伴手记 / 对话历史",
              () -> minecraft.setScreen(new CompanionHistoryScreen(this)));
          button(
              "本次测试与视觉统计",
              () ->
                  minecraft.setScreen(
                      new NoticeScreen(this, Store.GSON.toJson(RuntimeStats.snapshot()))));
          mutedText("动作 returned 不等于业务完成，原回执和核验结果均保留在此。");
        });
  }

  /** Reasoning effort and execution style are two separate settings; neither changes permissions. */
  void strategyControls(boolean advanced) {
    mutedText("模型推理强度（规划思考深度）");
    segmentRow(
        List.of(
            new SegmentOption(
                "低 · 复用技能",
                "LOW".equals(Store.config.planningEffort),
                () -> preset("LOW", Store.config.executionStyle)),
            new SegmentOption(
                "中 · 日常均衡",
                "MEDIUM".equals(Store.config.planningEffort),
                () -> preset("MEDIUM", Store.config.executionStyle)),
            new SegmentOption(
                "高 · 细化排障",
                "HIGH".equals(Store.config.planningEffort),
                () -> preset("HIGH", Store.config.executionStyle))));
    mutedText("执行风格（取放后核对方式；不改变任何权限）");
    segmentRow(
        List.of(
            new SegmentOption(
                "快速 · 复用同步槽位",
                ExecutionStyle.fluent(Store.config.executionStyle),
                () -> preset(Store.config.planningEffort, "FLUENT")),
            new SegmentOption(
                "严谨 · 每次重开核对",
                !ExecutionStyle.fluent(Store.config.executionStyle),
                () -> preset(Store.config.planningEffort, "STRICT"))));
    if (!advanced) return;
    mutedText(
        "当前上限 " + RuntimeStrategy.rounds() + " 轮规划 · " + RuntimeStrategy.label() + "（下次规划生效）");
    row(
        "预设：日常后勤 (中/快速)",
        () -> preset("MEDIUM", "FLUENT"),
        "预设：熟练挂机 (低/快速)",
        () -> preset("LOW", "FLUENT"));
    row(
        "预设：复杂任务 (高/快速)",
        () -> preset("HIGH", "FLUENT"),
        "预设：排障验收 (高/严谨)",
        () -> preset("HIGH", "STRICT"));
    toggle(
        "同步异常后临时加强检查",
        () -> Store.config.adaptiveVerification,
        v -> Store.config.adaptiveVerification = v);
    toggle(
        "原生推理强度参数（确认模型支持后开启）",
        () -> Store.config.nativeReasoning,
        v -> Store.config.nativeReasoning = v);
  }

  private void preset(String effort, String style) {
    Store.config.planningEffort = effort;
    Store.config.executionStyle = style;
    Store.saveSettingsOnly();
    rebuild();
  }

  // --- Package-private sub-editors invoked from WorkbenchPages / DeskRoute ---

  String capabilities() {
    var ids = new HashSet<String>();
    net.minecraftforge.fml.ModList.get().getMods().forEach(m -> ids.add(m.getModId()));
    return Store.GSON.toJson(CapabilityCatalog.describe("", ids));
  }

  void mode(String mode) {
    Store.config.permission = mode;
    if (!"FULL".equals(mode)) Store.config.fullGameControl = false;
    Store.saveSettingsOnly();
    if (mode.equals("FULL") && !Agent.pendingTool.isBlank()) {
      onClose();
      Agent.approve(true);
    } else rebuild();
  }

  private String draft(String key, String fallback) {
    return drafts.getOrDefault("api/" + key, fallback);
  }

  private void discardApi() {
    drafts.keySet().removeIf(k -> k.startsWith("api/"));
  }

  void connectionForm() {
    mutedText("先填写草稿，再保存或取消；输入不会影响当前正在运行的连接。");
    String protocol = draft("protocol", Store.config.apiProtocol);
    segmentRow(
        ProviderWire.PROTOCOLS.stream()
            .map(
                p ->
                    new SegmentOption(
                        p,
                        p.equals(protocol),
                        () -> {
                          saveDrafts();
                          drafts.put("api/protocol", p);
                          rebuild();
                        }))
            .toList());
    field("api/endpoint", "API 地址", Store.config.endpoint, v -> {});
    field("api/model", "模型名", Store.config.model, v -> {});
    var key = field("api/key", "API Key（仅保存本机）", Store.config.apiKey, v -> {});
    key.setFormatter(
        (v, i) ->
            net.minecraft.util.FormattedCharSequence.forward(
                "•".repeat(v.length()), net.minecraft.network.chat.Style.EMPTY));
    field("api/output", "输出 token 上限 256..12000", Integer.toString(Store.config.maxOutput), v -> {});
    field("api/turns", "基础规划轮数 1..96（中档）", Integer.toString(Store.config.maxTurns), v -> {});
    field("api/idle", "自主间隔秒 15..3600", Integer.toString(Store.config.idleSeconds), v -> {});
    field("api/port", "本机接口端口 1024..65535", Integer.toString(Store.config.mcpPort), v -> {});
    row(
        "保存连接配置",
        () -> {
          saveDrafts();
          var c = Store.config;
          ConnectionForm f;
          try {
            f =
                new ConnectionForm(
                    draft("endpoint", c.endpoint),
                    draft("model", c.model),
                    draft("key", c.apiKey),
                    draft("protocol", c.apiProtocol),
                    Integer.parseInt(draft("output", Integer.toString(c.maxOutput))),
                    Integer.parseInt(draft("turns", Integer.toString(c.maxTurns))),
                    Integer.parseInt(draft("idle", Integer.toString(c.idleSeconds))),
                    Integer.parseInt(draft("port", Integer.toString(c.mcpPort))));
          } catch (NumberFormatException e) {
            throw new IllegalArgumentException("预算、间隔和端口必须是整数");
          }
          if (!Store.claimControl()) throw new IllegalStateException("另一窗口持有数据写入权，连接草稿已保留");
          boolean
              changed =
                  !f.endpoint().equals(c.endpoint)
                      || !f.model().equals(c.model)
                      || !f.key().equals(c.apiKey)
                      || !f.protocol().equals(c.apiProtocol),
              port = f.port() != c.mcpPort;
          if (changed || port) Agent.stop("玩家应用连接配置，结束旧会话");
          c.endpoint = f.endpoint();
          c.model = f.model();
          c.apiKey = f.key();
          c.apiProtocol = f.protocol();
          c.maxOutput = f.output();
          c.maxTurns = f.turns();
          c.idleSeconds = f.idle();
          c.mcpPort = f.port();
          Store.saveSettingsOnly();
          if (port) McpBridge.restart();
          discardApi();
          status = "连接配置已保存；没有调用模型或发起测试请求";
          rebuild();
        },
        "取消草稿",
        () -> {
          discardApi();
          status = "已取消连接草稿，运行配置未改变";
          rebuild();
        });
    button("接口兼容说明", () -> minecraft.setScreen(new NoticeScreen(this, ApiWire.help())));
  }

  void identityForm() {
    field("identity/name", "伙伴名字", Store.config.name, v -> {});
    field("identity/owner", "主人ID", Store.config.owner, v -> {});
    flowField("identity/goal", "成长目标", Store.config.goal, 2000, v -> {});
    row(
        "保存身份与目标",
        () -> {
          saveDrafts();
          String name = drafts.getOrDefault("identity/name", Store.config.name).trim();
          if (name.isBlank()) throw new IllegalArgumentException("伙伴名字不能为空");
          Store.config.name = name;
          Store.config.owner = drafts.getOrDefault("identity/owner", Store.config.owner).trim();
          Store.config.goal = drafts.getOrDefault("identity/goal", Store.config.goal);
          Store.saveSettingsOnly();
          drafts.keySet().removeIf(k -> k.startsWith("identity/"));
          status = "身份与目标已保存，下次任务使用新目标";
          rebuild();
        },
        "取消身份草稿",
        () -> {
          drafts.keySet().removeIf(k -> k.startsWith("identity/"));
          rebuild();
        });
  }

  void inputSettings() {
    mutedText("导航地形策略也可在权限与安全页集中设置");
    flowField("input/keys", "LIMITED 模组按键白名单", Store.config.allowedKeys, 2000, v -> {});
    row(
        "保存按键白名单",
        () -> {
          saveDrafts();
          Store.config.allowedKeys = drafts.getOrDefault("input/keys", Store.config.allowedKeys);
          Store.saveSettingsOnly();
          status = "按键白名单已保存";
          rebuild();
        },
        "取消按键草稿",
        () -> {
          drafts.remove("input/keys");
          rebuild();
        });
    toggle("后台游戏输入", () -> Store.config.backgroundInput, v -> Store.config.backgroundInput = v);
    toggle("导航：允许破坏地形", () -> Store.config.navigationBreak, v -> Store.config.navigationBreak = v);
    toggle("导航：允许垫脚与搭路", () -> Store.config.navigationPlace, v -> Store.config.navigationPlace = v);
    button(
        "导航：一键设为仅绕行",
        () -> {
          Store.config.navigationDetourOnly = true;
          Store.config.navigationBreak = false;
          Store.config.navigationPlace = false;
          Store.saveSettingsOnly();
          rebuild();
        });
    row(
        "查看导航后端",
        () -> minecraft.setScreen(new NoticeScreen(this, BaritoneNavigation.status().toString())),
        "查看输入能力与注册按键",
        () ->
            minecraft.setScreen(
                new NoticeScreen(
                    this, PlayerControls.context() + "\n" + PlayerControls.bindings(""))));
    button(
        "复制当前物品 GUI 观察",
        () -> {
          onClose();
          try {
            minecraft.keyboardHandler.setClipboard(GuiContext.observe().toString());
            Companion.notifyUser("已复制当前界面观察");
          } catch (Exception e) {
            Companion.notifyUser(e.getMessage());
          }
        });
    mutedText("仅允许游戏内注册按键，不向宿主机注入快捷键。FULL/ASK/LIMITED 按统一权限检查。");
  }

  void advancedPermissions() {
    button(
        "死亡处理 · "
            + switch (Store.config.deathRecovery) {
              case "RESPAWN_IDLE" -> "正常重生后待命";
              case "REASSESS" -> "正常重生后重评原目标";
              default -> "等待人工重生，保留接管与连接";
            },
        () -> {
          Store.config.deathRecovery =
              switch (Store.config.deathRecovery) {
                case "PAUSE" -> "RESPAWN_IDLE";
                case "RESPAWN_IDLE" -> "REASSESS";
                default -> "PAUSE";
              };
          Store.saveSettingsOnly();
          rebuild();
        });
    field("trusted", "授权玩家（保存后生效）", Store.config.trusted, v -> {});
    button(
        "保存授权玩家",
        () -> {
          Store.config.trusted = drafts.getOrDefault("trusted", Store.config.trusted);
          Store.saveSettingsOnly();
          status = "已保存授权玩家";
          rebuild();
        });
    toggle("允许明确ID的原版资源动物狩猎", () -> Store.config.huntResources, v -> Store.config.huntResources = v);
    toggle("近处敌怪主动防卫", () -> Store.config.proactiveDefense, v -> Store.config.proactiveDefense = v);
    toggle("可选命名敌怪", () -> Store.config.attackNamed, v -> Store.config.attackNamed = v);
    toggle("允许显式指定玩家攻击", () -> Store.config.attackPlayers, v -> Store.config.attackPlayers = v);
    toggle("测试：低血不停工", () -> Store.config.testIgnoreLowHealth, v -> Store.config.testIgnoreLowHealth = v);
    toggle("Harness断连后完成已交代本地任务", () -> Store.config.harnessFinishLocal, v -> Store.config.harnessFinishLocal = v);
    toggle("新任务立即切换", () -> Store.config.interruptImmediately, v -> Store.config.interruptImmediately = v);
  }

  private void revokeTasks(CommunityBoard.Member m) {
    var active = Store.data.community.active();
    if (active != null && !m.uuid.isBlank() && active.sender.equals(m.uuid)) {
      var keep = new LinkedHashMap<CommunityBoard.Task, String>();
      for (var t : Store.data.community.tasks)
        if (!t.sender.equals(m.uuid) && Set.of("WAITING", "WAITING_MATERIALS").contains(t.state))
          keep.put(t, t.state);
      Agent.stop("活动任务成员授权撤销，现场需重新观察");
      keep.forEach((t, state) -> t.state = state);
    }
    for (var t : Store.data.community.tasks)
      if ((!m.uuid.isBlank() && t.sender.equals(m.uuid) || t.name.equalsIgnoreCase(m.name))
          && Set.of("WAITING", "WAITING_MATERIALS").contains(t.state)) {
        t.state = "CANCELLED";
        t.note = "成员授权已撤销";
      }
  }

  void members() {
    toggle(
        "默认成员汇报：详细（关=简短）",
        () -> Store.config.teamReport.equals("DETAIL"),
        v -> Store.config.teamReport = v ? "DETAIL" : "BRIEF");
    mutedText("成员按当前服务器/世界保存；所有授权成员都可在公屏要求急停。");
    field("team/name", "玩家名（在线可绑定UUID）", "", v -> {});
    {
      String nm = value("team/name", "").trim();
      if (!nm.isEmpty() && !nm.matches("[A-Za-z0-9_]{1,16}"))
        coloredText("⚠ 玩家名需 1..16 位英文、数字或下划线（当前：“" + nm + "”）", theme().warning());
    }
    cycle(
        "team/role",
        "角色",
        List.of("MEMBER", "ADMIN"),
        Map.of("MEMBER", "成员（只改自己的待办）", "ADMIN", "管理者（可调整所有待办）"));
    field("team/delivery", "本世界默认交付地点（可空）", "", v -> {});
    cycle(
        "team/report",
        "个人汇报",
        List.of("DEFAULT", "BRIEF", "DETAIL"),
        Map.of("DEFAULT", "跟随默认", "BRIEF", "简短", "DETAIL", "详细"));
    if (!value("team/id", "").isBlank()) coloredText("正在编辑已有成员；点“取消编辑 / 新建”放弃修改", theme().accent());
    field("team/search", "搜索已授权成员", "", v -> {});
    button(
        value("team/online", "false").equals("true") ? "收起在线玩家" : "从在线玩家选择",
        () -> {
          drafts.put(
              "team/online", value("team/online", "false").equals("true") ? "false" : "true");
          rebuild();
        });
    if (value("team/online", "false").equals("true") && minecraft.getConnection() != null)
      for (var online : minecraft.getConnection().getOnlinePlayers().stream().limit(24).toList()) {
        String name = online.getProfile().getName();
        button(
            "选择在线玩家 · " + name,
            () -> {
              drafts.put("team/name", name);
              rebuild();
            });
      }
    row(
        "保存成员",
        () -> {
          saveDrafts();
          String name = value("team/name", "").trim(), delivery = value("team/delivery", "").trim();
          if (!delivery.isBlank() && !Store.data.places.containsKey(delivery))
            throw new IllegalArgumentException("交付处须为本世界命名地点");
          var online =
              minecraft.getConnection() == null
                  ? null
                  : minecraft.getConnection().getPlayerInfo(name);
          String uuid = online == null ? "" : online.getProfile().getId().toString();
          String oldName =
              Store.data.members.stream()
                  .filter(m -> m.id.equals(value("team/id", "")))
                  .map(m -> m.name)
                  .findFirst()
                  .orElse("");
          var m =
              MemberDirectory.save(
                  Store.data.members,
                  value("team/id", ""),
                  name,
                  uuid,
                  value("team/role", "MEMBER"),
                  delivery,
                  value("team/report", "DEFAULT"));
          if (!oldName.isBlank() && !oldName.equalsIgnoreCase(name))
            Store.data.revokedMembers.add(MemberDirectory.key(oldName));
          Store.data.revokedMembers.remove(MemberDirectory.key(name));
          Store.data.revokedMembers.remove("uuid:" + m.uuid);
          Store.save();
          clearDrafts("team/");
          status = "成员已保存 · " + (m.uuid.isBlank() ? "待确认身份" : "UUID已绑定");
          rebuild();
        },
        "取消编辑 / 新建",
        () -> {
          clearDrafts("team/");
          rebuild();
        });
    for (var m :
        Store.data.members.stream()
            .filter(
                x ->
                    x.name
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains(value("team/search", "").toLowerCase(java.util.Locale.ROOT)))
            .toList()) {
      text(
          m.name
              + " · "
              + m.role
              + " · "
              + (m.enabled ? "已授权" : "已停用")
              + " · "
              + (m.uuid.isBlank() ? "待绑定" : "UUID已绑定")
              + "\n交付 · "
              + (m.delivery.isBlank() ? "任务指定" : m.delivery)
              + " · 汇报 "
              + m.report);
      row(
          "编辑 " + m.name,
          () -> {
            drafts.put("team/id", m.id);
            drafts.put("team/name", m.name);
            drafts.put("team/role", m.role);
            drafts.put("team/delivery", m.delivery);
            drafts.put("team/report", m.report);
            scroll = 0;
            rebuild();
          },
          m.enabled ? "停用授权" : "恢复授权",
          () -> {
            m.enabled = !m.enabled;
            if (!m.enabled) revokeTasks(m);
            Store.save();
            rebuild();
          });
      dangerButton(
          value("team/delete", "").equals(m.id) ? "确认删除 " + m.name + "（历史保留）" : "删除 " + m.name,
          () -> {
            if (!value("team/delete", "").equals(m.id)) {
              drafts.put("team/delete", m.id);
              status = "再次点击确认删除；编辑其他项目可取消";
              rebuild();
              return;
            }
            revokeTasks(m);
            Store.data.revokedMembers.add(MemberDirectory.key(m.name));
            if (!m.uuid.isBlank()) Store.data.revokedMembers.add("uuid:" + m.uuid);
            Store.data.members.remove(m);
            clearDrafts("team/");
            Store.save();
            rebuild();
          });
    }
    mutedText("公屏：@伙伴名 目标；@伙伴名 任务列表；@伙伴名 取消任务 ID；@伙伴名 修改任务 ID 新目标。");
  }

  void teamQueue() {
    mutedText("普通任务FIFO；活动任务不因新聊天被打断。ENDED=会话结束，实际业务结果看工具回执。");
    for (var t : Store.data.community.tasks) {
      text(
          t.id
              + " · "
              + t.name
              + " · "
              + t.state
              + "\n"
              + t.text
              + (t.note.isBlank() ? "" : "\n" + t.note)
              + "\n"
              + LogisticsRuntime.summary(t));
      if (Set.of("WAITING", "WAITING_MATERIALS").contains(t.state))
        button(
            "取消待办 " + t.id,
            () -> {
              Store.data.community.change(t.id, "", true, null);
              Store.save();
              rebuild();
            });
    }
    dangerButton(
        "停止当前与所有待办",
        () -> {
          Agent.stop("玩家停止多人队列");
          rebuild();
        });
  }

  private void cycle(String key, String label, List<String> values, Map<String, String> names) {
    String raw = value(key, values.get(0)).trim().toUpperCase(Locale.ROOT);
    String now = values.contains(raw) ? raw : values.get(0);
    drafts.put(key, now);
    button(
        label + " · " + names.getOrDefault(now, now) + "（点击切换）",
        () -> {
          saveDrafts();
          drafts.put(key, values.get((values.indexOf(now) + 1) % values.size()));
          rebuild();
        });
  }

  private LogisticsPlan orderDraft() {
    var o = new JsonObject();
    o.addProperty("kind", value("order/kind", "supply"));
    o.addProperty("item", value("order/item", "minecraft:iron_ingot"));
    o.addProperty("input", value("order/input", "minecraft:raw_iron"));
    o.addProperty("count", Integer.parseInt(value("order/count", "32")));
    o.addProperty("source", value("order/source", ""));
    o.addProperty("destination", value("order/destination", ""));
    o.addProperty("low", Integer.parseInt(value("order/low", "16")));
    if (!o.get("kind").getAsString().equals("stock")) o.addProperty("low", 0);
    String fallback =
        Optional.ofNullable(Store.data.community.active()).map(t -> t.delivery).orElse("");
    return LogisticsPlan.from(o, fallback);
  }

  private void loadOrder(JsonObject plan) {
    for (String k : List.of("kind", "item", "input", "count", "source", "destination", "low"))
      if (plan.has(k)) drafts.put("order/" + k, plan.get(k).getAsString());
    section(19, false);
    scroll = 0;
    rebuild();
  }

  void orderForm() {
    mutedText("明确目标后，本地执行器准备并实际交付。普通委托数量1..64；生产仅普通无NBT产物。");
    String kind = value("order/kind", "supply");
    button(
        "工序 · " + kind + "（点击切换）",
        () -> {
          var modes = List.of("supply", "craft", "smelt", "deliver", "stock");
          drafts.put("order/kind", modes.get((modes.indexOf(kind) + 1) % modes.size()));
          rebuild();
        });
    field("order/item", "目标物品ID / 完整指纹", "minecraft:iron_ingot", v -> {});
    field("order/count", kind.equals("stock") ? "补足库存目标 1..256" : "交付数量 1..64", "32", v -> {});
    field("order/source", "来源地点（搬运/维护必填）", "", v -> {});
    field("order/destination", "交付地点（可用活动成员默认值）", "", v -> {});
    if (kind.equals("smelt")) field("order/input", "普通熔炼输入ID", "minecraft:raw_iron", v -> {});
    if (kind.equals("stock")) field("order/low", "低于多少开始补货", "16", v -> {});
    field("order/name", "常用任务 / 库存目标名称", "我的后勤任务", v -> {});
    row(
        "执行并返回世界",
        () -> {
          var p = orderDraft();
          onClose();
          if (!Agent.enabled) Agent.start();
          Agent.execute("run_order", p.json(), Companion::notifyUser);
        },
        "保存为常用任务",
        () -> {
          var p = orderDraft();
          String name = value("order/name", "我的后勤任务").trim();
          if (name.isBlank() || name.length() > 80)
            throw new IllegalArgumentException("任务名1..80字符");
          if (Store.data.favoriteOrders.size() >= 32
              && !Store.data.favoriteOrders.containsKey(name))
            throw new IllegalArgumentException("常用任务最多32项");
          Store.data.favoriteOrders.put(name, p.json());
          Store.save();
          status = "已保存配置，未执行；本世界地点不跨服共享";
          rebuild();
        });
    if (kind.equals("stock")) {
      field("stock/interval", "检查间隔秒 60..86400", "300", v -> {});
      button(
          "保存库存目标（先停用）",
          () -> {
            var p = orderDraft();
            var old =
                Store.data.stockTargets.stream()
                    .filter(
                        t -> {
                          try {
                            var x = LogisticsPlan.from(t.plan, "");
                            return t.dimension.equals(LogisticsRuntime.dimension())
                                && x.destination().equals(p.destination())
                                && x.item().equals(p.item());
                          } catch (Exception e) {
                            return false;
                          }
                        })
                    .findFirst();
            if (old.isEmpty() && Store.data.stockTargets.size() >= 16)
              throw new IllegalArgumentException("库存目标最多16项");
            var t = old.orElseGet(StockTarget::new);
            int interval = Integer.parseInt(value("stock/interval", "300"));
            var candidate = new StockTarget();
            candidate.plan = p.json();
            candidate.intervalSeconds = interval;
            candidate.validate();
            t.name = value("order/name", "库存维护");
            t.dimension = LogisticsRuntime.dimension();
            t.plan = p.json();
            t.intervalSeconds = interval;
            t.enabled = false;
            t.state = "已保存，待启用";
            if (old.isEmpty()) Store.data.stockTargets.add(t);
            Store.save();
            status = "已保存，去库存维护页启用";
            rebuild();
          });
    }
  }

  void stockTargets() {
    mutedText("玩家任务优先；仅API模式空闲、同维度且来源/目标已加载时检查。连续3次受阻自动暂停。");
    primaryButton(
        "新增库存目标",
        () -> {
          drafts.put("order/kind", "stock");
          section(19, false);
        });
    for (var t : Store.data.stockTargets) {
      text(t.name + " · " + t.state + "\n间隔 " + t.intervalSeconds + "秒 · " + t.dimension);
      row(
          t.enabled ? "停用维护" : "启用维护",
          () -> {
            t.validate();
            t.enabled = !t.enabled;
            t.nextCheck = 0;
            t.failures = 0;
            t.state = t.enabled ? "等待接管与空闲检查" : "已停用";
            Store.save();
            rebuild();
          },
          "载入配置",
          () -> {
            loadOrder(t.plan);
            drafts.put("stock/interval", Integer.toString(t.intervalSeconds));
            rebuild();
          });
      dangerButton(
          "删除 " + t.name,
          () -> {
            if (Agent.routine instanceof LogisticsTask task && task.record.id.equals(t.lastOrder))
              throw new IllegalArgumentException("维护正在执行，先停止作业");
            Store.data.stockTargets.remove(t);
            Store.save();
            rebuild();
          });
    }
  }

  private void bagPreset(String name) {
    bagDraft = new BagPolicy();
    bagDraft.name = name;
    bagDraft.desired.put("minecraft:bread", name.equals("跟随补给") ? 16 : 8);
    bagDraft.desired.put("minecraft:torch", name.equals("跟随补给") ? 16 : 32);
    if (name.equals("采集出门")) bagDraft.desired.put("minecraft:iron_pickaxe", 1);
    clearDrafts("bag/");
    rebuild();
  }

  void bagForm() {
    if (bagDraft == null) bagDraft = Store.data.bag.copy();
    mutedText("先编辑并保存模板；默认不自动丢弃或替换装备。");
    row("基地后勤示例", () -> bagPreset("基地后勤"), "采集出门示例", () -> bagPreset("采集出门"));
    button("跟随补给示例", () -> bagPreset("跟随补给"));
    field("bag/name", "模板名称", bagDraft.name, v -> {});
    field("bag/source", "补给来源地点", bagDraft.source, v -> {});
    field("bag/destination", "多余物资归仓地点", bagDraft.destination, v -> {});
    field("bag/free", "期望空主背包格", Integer.toString(bagDraft.freeSlots), v -> {});
    field("bag/durability", "工具最低剩余耐久", Integer.toString(bagDraft.minDurability), v -> {});
    button(
        "委托前自动准备 · " + bagDraft.autoBeforeOrders,
        () -> {
          bagDraft.autoBeforeOrders = !bagDraft.autoBeforeOrders;
          rebuild();
        });
    button(
        "归仓多余普通物资 · " + bagDraft.unload,
        () -> {
          bagDraft.unload = !bagDraft.unload;
          rebuild();
        });
    field("bag/item", "添加物资：注册ID / 完整指纹", "minecraft:bread", v -> {});
    field("bag/count", "随身期望数量", "8", v -> {});
    button(
        "添加 / 更新期望物资",
        () -> {
          String id = value("bag/item", "minecraft:bread");
          int n = Integer.parseInt(value("bag/count", "8"));
          var copy = bagDraft.copy();
          copy.desired.put(id, n);
          copy.validate();
          bagDraft = copy;
          rebuild();
        });
    for (var e : bagDraft.desired.entrySet()) {
      String id = e.getKey();
      text(id + " ×" + e.getValue());
      row(
          bagDraft.mandatory.contains(id) ? "必要（点改期望）" : "期望（点改必要）",
          () -> {
            if (!bagDraft.mandatory.remove(id)) bagDraft.mandatory.add(id);
            rebuild();
          },
          "移除此项",
          () -> {
            bagDraft.desired.remove(id);
            bagDraft.mandatory.remove(id);
            rebuild();
          });
    }
    button(
        "将当前主手物品指纹设为固定保留",
        () -> {
          if (minecraft.player.getMainHandItem().isEmpty())
            throw new IllegalArgumentException("主手为空");
          bagDraft.protectedItems.add(WorldSense.fingerprint(minecraft.player.getMainHandItem()));
          rebuild();
        });
    for (String id : bagDraft.protectedItems) {
      text("固定保留 · " + id);
      button(
          "取消固定保留",
          () -> {
            bagDraft.protectedItems.remove(id);
            rebuild();
          });
    }
    row(
        "保存行装模板",
        () -> {
          var copy = bagDraft.copy();
          copy.name = value("bag/name", copy.name);
          copy.source = value("bag/source", copy.source).trim();
          copy.destination = value("bag/destination", copy.destination).trim();
          copy.freeSlots = Integer.parseInt(value("bag/free", Integer.toString(copy.freeSlots)));
          copy.minDurability =
              Integer.parseInt(value("bag/durability", Integer.toString(copy.minDurability)));
          copy.validate();
          Store.data.bag = copy;
          Store.save();
          bagDraft = null;
          clearDrafts("bag/");
          status = "行装已保存";
          rebuild();
        },
        "取消模板草稿",
        () -> {
          bagDraft = null;
          clearDrafts("bag/");
          rebuild();
        });
    primaryButton(
        "按已保存配置准备一次",
        () -> {
          onClose();
          if (!Agent.enabled) Agent.start();
          Agent.execute("prepare_bag", new JsonObject(), Companion::notifyUser);
        });
  }

  void logisticsHistory() {
    mutedText("数量来自唯一执行器回执，外来补给不算自产。COMPLETE 为对应委托完成。");
    text("常用任务（当前世界）");
    for (var e : Store.data.favoriteOrders.entrySet()) {
      String name = e.getKey();
      row(
          "载入 " + name,
          () -> loadOrder(e.getValue()),
          "删除收藏",
          () -> {
            Store.data.favoriteOrders.remove(name);
            Store.save();
            rebuild();
          });
    }
    var list = new ArrayList<>(Store.data.logisticsRecords.values());
    Collections.reverse(list);
    for (var r : list.stream().limit(24).toList()) {
      text(r.summary() + "\n阶段 · " + r.phase + " · 可用 " + r.prepared + " · 核实自产 " + r.produced);
      button("查看数量、阶段与回执", () -> minecraft.setScreen(new NoticeScreen(this, Store.GSON.toJson(r))));
      if (!r.state.equals("RUNNING") && !r.state.equals("COMPLETE") && r.plan.has("item"))
        button(
            "核实后续做剩余目标",
            () -> {
              onClose();
              if (!Agent.enabled) Agent.start();
              var a = new JsonObject();
              a.addProperty("record_id", r.id);
              Agent.execute("resume_order", a, Companion::notifyUser);
            });
      if (r.plan.has("item")) button("载入为新委托（非恢复）", () -> loadOrder(r.plan));
    }
  }

  // --- Rendering & Input ---

  @Override
  public void render(GuiGraphics g, int mx, int my, float delta) {
    UiTheme t = theme();

    // 1. Ambient backdrop + floating studio window
    g.fill(0, 0, width, height, t.backdrop());
    UiDraw.fillRounded(g, winX, winY, winW, winH, t.windowBg(), t.border());
    // Signature G4Neon 2px top accent gradient line
    UiDraw.fillHGradient(g, winX + 1, winY + 1, winW - 2, 2, t.accent(), t.accentAlt());

    // 2. Left Rail
    g.fill(winX + 1, winY + 3, winX + box.nav(), winY + winH - 1, t.railBg());
    g.fill(winX + box.nav(), winY + 3, winX + box.nav() + 1, winY + winH - 1, t.border());

    // Brand + Companion Name inside Rail
    g.drawString(
        font,
        font.plainSubstrByWidth(Store.config.name + " · G4Neon", box.nav() - 16),
        winX + 10,
        winY + 12,
        t.text(),
        false);
    g.drawString(
        font,
        font.plainSubstrByWidth(BuildInfo.VERSION, box.nav() - 16),
        winX + 10,
        winY + 23,
        t.accent(),
        false);

    // 3. Top Header Bar
    g.fill(winX + box.nav() + 1, winY + 3, winX + winW - 1, headerBottom, t.headerBg());
    g.fill(winX + box.nav() + 1, headerBottom, winX + winW - 1, headerBottom + 1, t.border());

    String crumb =
        settings && !settingsSearch.isBlank()
            ? "设置 / 搜索：" + settingsSearch
            : (settings ? "设置" : "账本")
                + " / "
                + (settings ? SETTINGS_NAV : LEDGER_NAV)[page]
                + (section >= 0 ? " / " + DeskRoute.of(section, settings).title() : "");
    g.drawString(
        font,
        font.plainSubstrByWidth(crumb, Math.max(40, box.width() - 96)),
        box.left(),
        winY + 14,
        t.text(),
        false);
    g.drawString(
        font,
        font.plainSubstrByWidth(
            Hud.controlLine()
                + " · "
                + (Agent.enabled
                    ? ActionSummary.describe(Actions.job != null ? Actions.status : Agent.state)
                    : StatusFeed.restDetail(Agent.state)),
            Math.max(40, box.width() - 96)),
        box.left(),
        winY + 30,
        t.textSecondary(),
        false);

    // Render Rail & Header Chrome Widgets outside content scissor
    for (var cw : chromeWidgets) cw.render(g, mx, my, delta);

    // 4. Scissored Scroll Viewport
    boolean mouseInView =
        mx >= viewLeft && mx <= viewRight && my >= box.top() && my <= box.bottom();
    int contentMx = mouseInView ? mx : -9999;
    int contentMy = mouseInView ? my : -9999;

    g.enableScissor(viewLeft, box.top(), viewRight, box.bottom());

    if (section < 0 && !settings && page == 0 && status.isBlank()) {
      int artY = box.top() - scroll;
      double artScale = Math.max(box.width() / 2172d, 58 / 724d);
      int sw = (int) Math.round(box.width() / artScale), sh = (int) Math.round(58 / artScale);
      g.blit(
          ART,
          box.left(),
          artY,
          box.width(),
          58,
          (2172 - sw) / 2f,
          (724 - sh) / 2f,
          sw,
          sh,
          2172,
          724);
      g.fill(box.left(), artY, box.left() + box.width(), artY + 58, 0x66060b16);
      UiDraw.fillRounded(g, box.left(), artY, box.width(), 58, 0, t.border());
      g.drawString(font, "基地后勤 · 让重复工作交给伙伴", box.left() + 10, artY + 12, 0xfff1f5f9, false);
      g.drawString(font, "目标明确 / 操作可查 / 结果核实", box.left() + 10, artY + 29, 0xff9fb0c9, false);
      UiDraw.drawBadge(
          g,
          font,
          box.left() + box.width() - 56,
          artY + 10,
          "G4Neon",
          t.textOnAccent(),
          t.accent(),
          t.accent());
    }

    // Card backgrounds & card headers
    for (var c : cards) {
      int cy = c.y() - scroll;
      if (cy + c.h() < box.top() || cy > box.bottom()) continue;
      if (c.alert()) {
        UiDraw.fillRounded(
            g,
            c.x(),
            cy,
            c.w(),
            c.h(),
            UiTheme.withAlpha(c.badgeColor() == 0 ? t.warning() : c.badgeColor(), t.light() ? 22 : 24),
            c.badgeColor() == 0 ? t.warning() : c.badgeColor());
      } else {
        UiDraw.fillCard(g, c.x(), cy, c.w(), c.h(), t);
      }
      if (!c.title().isEmpty()) {
        g.fill(c.x() + 1, cy + 4, c.x() + 3, cy + 14, c.alert() ? t.warning() : t.accent());
        int rightReserve = 12;
        if (!c.badge().isEmpty()) {
          int bw = UiDraw.badgeWidth(font, c.badge());
          int bc = c.badgeColor() == 0 ? t.accent() : c.badgeColor();
          UiDraw.drawBadge(
              g,
              font,
              c.x() + c.w() - bw - 8,
              cy + 4,
              c.badge(),
              bc,
              UiTheme.withAlpha(bc, 30),
              UiTheme.withAlpha(bc, 110));
          rightReserve += bw + 8;
        }
        g.drawString(
            font,
            font.plainSubstrByWidth(c.title(), Math.max(20, c.w() - rightReserve - 10)),
            c.x() + 8,
            cy + 6,
            t.text(),
            false);
        g.fill(c.x() + 6, cy + 19, c.x() + c.w() - 6, cy + 20, UiTheme.withAlpha(t.border(), 140));
      }
    }

    // Text lines, badges, keycap rows
    for (var l : lines) {
      int ly = l.y() - scroll;
      if (ly + 11 >= box.top() && ly <= box.bottom())
        g.drawString(font, l.text(), l.x(), ly, l.color(), false);
    }
    for (var b : badges) {
      int by = b.y() - scroll;
      if (by + 13 >= box.top() && by <= box.bottom())
        UiDraw.drawBadge(
            g,
            font,
            b.x(),
            by,
            b.text(),
            b.color(),
            UiTheme.withAlpha(b.color(), 28),
            UiTheme.withAlpha(b.color(), 100));
    }
    for (var k : keycaps) {
      int ky = k.y() - scroll;
      if (ky + 16 >= box.top() && ky <= box.bottom()) {
        int kw = UiDraw.drawKeycap(g, font, k.x() + 2, ky, k.key(), t);
        g.drawString(
            font,
            font.plainSubstrByWidth(k.action(), Math.max(20, k.w() - kw - 12)),
            k.x() + kw + 10,
            ky + 3,
            t.text(),
            false);
      }
    }

    // Render Scrollable Content Widgets
    for (var sw : contentWidgets) {
      if (sw.widget().visible) sw.widget().render(g, contentMx, contentMy, delta);
    }

    g.disableScissor();

    // 5. Scrollbar & Footer
    int ms = maxScroll();
    int sbX = winX + winW - 9;
    boolean sbHovered =
        mx >= sbX - 4 && mx <= sbX + 8 && my >= box.top() && my <= box.bottom();
    UiDraw.drawScrollbar(
        g, sbX, box.top(), box.bottom() - box.top(), scroll, ms, box.bottom() - box.top(), draggingScrollbar || sbHovered, t);

    g.fill(winX + box.nav() + 1, winY + winH - 22, winX + winW - 1, winY + winH - 21, t.border());
    String footerText =
        BuildInfo.ID
            + (ms > 0 ? " · 滚轮或右侧滚动条查看更多" : " · G4Neon 本地工作台")
            + (!dirtyKeys.isEmpty() ? " · 存在未保存输入草稿" : "");
    g.drawString(
        font,
        font.plainSubstrByWidth(footerText, box.width()),
        box.left(),
        winY + winH - 15,
        t.textMuted(),
        false);
  }

  private String liveFingerprint() {
    var att = Hud.FEED.attention();
    return Agent.enabled
        + "|"
        + Agent.paused
        + "|"
        + Agent.pendingTool
        + "|"
        + Cooperation.active()
        + "|"
        + WatchMode.active()
        + "|"
        + FollowControl.active()
        + "|"
        + (att != null ? att.level + ":" + att.at : "")
        + "|"
        + Hud.controlLine();
  }

  @Override
  public void tick() {
    super.tick();
    if (++ticks % 10 == 0
        && !(getFocused() instanceof EditBox)
        && section < 0
        && ((!settings && page == 0) || (settings && page == 0))) {
      String now = liveFingerprint();
      if (!now.equals(lastFingerprint)) {
        saveDrafts();
        rebuild();
      }
    }
  }

  @Override
  public boolean mouseClicked(double mx, double my, int button) {
    int sbX = winX + winW - 9;
    if (button == 0 && maxScroll() > 0 && mx >= sbX - 6 && mx <= sbX + 10 && my >= box.top() && my <= box.bottom()) {
      draggingScrollbar = true;
      dragStartMouseY = (int) my;
      dragStartScroll = scroll;
      return true;
    }
    for (int i = chromeWidgets.size() - 1; i >= 0; i--) {
      var cw = chromeWidgets.get(i);
      if (cw.mouseClicked(mx, my, button)) {
        setFocused(cw);
        return true;
      }
    }
    if (mx >= viewLeft && mx <= viewRight && my >= box.top() && my <= box.bottom()) {
      for (int i = contentWidgets.size() - 1; i >= 0; i--) {
        var sw = contentWidgets.get(i).widget();
        if (sw.visible && sw.mouseClicked(mx, my, button)) {
          setFocused(sw);
          return true;
        }
      }
    }
    setFocused(null);
    return false;
  }

  @Override
  public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
    if (draggingScrollbar && button == 0) {
      int ms = maxScroll();
      int viewH = Math.max(1, box.bottom() - box.top());
      int thumbH = Math.max(22, Math.min(viewH, (int) ((long) viewH * viewH / Math.max(1L, viewH + ms))));
      int travel = Math.max(1, viewH - thumbH);
      int deltaY = (int) my - dragStartMouseY;
      scroll = Rules.bounded(dragStartScroll + (int) ((long) deltaY * ms / travel), 0, ms);
      repositionContentWidgets();
      return true;
    }
    return super.mouseDragged(mx, my, button, dx, dy);
  }

  @Override
  public boolean mouseReleased(double mx, double my, int button) {
    if (button == 0 && draggingScrollbar) {
      draggingScrollbar = false;
      return true;
    }
    return super.mouseReleased(mx, my, button);
  }

  @Override
  public boolean mouseScrolled(double mx, double my, double delta) {
    if (mx >= winX + box.nav()) {
      int next = Rules.bounded(scroll - (int) (delta * 28), 0, maxScroll());
      if (next != scroll) {
        scroll = next;
        repositionContentWidgets();
      }
      return true;
    }
    return super.mouseScrolled(mx, my, delta);
  }

  @Override
  public void onClose() {
    saveDrafts();
    Store.save();
    if (previous instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
        && minecraft.player != null
        && minecraft.player.containerMenu == previousMenu) minecraft.setScreen(previous);
    else minecraft.setScreen(null);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
