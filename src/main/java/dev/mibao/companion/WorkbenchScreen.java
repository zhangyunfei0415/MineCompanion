package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Single-player desk: live task, portable library and effective permissions, no fake companions.
 */
public final class WorkbenchScreen extends Screen {
  private static final ResourceLocation ART =
      new ResourceLocation(Companion.ID, "textures/gui/logistics-desk-20261031.png");
  private final Screen previous;
  private final Object previousMenu;
  private boolean settings;
  private int page, scroll, y, maxY;
  private DeskLayout box;
  private String query = "", selected = "", command = "", status = "";
  private final Map<String, String> drafts = new HashMap<>();
  private final Map<String, EditBox> fields = new LinkedHashMap<>();
  private final List<Line> lines = new ArrayList<>();
  private final Map<String, Integer> scrolls = new HashMap<>();
  private BagPolicy bagDraft;
  private int section = -1, parentScroll, ticks;
  private final WorkbenchPages pages = new WorkbenchPages(this);

  private record Line(String text, int x, int y, int color) {}

  public WorkbenchScreen() {
    super(Component.literal("后勤伙伴 · 工作台"));
    var mc = net.minecraft.client.Minecraft.getInstance();
    previous = mc.screen;
    previousMenu = mc.player == null ? null : mc.player.containerMenu;
  }

  @Override
  protected void init() {
    rebuild();
  }

  private int fg() {
    return Store.config.theme.equals("纸白")
        ? 0xff18323e
        : Store.config.theme.equals("终端") ? 0xffb5f4c7 : 0xffd8e8ed;
  }

  private int muted() {
    return Store.config.theme.equals("纸白")
        ? 0xff486371
        : Store.config.theme.equals("终端") ? 0xff98bfa4 : 0xff9cb4c0;
  }

  private void text(String s) {
    for (var line : font.split(Component.literal(s), box.width() - 8)) {
      lines.add(new Line(lineText(line), box.left() + 4, y, fg()));
      y += 12;
    }
    y += 5;
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

  private DeskButton button(String label, Runnable run) {
    int py = y - scroll;
    var b = new DeskButton(label, box.left(), py, box.width(), () -> safe(run));
    b.visible = py >= box.top() && py + 22 <= box.bottom();
    addRenderableWidget(b);
    y += 28;
    maxY = Math.max(maxY, y);
    return b;
  }

  private void row(String a, Runnable ar, String b, Runnable br) {
    if (box.columns() == 1) {
      button(a, ar);
      button(b, br);
      return;
    }
    int py = y - scroll;
    var aButton = new DeskButton(a, box.left(), py, box.columnWidth(), () -> safe(ar));
    var bButton =
        new DeskButton(
            b, box.left() + box.columnWidth() + 8, py, box.columnWidth(), () -> safe(br));
    aButton.visible = bButton.visible = py >= box.top() && py + 22 <= box.bottom();
    addRenderableWidget(aButton);
    addRenderableWidget(bButton);
    y += 28;
    maxY = Math.max(maxY, y);
  }

  private EditBox field(String key, String label, String value, Consumer<String> responder) {
    return flowField(key, label, value, key.equals("command") ? 4000 : 512, responder);
  }

  EditBox flowField(String key, String label, String value, int max, Consumer<String> responder) {
    text(label);
    int py = y - scroll;
    var e = new EditBox(font, box.left() + 3, py, box.width() - 6, 20, Component.literal(label));
    e.setMaxLength(max);
    e.setValue(drafts.getOrDefault(key, value));
    e.setResponder(
        v -> {
          drafts.put(key, v);
          responder.accept(v);
        });
    responder.accept(e.getValue());
    e.visible = py >= box.top() && py + 20 <= box.bottom();
    fields.put(key, e);
    addRenderableWidget(e);
    y += 28;
    maxY = Math.max(maxY, y);
    return e;
  }

  private void safe(Runnable run) {
    try {
      run.run();
    } catch (Exception e) {
      status = Rules.clean(e.getMessage(), 180);
      rebuild();
    }
  }

  private void go(boolean root, int target) {
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

  private void saveDrafts() {
    fields.forEach((key, value) -> drafts.put(key, value.getValue()));
  }

  private void detail(String id) {
    saveDrafts();
    scrolls.put("list", scroll);
    selected = id;
    scroll = 0;
    rebuild();
  }

  private void section(int tab, boolean root) {
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
  }

  private void task(String text) {
    if (text.isBlank()) {
      status = "请先填写任务";
      return;
    }
    onClose();
    if (!Agent.enabled) Agent.start();
    Agent.submit(text, true);
  }

  private void rebuild() {
    clearWidgets();
    lines.clear();
    fields.clear();
    box = DeskLayout.of(width, height);
    y = box.top();
    maxY = y;
    var ledger = new DeskButton("账本", 8, 37, (box.nav() - 20) / 2, () -> go(false, 0));
    ledger.selected = !settings;
    addRenderableWidget(ledger);
    var cfg =
        new DeskButton(
            "设置", 10 + (box.nav() - 20) / 2, 37, (box.nav() - 20) / 2, () -> go(true, 0));
    cfg.selected = settings;
    addRenderableWidget(cfg);
    String[] nav =
        settings
            ? new String[] {"模型与连接", "权限与行为", "输入与感知", "显示与陪伴", "数据与维护"}
            : new String[] {"当前任务", "基地与库存", "技能与流程", "知识与配方", "任务历史"};
    for (int i = 0; i < nav.length; i++) {
      int target = i;
      var b = new DeskButton(nav[i], 8, 70 + i * 28, box.nav() - 16, () -> go(settings, target));
      b.selected = page == i;
      addRenderableWidget(b);
    }
    addRenderableWidget(new DeskButton("返回游戏", 8, height - 28, box.nav() - 16, this::onClose));
    if (!status.isBlank()) text("提示 · " + status);
    if (section >= 0) {
      button(
          "‹ 返回" + (settings ? "设置" : "账本"),
          () -> {
            saveDrafts();
            section = -1;
            scroll = parentScroll;
            rebuild();
          });
      text(DeskRoute.of(section, settings).title());
      pages.render();
      return;
    }
    if (settings) {
      switch (page) {
        case 0 -> connection();
        case 1 -> permissions();
        case 2 -> input();
        case 3 -> appearance();
        default -> maintenance();
      }
    } else
      switch (page) {
        case 0 -> home();
        case 1 -> world();
        case 2 -> library();
        case 3 -> knowledge();
        default -> history();
      }
  }

  /** Collapsible group header; open state is kept with the other drafts while the desk is open. */
  private boolean group(String key, String title, boolean defaultOpen) {
    boolean open =
        drafts.getOrDefault("group/" + key, Boolean.toString(defaultOpen)).equals("true");
    button(
        (open ? "▾ " : "▸ ") + title,
        () -> {
          drafts.put("group/" + key, Boolean.toString(!open));
          rebuild();
        });
    return open;
  }

  /**
   * Home: things needing the player first, then the live task, local command, common controls, then
   * tools.
   */
  private void home() {
    if (status.isBlank()) {
      y += 72;
      maxY = y;
    }
    // 1. Items waiting for the player. Errors stay here until acknowledged.
    if (!Agent.pendingTool.isBlank()) {
      text("等待授权 · " + Agent.pendingTool);
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
      button(
          "拒绝本次操作",
          () -> {
            Agent.approve(false);
            rebuild();
          });
    }
    var attention = Hud.FEED.attention();
    if (attention != null && !"info".equals(attention.level)) {
      text(("alert".equals(attention.level) ? "需处理 · " : "待处理 · ") + attention.text);
      button(
          "知道了（记录仍保留在历史）",
          () -> {
            Hud.FEED.acknowledge();
            rebuild();
          });
    }
    // 2. Live task: plan summary vs. actual execution are shown separately.
    var p = ProgressBoard.snapshot();
    if (p.has("goal")) {
      text("计划 · " + p.get("goal").getAsString());
      text("当前计划 · " + p.get("current").getAsString());
      text("下一步 · " + p.get("next").getAsString());
    }
    text(
        "实际执行 · "
            + (Agent.enabled
                ? ActionSummary.describe(Actions.job != null ? Actions.status : Agent.state)
                : StatusFeed.restDetail(Agent.state)));
    if (RestockTask.latest != null)
      text("核验进度 · " + RestockTask.latest.progress + "\n" + RestockTask.latest.phase);
    // 3. Local command.
    field("command", "在本地派一个任务（不发公屏）", command, v -> command = v);
    row(
        "发送任务",
        () -> task(command),
        "打开底部对话",
        () -> {
          onClose();
          LocalDialogue.toggle();
        });
    // 4. Common controls.
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
        "停止当前工作",
        () -> {
          Agent.stop("玩家急停");
          rebuild();
        },
        WatchMode.active() ? "退出观看" : "脱手观看",
        () -> {
          WatchMode.toggle();
          rebuild();
        });
    row(
        "查看伙伴工作过程",
        () -> minecraft.setScreen(new TaskTimelineScreen(this)),
        "控制模式 · " + (!Agent.enabled ? "人工" : Cooperation.active() ? "协同" : "AI接管"),
        () -> {
          if (!Agent.enabled) Agent.start();
          else Cooperation.set(!Cooperation.active());
          rebuild();
        });
    if (Cooperation.active())
      row(
          "协同：交回AI",
          () -> {
            Cooperation.resume();
            onClose();
          },
          "协同续接 · " + Store.config.cooperativeResume,
          () -> {
            Store.config.cooperativeResume =
                Store.config.cooperativeResume.equals("AUTO") ? "MANUAL" : "AUTO";
            Store.save();
            rebuild();
          });
    // 5. Logistics tools.
    if (group("home/logistics", "后勤委托与库存", true)) {
      row("统一委托 / 自动交付", () -> section(19, false), "多人任务队列", () -> section(18, false));
      row("常备背包", () -> section(20, false), "持续库存维护", () -> section(21, false));
      button("委托结果、常用任务与恢复记录", () -> section(22, false));
    }
    if (group("home/actions", "直接动作", false)) {
      row("原版补给与搬运", () -> section(9, false), "制作与熔炼", () -> section(13, false));
      row("预制动作与农作", () -> section(10, false), "背包操作", () -> section(12, false));
    }
    // 6. Advanced, folded by default.
    if (group("home/advanced", "推理强度、执行风格与自主", false)) {
      strategyControls(false);
      toggle("空闲自主工作", () -> Agent.autonomous, v -> Agent.autonomous = v);
      toggle("合成手势批量分配", () -> Store.config.craftGestures, v -> Store.config.craftGestures = v);
    }
  }

  private void world() {
    text("地图与库存按当前世界隔离。历史库存需要打开重核实。");
    row("地点与仓库", () -> section(1, false), "附近世界与资源", () -> section(8, false));
    row(
        "库存记忆与变化",
        () -> minecraft.setScreen(new NoticeScreen(this, InventoryMemory.human())),
        "世界记忆与纠错",
        () -> section(2, false));
    text("已记地点 " + Store.data.places.size() + " · 世界对象 " + Store.data.world.objects.size());
    for (var e : Store.data.places.entrySet()) {
      text(
          e.getKey()
              + " · "
              + e.getValue().dimension
              + " · "
              + e.getValue().x
              + ", "
              + e.getValue().y
              + ", "
              + e.getValue().z);
      if (lines.size() > 40) break;
    }
  }

  private void library() {
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
      text(name + " · " + Store.skillCheck(name).status + "\n步骤 " + old.steps.size());
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
                      Store.GSON.toJson(old) + "\n" + Store.GSON.toJson(Store.skillCheck(name)))));
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
      text(p.name + "\n" + SkillPackage.string(p.definition, "description", ""));
      text("ID · " + p.id + "\n依赖 · " + p.requires + "\n兼容问题 · " + SkillLibrary.issues(p));
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
      button(
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
              String type = SkillPackage.string(e.getValue().getAsJsonObject(), "type", "string");
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
      return;
    }
    field("library-query", "搜索名称或技能ID", query, v -> query = v);
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
    text("放入JSON后刷新；启用不代表运行或验证。地点参数填写当前世界命名地点。");
    var rows = SkillLibrary.list(query).getAsJsonArray("skills");
    for (var el : rows) {
      var r = el.getAsJsonObject();
      String id = r.get("id").getAsString();
      button(
          r.get("name").getAsString() + " · " + (r.get("enabled").getAsBoolean() ? "启用" : "停用"),
          () -> detail(id));
      text(
          r.get("verification").getAsString()
              + " · "
              + (r.getAsJsonArray("issues").isEmpty()
                  ? "依赖检查通过，运行条件另核实"
                  : r.get("issues").toString()));
    }
    for (String error : SkillLibrary.errors) text(error);
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
    text("既有技能 · 与共享包同一目录，保留原格式");
    for (var entry : Store.data.skills.entrySet()) {
      if (!entry.getKey().contains(query)) continue;
      String name = entry.getKey();
      button(name + " · " + Store.skillCheck(name).status, () -> detail("@legacy:" + name));
    }
  }

  private void knowledge() {
    text("资料是参考，不能提高权限或代替实际核验。");
    row(
        "配方工坊",
        () -> minecraft.setScreen(new RecipeBrowserScreen(this)),
        "导入参考资料",
        () -> {
          try {
            var j =
                JsonParser.parseString(minecraft.keyboardHandler.getClipboard()).getAsJsonObject();
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
    text("JEI · " + RecipeBridge.status());
    text("共享知识目录 · " + SkillLibrary.root().resolve("knowledge"));
    button(
        "打开知识目录",
        () ->
            net.minecraft.Util.getPlatform()
                .openFile(SkillLibrary.root().resolve("knowledge").toFile()));
    for (var e : KnowledgeNotes.list()) text(e.toString());
    button(
        "查看本地经验",
        () ->
            minecraft.setScreen(new NoticeScreen(this, Store.GSON.toJson(Learning.book.outcomes))));
  }

  private void history() {
    row(
        "任务与交付历史",
        () -> section(15, false),
        "对话历史",
        () -> minecraft.setScreen(new CompanionHistoryScreen(this)));
    button(
        "本次测试与视觉统计",
        () ->
            minecraft.setScreen(
                new NoticeScreen(this, Store.GSON.toJson(RuntimeStats.snapshot()))));
    text("动作 returned 不是业务完成，原回执和核验结果保留。");
  }

  /**
   * Reasoning effort and execution style are two separate settings; neither changes permissions.
   */
  private void strategyControls(boolean advanced) {
    button(
        "模型推理强度 · " + PlanningPolicy.title(Store.config.planningEffort) + "（点击切换）",
        () -> {
          Store.config.planningEffort = PlanningPolicy.next(Store.config.planningEffort);
          Store.save();
          rebuild();
        });
    button(
        "执行风格 · " + (ExecutionStyle.fluent(Store.config.executionStyle) ? "快速" : "严谨") + "（点击切换）",
        () -> {
          Store.config.executionStyle =
              ExecutionStyle.fluent(Store.config.executionStyle) ? "STRICT" : "FLUENT";
          Store.save();
          rebuild();
        });
    text("推理强度：规划想得多细。执行风格：取放后怎么核对（快速=优先用已同步槽位，严谨=每次重开）。两项都不改变权限。");
    if (!advanced) return;
    text(
        "当前上限 "
            + RuntimeStrategy.rounds()
            + " 轮规划；不改变本地动作预算。"
            + RuntimeStrategy.label()
            + "。低档复用熟悉技能，中档安排小任务，高档细化依赖与恢复。设置从下一次规划/动作生效。");
    text("常用组合（只改上面两项，不改权限）");
    row(
        "日常后勤 · 中 / 快速",
        () -> preset("MEDIUM", "FLUENT"),
        "熟练挂机 · 低 / 快速",
        () -> preset("LOW", "FLUENT"));
    row(
        "复杂任务 · 高 / 快速",
        () -> preset("HIGH", "FLUENT"),
        "排障验收 · 高 / 严谨",
        () -> preset("HIGH", "STRICT"));
    toggle(
        "同步异常后临时加强检查",
        () -> Store.config.adaptiveVerification,
        v -> Store.config.adaptiveVerification = v);
    toggle(
        "原生推理强度参数（确认模型支持后开启）",
        () -> Store.config.nativeReasoning,
        v -> Store.config.nativeReasoning = v);
    text(
        "原生参数仅接Chat/Responses，模型支持未自动验证；其他协议仅应用策略与预算。Harness收到建议，不会修改外部软件自身强度。输出token预算沿现有设置，不自动增加。临时加强最多30秒，不换模型、不提高思考强度、不重发动作。");
  }

  private void preset(String effort, String style) {
    Store.config.planningEffort = effort;
    Store.config.executionStyle = style;
    Store.save();
    rebuild();
  }

  private void connection() {
    strategyControls(true);
    text(
        "当前规划入口 · "
            + Store.config.driver
            + "\n游戏控制 · "
            + (Agent.enabled ? Agent.paused ? "暂停" : "已接管" : "人工"));
    row(
        "API 模型",
        () -> {
          Agent.stop("切换API规划入口");
          Store.config.driver = "API";
          Store.save();
          rebuild();
        },
        "本地 Harness",
        () -> {
          Agent.stop("切换Harness规划入口");
          Store.config.driver = "HARNESS";
          Store.config.mcp = true;
          Store.save();
          McpBridge.sync();
          rebuild();
        });
    var hs = HarnessController.status();
    text(
        "Harness · "
            + (hs.get("lease_active").getAsBoolean()
                ? "已连接，心跳剩余 " + hs.get("lease_remaining_ms").getAsLong() / 1000 + "s"
                : "未连接")
            + "\nAPI · "
            + Store.config.model
            + " / "
            + Store.config.apiProtocol);
    toggle(
        "精简模型工具组（按任务切换；适合小模型）",
        () -> Store.config.compactToolMenu,
        v -> Store.config.compactToolMenu = v);
    toggle(
        "模型使用精简现场（完整槽位/NBT按需读取）",
        () -> Store.config.compactObservation,
        v -> Store.config.compactObservation = v);
    field(
        "model_context_chars",
        "历史请求预算 12000..200000字符（非模型token窗口）",
        "" + Store.config.contextChars,
        v -> {
          try {
            Store.config.contextChars = Rules.bounded(Integer.parseInt(v), 12000, 200000);
          } catch (NumberFormatException ignored) {
          }
        });
    row("编辑模型与连接配置", () -> section(4, true), "Harness / 截图 / 记录", () -> section(14, true));
    button(
        "复制不含凭据的接入说明",
        () -> {
          minecraft.keyboardHandler.setClipboard(
              "Minecraft后勤伙伴 "
                  + BuildInfo.ID
                  + "；使用harness-client.py读取本机连接配置。先status、capabilities、tools，游戏中选择HARNESS并明确AI接管后connect。使用原request_id查询回执；截图需独立授权。不要公开连接凭据。");
          status = "已复制不含令牌的说明";
          rebuild();
        });
    row(
        "能力目录",
        () -> minecraft.setScreen(new NoticeScreen(this, capabilities())),
        "模型用量",
        () -> minecraft.setScreen(new NoticeScreen(this, UsageStats.human(Store.data.usageStats))));
  }

  private String draft(String key, String fallback) {
    return drafts.getOrDefault("api/" + key, fallback);
  }

  private void discardApi() {
    drafts.keySet().removeIf(k -> k.startsWith("api/"));
  }

  void connectionForm() {
    text("先填写草稿，再保存或取消；输入不会修改正在使用的连接。原生推理强度和执行策略在上一层。");
    String protocol = draft("protocol", Store.config.apiProtocol);
    button(
        "协议 · " + protocol,
        () -> {
          saveDrafts();
          var all = ProviderWire.PROTOCOLS;
          drafts.put("api/protocol", all.get((all.indexOf(protocol) + 1) % all.size()));
          rebuild();
        });
    field("api/endpoint", "API地址", Store.config.endpoint, v -> {});
    field("api/model", "模型名", Store.config.model, v -> {});
    var key = field("api/key", "API Key（仅保存本机）", Store.config.apiKey, v -> {});
    key.setFormatter(
        (v, i) ->
            net.minecraft.util.FormattedCharSequence.forward(
                "•".repeat(v.length()), net.minecraft.network.chat.Style.EMPTY));
    field("api/output", "输出token上限 256..12000", Integer.toString(Store.config.maxOutput), v -> {});
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
          Store.save();
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
          Store.save();
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
    flowField("input/keys", "LIMITED模组按键白名单", Store.config.allowedKeys, 2000, v -> {});
    row(
        "保存按键白名单",
        () -> {
          saveDrafts();
          Store.config.allowedKeys = drafts.getOrDefault("input/keys", Store.config.allowedKeys);
          Store.save();
          status = "按键白名单已保存，能力权限在权限与行为";
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
    button("导航：仅绕行", () -> {
      Store.config.navigationBreak = false;
      Store.config.navigationPlace = false;
      Store.save();
      rebuild();
    });
    button("查看导航后端", () -> minecraft.setScreen(new NoticeScreen(this, BaritoneNavigation.status().toString())));
    button(
        "查看输入能力与注册按键",
        () ->
            minecraft.setScreen(
                new NoticeScreen(
                    this, PlayerControls.context() + "\n" + PlayerControls.bindings(""))));
    button(
        "复制当前物品GUI观察",
        () -> {
          onClose();
          try {
            minecraft.keyboardHandler.setClipboard(GuiContext.observe().toString());
            Companion.notifyUser("已复制当前界面观察");
          } catch (Exception e) {
            Companion.notifyUser(e.getMessage());
          }
        });
    text("仅允许游戏内注册按键，不向宿主机注入快捷键。FULL/ASK/LIMITED按统一权限检查。");
  }

  void advancedPermissions() {
    field("trusted", "授权玩家（保存后生效）", Store.config.trusted, v -> {});
    button(
        "保存授权玩家",
        () -> {
          Store.config.trusted = drafts.getOrDefault("trusted", Store.config.trusted);
          Store.save();
          status = "已保存授权玩家";
          rebuild();
        });
    toggle(
        "允许明确ID的原版资源动物狩猎", () -> Store.config.huntResources, v -> Store.config.huntResources = v);
    toggle("近处敌怪主动防卫", () -> Store.config.proactiveDefense, v -> Store.config.proactiveDefense = v);
    toggle("可选命名敌怪", () -> Store.config.attackNamed, v -> Store.config.attackNamed = v);
    toggle("允许显式指定玩家攻击", () -> Store.config.attackPlayers, v -> Store.config.attackPlayers = v);
    toggle(
        "测试：低血不停工",
        () -> Store.config.testIgnoreLowHealth,
        v -> Store.config.testIgnoreLowHealth = v);
    toggle(
        "Harness断连后完成已交代本地任务",
        () -> Store.config.harnessFinishLocal,
        v -> Store.config.harnessFinishLocal = v);
    toggle(
        "新任务立即切换",
        () -> Store.config.interruptImmediately,
        v -> Store.config.interruptImmediately = v);
    text("这些是进阶行为选择；截图、联网、公屏与各能力权限在上一层，调试显示在输入与感知。");
  }

  private String capabilities() {
    var ids = new HashSet<String>();
    net.minecraftforge.fml.ModList.get().getMods().forEach(m -> ids.add(m.getModId()));
    return Store.GSON.toJson(CapabilityCatalog.describe("", ids));
  }

  private void permissions() {
    toggle(
        "显示 Agent 虚拟指针（不移动系统鼠标）",
        () -> Store.config.agentPointerVisible,
        v -> Store.config.agentPointerVisible = v);
    toggle(
        "完全游戏控制（免游戏操作审批，允许通用键鼠；公屏/截图仍独立）",
        () -> Store.config.fullGameControl,
        v -> Store.config.fullGameControl = v);
    // Effective permissions summary card
    var perm = PermissionView.current();
    text("═ 当前生效权限汇总 ═");
    text(
        "游戏操作："
            + (perm.get("mode").getAsString().equals("FULL")
                ? "完全放权"
                : perm.get("mode").getAsString().equals("ASK") ? "逐次批准" : "有限模式"));
    text(
        "  采集："
            + (perm.get("mine").getAsBoolean() ? "✓" : "✗")
            + " · 种植放置："
            + (perm.get("plant_and_place").getAsBoolean() ? "✓" : "✗")
            + " · 主动清怪："
            + (perm.get("hunt").getAsBoolean() ? "✓" : "✗"));
    text(
        "  容器："
            + (perm.get("containers").getAsBoolean() ? "✓" : "✗")
            + " · GUI："
            + (perm.get("gui").getAsBoolean() ? "✓" : "✗")
            + " · 通用输入："
            + (perm.get("generic_input").getAsBoolean() ? "✓" : "✗"));
    text(
        "联网资料："
            + (perm.get("network_learning").getAsBoolean() ? "✓" : "✗")
            + " · 截图："
            + (perm.get("screenshots").getAsBoolean() ? "✓" : "✗")
            + " · 公屏回复："
            + (perm.get("public_replies").getAsBoolean() ? "✓" : "✗"));
    text(
        "执行风格："
            + (ExecutionStyle.fluent(Store.config.executionStyle) ? "快速（复用已同步）" : "严谨（每次重开核对）"));
    text("说明：权限控制审批流程，不能实现尚未支持的能力。完整能力通过 capability_probe / machine_adapters 查询。");
    y += 10;

    button(
        "应用玩家式放权预设（FULL + 自主准备 + 附近补给）",
        () -> {
          Agent.stop("玩家应用放权预设，旧作业结束");
          Store.config.permission = "FULL";
          Store.config.playerAutonomy = true;
          Store.config.nearbySupplyPickup = true;
          Store.config.executionStyle = "FLUENT";
          Store.config.interruptImmediately = false;
          Store.save();
          status = "已应用；联网、截图、公屏与模型选择未改变。需要时重新AI接管";
          rebuild();
        });
    toggle(
        "玩家式：自主准备与有界恢复策略", () -> Store.config.playerAutonomy, v -> Store.config.playerAutonomy = v);
    toggle(
        "玩家式：空闲时拾取身边补给（仅FULL/API）",
        () -> Store.config.nearbySupplyPickup,
        v -> Store.config.nearbySupplyPickup = v);
    button("主人与授权成员", () -> section(17, true));
    text("当前世界／服务器指令配置（不授予服务器OP权限）");
    var commands = CommandSettings.profile();
    button(
        "已检测模组与接口能力",
        () ->
            minecraft.setScreen(
                new NoticeScreen(this, Store.GSON.toJson(IntegrationStatus.view()))));
    field(
        "player_commands",
        "普通玩家指令与别名（逗号分隔）",
        commands.playerCommands,
        v -> {
          commands.playerCommands = v;
          Store.save();
        });
    toggle(
        "允许自定义单行指令（仍须Agent操作授权）", () -> commands.customCommands, v -> commands.customCommands = v);
    toggle(
        "允许clone / summon测试指令",
        () -> commands.advancedCommands,
        v -> commands.advancedCommands = v);
    toggle("允许广泛对象选择器（@a/@e等）", () -> commands.broadSelectors, v -> commands.broadSelectors = v);
    toggle(
        "允许Agent打开聊天框并自由打字",
        () -> Store.config.allowChatTyping,
        v -> Store.config.allowChatTyping = v);
    toggle(
        "允许Agent提交聊天框（可发公屏或新插件指令）",
        () -> Store.config.allowChatSubmit,
        v -> Store.config.allowChatSubmit = v);
    text("自由打字与提交独立于自动回复；全部开启时原生Enter可发送任意单行内容。指令技能仍走本服指令策略。");
    field(
        "command_radius",
        "坐标命令最大半径 1..1024",
        "" + commands.radius,
        v -> {
          try {
            commands.radius = Rules.bounded(Integer.parseInt(v), 1, 1024);
            Store.save();
          } catch (NumberFormatException ignored) {
          }
        });
    field(
        "command_volume",
        "区域命令最大方块数 1..32768",
        "" + commands.maxBlocks,
        v -> {
          try {
            commands.maxBlocks = Rules.bounded(Integer.parseInt(v), 1, 32768);
            Store.save();
          } catch (NumberFormatException ignored) {
          }
        });

    text("当前模式 · " + Store.config.permission + "。FULL免逐次游戏操作审批；独立的截图、联网、公屏选择保留。");
    row("LIMITED 有限权限", () -> mode("LIMITED"), "ASK 逐次 / 本流程批准", () -> mode("ASK"));
    button("FULL 完全游戏放权", () -> mode("FULL"));
    button(
        "执行风格 · "
            + (ExecutionStyle.fluent(Store.config.executionStyle) ? "快速（优先复用同步）" : "严谨（每次重开核对）"),
        () -> {
          Store.config.executionStyle =
              ExecutionStyle.fluent(Store.config.executionStyle) ? "STRICT" : "FLUENT";
          Store.save();
          rebuild();
        });
    text("快速：普通箱子优先核对已收槽位更新，必要时刷新一次，再退回重开；不重复发取放。严谨：保留每次重开。两者均保留数量核验与急停；复杂机器沿原适配器。");
    if (!Agent.pendingTool.isBlank()) {
      text("待授权 · " + Agent.pendingTool);
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
      button(
          "拒绝本次操作",
          () -> {
            Agent.approve(false);
            rebuild();
          });
    }
    toggle("有限模式：容器与仓储", () -> Store.config.allowContainers, v -> Store.config.allowContainers = v);
    toggle("有限模式：GUI操作", () -> Store.config.allowGui, v -> Store.config.allowGui = v);
    toggle("有限模式：丢弃物品", () -> Store.config.allowDrop, v -> Store.config.allowDrop = v);
    toggle("有限模式：实体交互", () -> Store.config.allowEntityUse, v -> Store.config.allowEntityUse = v);
    toggle("有限模式：采集", () -> Store.config.allowMine, v -> Store.config.allowMine = v);
    toggle("有限模式：种植与放置", () -> Store.config.allowPlace, v -> Store.config.allowPlace = v);
    toggle("有限模式：通用输入", () -> Store.config.allowInput, v -> Store.config.allowInput = v);
    toggle("有限模式：主动清怪", () -> Store.config.allowHunt, v -> Store.config.allowHunt = v);
    toggle("本地自卫", () -> Store.config.selfDefense, v -> Store.config.selfDefense = v);
    toggle("求助通知", () -> Store.config.helpNotifications, v -> Store.config.helpNotifications = v);
    toggle("Harness截图", () -> Store.config.harnessImages, v -> Store.config.harnessImages = v);
    toggle("联网参考资料", () -> Store.config.web, v -> Store.config.web = v);
    toggle("回复公屏", () -> Store.config.publicReply, v -> Store.config.publicReply = v);
    button("进阶行为与授权玩家", () -> section(5, true));
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
    text("默认简短：接单、关键审批阻塞和结案；详细仍有公屏频率限制。仅在公屏回复已开启时发送，完整记录保留本地。");
    text("成员按当前服务器/世界保存。管理者可调整任何待办，成员只改自己的待办；派出的游戏任务都继承本机当前权限。聊天不能改权限或成员配置。旧主人/授权玩家在身份确认后迁入。");
    field("team/name", "玩家名（在线可绑定UUID）", "", v -> {});
    {
      String nm = value("team/name", "").trim();
      if (!nm.isEmpty() && !nm.matches("[A-Za-z0-9_]{1,16}"))
        text("⚠ 玩家名需 1..16 位英文、数字或下划线（当前：“" + nm + "”）");
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
    if (!value("team/id", "").isBlank()) text("正在编辑已有成员；点“取消编辑 / 新建”放弃修改");
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
      button(
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
    text("公屏：@伙伴名 目标；@伙伴名 任务列表；@伙伴名 取消任务 ID；@伙伴名 修改任务 ID 新目标。所有授权成员都可要求急停。身份不明的系统聊天不执行。");
  }

  void teamQueue() {
    text("普通任务FIFO；活动任务不因新聊天被打断。ENDED=会话结束，实际业务结果看工具回执。暂停/停止/断线不会自动重放旧目标。");
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
    button(
        "停止当前与所有待办",
        () -> {
          Agent.stop("玩家停止多人队列");
          rebuild();
        });
  }

  private String value(String key, String fallback) {
    return drafts.getOrDefault(key, fallback);
  }

  /** Click-to-cycle choice instead of free text, so only valid values can be saved. */
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
    text("明确目标后，本地执行器准备并实际交付。普通委托数量1..64；生产仅普通无NBT产物，熔炼输入另填。旧制作口令不变。");
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
    text("玩家任务优先；仅API模式空闲、同维度且来源/目标已加载时检查。先复核库存，低于下限再补。停止/重进服需重新启用；连续3次受阻自动暂停。");
    button(
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
      button(
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
    if (bagDraft == null)
      bagDraft = Store.GSON.fromJson(Store.GSON.toJson(Store.data.bag), BagPolicy.class);
    text("先编辑并保存模板。物资可分必要/期望，默认不自动丢弃或替换装备；归仓保留快捷栏、NBT物品、工具与固定物品。");
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
          var copy = Store.GSON.fromJson(Store.GSON.toJson(bagDraft), BagPolicy.class);
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
          var copy = Store.GSON.fromJson(Store.GSON.toJson(bagDraft), BagPolicy.class);
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
    button(
        "按已保存配置准备一次",
        () -> {
          onClose();
          if (!Agent.enabled) Agent.start();
          Agent.execute("prepare_bag", new JsonObject(), Companion::notifyUser);
        });
  }

  void logisticsHistory() {
    text("数量来自唯一执行器回执，外来补给不算自产。COMPLETE为对应委托完成，普通ENDED仍仅会话结束。");
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

  private void mode(String mode) {
    Store.config.permission = mode;
    Store.save();
    if (mode.equals("FULL") && !Agent.pendingTool.isBlank()) {
      onClose();
      Agent.approve(true);
    } else rebuild();
  }

  private void toggle(String title, BooleanSupplier get, Consumer<Boolean> set) {
    button(
        title + " · " + (get.getAsBoolean() ? "开启" : "关闭"),
        () -> {
          set.accept(!get.getAsBoolean());
          Store.save();
          rebuild();
        });
  }

  private void input() {
    toggle("导航自动打开木门/栅栏门", () -> Store.config.autoOpenDoors, v -> Store.config.autoOpenDoors = v);
    text("动作只作用于当前游戏窗口，停止释放本模组持有的键，不清空玩家人工长按。");
    toggle("目标与碰撞诊断显示", () -> Store.config.debugScene, v -> Store.config.debugScene = v);
    button(
        "查看当前战斗认知边界",
        () -> {
          var p = minecraft.player;
          String weapon =
              p == null
                  ? "未进入世界"
                  : MeleeOps.weapon(p.getMainHandItem()) ? "当前主手可被普通近战识别" : "当前主手不符合普通近战武器规则";
          minecraft.setScreen(
              new NoticeScreen(
                  this,
                  weapon
                      + "\n"
                      + "索敌使用已加载客户端实体与射线；通用清怪仍有目标白名单。\n"
                      + "装备选择目前主要看普通攻击属性，不代表Mine and Slash实际DPS排名。职业资源、技能冷却和召唤敌我规则仍需针对服务器版本适配。\n"
                      + (p == null ? "" : DynamicScene.inspect())));
        });
    row("主副手、背包与模组按键", () -> section(12, true), "视觉辅助与示范", () -> section(11, true));
    button(
        WatchMode.active() ? "退出观看" : "开启脱手观看",
        () -> {
          WatchMode.toggle();
          rebuild();
        });
    text(
        "面板 · "
            + Companion.PANEL.getTranslatedKeyMessage().getString()
            + "\n观看 · "
            + Companion.WATCH.getTranslatedKeyMessage().getString()
            + "\n对话 · "
            + Companion.DIALOGUE.getTranslatedKeyMessage().getString()
            + "\n急停 · "
            + Companion.STOP.getTranslatedKeyMessage().getString());
  }

  private void appearance() {
    toggle("原生API流式接收", () -> Store.config.apiStreaming, v -> Store.config.apiStreaming = v);
    toggle(
        "前台过程：详细（关=简洁）", () -> Store.config.processDetailed, v -> Store.config.processDetailed = v);
    text("Home进入本地输入后，可点侧栏的简洁/详细切换；详细区滚轮翻阅、点击展开，AI继续工作。接口不支持stream时关闭流式，不自动重发。公开工作说明与实际回执分开展示。");
    text("主题预览与观看布局。切换主题不会改变权限或任务。");
    for (String theme : List.of("深海", "纸白", "终端", "原版")) {
      var b =
          button(
              theme,
              () -> {
                Store.config.theme = theme;
                Store.save();
                rebuild();
              });
      b.selected = Store.config.theme.equals(theme);
    }
    button("拖动观看布局 / 缩放 / 复位", () -> minecraft.setScreen(new HudLayoutEditor(this)));
    row(
        "侧栏、头像与本地对话",
        () -> section(7, true),
        "恢复默认侧栏布局",
        () -> {
          Store.config.hudLeft = false;
          Store.config.hudY = 42;
          Store.config.hudWidth = 190;
          Store.config.hudOpacity = 85;
          Store.save();
          rebuild();
        });
    button("人设与跨世界偏好", () -> section(16, true));
    text("通知自动淡出，历史在账本；头像只显示实际工作状态。");
  }

  private void leaseSetting(int seconds) {
    HarnessController.configureLease(seconds);
    status = "Harness租约已应用：" + HarnessLeasePolicy.label(seconds);
    rebuild();
  }

  private void maintenance() {
    text("Harness租约 · " + HarnessLeasePolicy.label(Store.config.harnessLeaseSeconds));
    row("5分钟", () -> leaseSetting(300), "1小时", () -> leaseSetting(3600));
    row("6小时", () -> leaseSetting(21600), "12小时", () -> leaseSetting(43200));
    row("24小时", () -> leaseSetting(86400), "直到手动停止（不按时间到期）", () -> leaseSetting(0));
    field(
        "lease/minutes",
        "自定义时长（分钟，1..43200）",
        Integer.toString(Math.max(1, Store.config.harnessLeaseSeconds / 60)),
        v -> {});
    button(
        "应用自定义时长",
        () -> leaseSetting(HarnessLeasePolicy.customMinutes(value("lease/minutes", "60"))));
    text(
        "设置立即应用当前连接，不撤销控制权。有限时长为空闲超时：心跳或有效请求会续期；手动模式不按时间到期，F9、主动释放、关闭接口、退出世界仍立即撤销。新版客户端重连时自动跟随所选时长。");
    field("library-root", "共享库目录（留空使用本实例；修改后保存）", Store.config.libraryFolder, v -> {});
    button(
        "保存目录并刷新",
        () -> {
          Store.config.libraryFolder = drafts.getOrDefault("library-root", "").trim();
          Store.save();
          SkillLibrary.refresh();
          status = SkillLibrary.errors.isEmpty() ? "目录已刷新" : String.join("；", SkillLibrary.errors);
          rebuild();
        });
    row(
        "备份 / 恢复 / 数据目录",
        () -> section(6, true),
        "本次运行统计",
        () ->
            minecraft.setScreen(
                new NoticeScreen(this, Store.GSON.toJson(RuntimeStats.snapshot()))));
    text("技能定义可共享；世界库存、设施和当前流程不共享。备份不包含API密钥或Harness令牌。");
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float delta) {
    boolean light = Store.config.theme.equals("纸白");
    g.fill(
        0,
        0,
        width,
        height,
        light
            ? 0xffeef3f3
            : Store.config.theme.equals("终端")
                ? 0xff06170f
                : Store.config.theme.equals("原版") ? 0xff24211c : 0xff091722);
    g.fill(
        0,
        0,
        box.nav(),
        height,
        light
            ? 0xffdce7e8
            : Store.config.theme.equals("终端")
                ? 0xff0b2618
                : Store.config.theme.equals("原版") ? 0xff353025 : 0xff0e2430);
    g.fill(box.nav(), 0, box.nav() + 1, height, light ? 0xffb2cbce : 0xff2a4b57);
    g.drawString(
        font, font.plainSubstrByWidth(Store.config.name, box.nav() - 16), 9, 14, fg(), false);
    g.drawString(
        font,
        font.plainSubstrByWidth(
            (settings ? "设置" : "账本")
                + " / "
                + (settings
                        ? new String[] {"模型与连接", "权限与行为", "输入与感知", "显示与陪伴", "数据与维护"}
                        : new String[] {"当前任务", "基地与库存", "技能与流程", "知识与配方", "任务历史"})
                    [page]
                + (section >= 0 ? " / " + DeskRoute.of(section, settings).title() : ""),
            box.width()),
        box.left(),
        18,
        fg(),
        false);
    g.drawString(
        font,
        font.plainSubstrByWidth(
            Hud.controlLine()
                + " · "
                + (Agent.enabled
                    ? ActionSummary.describe(Actions.job != null ? Actions.status : Agent.state)
                    : StatusFeed.restDetail(Agent.state)),
            box.width()),
        box.left(),
        44,
        muted(),
        false);
    g.enableScissor(box.left(), box.top(), width - 10, box.bottom());
    if (section < 0 && !settings && page == 0 && status.isBlank()) {
      int artY = box.top() - scroll;
      double artScale = Math.max(box.width() / 2172d, 64 / 724d);
      int sw = (int) Math.round(box.width() / artScale), sh = (int) Math.round(64 / artScale);
      g.blit(
          ART,
          box.left(),
          artY,
          box.width(),
          64,
          (2172 - sw) / 2f,
          (724 - sh) / 2f,
          sw,
          sh,
          2172,
          724);
      g.fill(box.left(), artY, box.left() + box.width(), artY + 64, 0x22071923);
      g.drawString(font, "基地后勤 · 让重复工作交给伙伴", box.left() + 10, artY + 12, 0xffe8f4ee, false);
      g.drawString(font, "目标明确 / 操作可查 / 结果核实", box.left() + 10, artY + 30, 0xffa6c6cc, false);
    }
    for (var l : lines) g.drawString(font, l.text, l.x, l.y - scroll, l.color, false);
    g.disableScissor();
    super.render(g, mx, my, delta);
    g.drawString(
        font,
        font.plainSubstrByWidth(
            BuildInfo.ID + " · " + (maxY > box.bottom() ? "滚轮查看更多" : "本地工作台"), box.width()),
        box.left(),
        height - 17,
        muted(),
        false);
  }

  @Override
  public void tick() {
    super.tick();
    if (++ticks % 20 == 0
        && !(getFocused() instanceof EditBox)
        && section < 0
        && ((!settings && page == 0) || (settings && page == 0))) {
      saveDrafts();
      rebuild();
    }
  }

  @Override
  public boolean mouseScrolled(double mx, double my, double delta) {
    if (mx > box.nav()) {
      saveDrafts();
      scroll = Rules.bounded(scroll - (int) (delta * 28), 0, Math.max(0, maxY - box.bottom() + 4));
      rebuild();
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
