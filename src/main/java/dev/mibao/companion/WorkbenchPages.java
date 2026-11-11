package dev.mibao.companion;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** Domain editors hosted inside WorkbenchScreen; no second Screen or navigation tree. */
final class WorkbenchPages {
  private final WorkbenchScreen ui;
  private final Minecraft minecraft = Minecraft.getInstance();
  private final net.minecraft.client.gui.Font font = minecraft.font;
  private int tab, page, x, w, height, scroll;
  private boolean settingsRoot;
  private EditBox command, place, item, count;
  private String selected = "", commandDraft = "";
  private int editingMemory = -1, presetType;
  private String presetItem = "minecraft:iron_ingot",
      presetCount = "32",
      presetRadius = "8",
      interfaceNote = "",
      worldQuery = "",
      worldSelected = "";
  private String supplyDestination = "交付箱",
      supplySource = "材料仓库",
      supplyItem = "minecraft:iron_ingot",
      supplyTarget = "128";

  private record Label(FormattedCharSequence text, int x, int y) {
    Label(String text, int x, int y) {
      this(FormattedCharSequence.forward(text, Style.EMPTY), x, y);
    }
  }

  private record Pending(String label, Runnable run) {}

  private final List<Pending> pending = new ArrayList<>();
  private int buttonY;

  private void flushButtons() {
    if (pending.size() == 2)
      ui.flowRow(
          pending.get(0).label(),
          pending.get(0).run(),
          pending.get(1).label(),
          pending.get(1).run());
    else if (pending.size() == 1) ui.flowButton(pending.get(0).label(), pending.get(0).run());
    pending.clear();
  }

  private final List<Label> labels =
      new AbstractList<>() {
        public Label get(int i) {
          throw new IndexOutOfBoundsException();
        }

        public int size() {
          return 0;
        }

        public boolean add(Label l) {
          flushButtons();
          ui.flowText(l.text());
          return true;
        }
      };

  WorkbenchPages(WorkbenchScreen ui) {
    this.ui = ui;
  }

  void open(int route, boolean settings) {
    tab = route;
    settingsRoot = settings;
    controlSettings = settings && route == 12;
    page = 0;
  }

  void render() {
    pending.clear();
    x = 0;
    w = ui.flowWidth();
    height = ui.height;
    switch (tab) {
      case 1 -> places();
      case 2 -> memories();
      case 3 -> skills();
      case 4 -> ui.connectionForm();
      case 5 -> ui.advancedPermissions();
      case 6 -> maintenance();
      case 7 -> sidebar();
      case 8 -> world();
      case 9 -> supplies();
      case 10 -> presets();
      case 11 -> visual();
      case 12 -> controls();
      case 13 -> production();
      case 14 -> harness();
      case 15 -> ledgerHistory();
      case 16 -> identitySettings();
      case 17 -> ui.members();
      case 18 -> ui.teamQueue();
      case 19 -> ui.orderForm();
      case 20 -> ui.bagForm();
      case 21 -> ui.stockTargets();
      case 22 -> ui.logisticsHistory();
      default -> dashboard();
    }
    flushButtons();
  }

  private void button(String label, int px, int py, int width, Runnable run) {
    if (!pending.isEmpty() && (py != buttonY || pending.size() == 2)) flushButtons();
    buttonY = py;
    pending.add(new Pending(label, run));
  }

  private EditBox field(String title, String value, int y, int max, Consumer<String> change) {
    flushButtons();
    String context =
        tab == 2
            ? Integer.toString(editingMemory)
            : tab == 8 ? worldSelected : tab == 10 ? Integer.toString(presetType) : "";
    String key = "section/" + tab + "/" + page + "/" + context + "/" + title;
    if (tab == 1 && title.equals("地点名")) ui.clearDrafts(key);
    return ui.flowField(key, title, value, max, change);
  }

  private void rebuild() {
    if (tab == 12) settingsRoot = controlSettings;
    ui.componentChanged(tab, settingsRoot);
  }

  private void navigate(int target) {
    page = 0;
    tab = target;
    rebuild();
  }

  private void onClose() {
    ui.onClose();
  }

  private void harness() {
    button(
        Store.config.mcp ? "本机接口：开" : "本机接口：关",
        0,
        0,
        0,
        () -> {
          Agent.stop("切换本机接口");
          Store.config.mcp = !Store.config.mcp;
          Store.save();
          McpBridge.sync();
          rebuild();
        });
    button(
        "本地记录：" + HarnessController.recordStatus(),
        0,
        0,
        0,
        () -> {
          try {
            if (HarnessController.recordStatus().equals("未记录")) HarnessController.startRecord();
            else HarnessController.stopRecord();
            rebuild();
          } catch (Exception e) {
            Companion.notifyUser(e.getMessage());
          }
        });
    button(
        "复制本机MCP认证配置（请勿分享）",
        0,
        0,
        0,
        () -> {
          minecraft.keyboardHandler.setClipboard(McpBridge.config());
          Companion.notifyUser("认证配置已复制，仅供可信本机客户端使用");
        });
    labels.add(new Label("截图授权统一在权限与行为；观看与头像在各自设置。", 0, 0));
    labels.add(new Label("接入使用harness-client.py，状态和截图均按当前权限执行。", 0, 0));
  }

  private String productionItem = "minecraft:iron_pickaxe", productionCount = "1";

  private void production() {
    field("制作/输入ID", productionItem, 55, 160, s -> productionItem = s);
    field("数量1..64", productionCount, 84, 2, s -> productionCount = s);
    button("制作并核验", x, 109, 120, () -> run("制作 " + productionItem + "|" + productionCount));
    button(
        "熔炼并核验",
        x + 126,
        109,
        Math.min(130, w - 126),
        () -> run("熔炼 " + productionItem + "|" + productionCount));
    button("准备工作台和炉", x, 136, 135, () -> run("准备工坊"));
    button(
        "恢复记录",
        x + 141,
        136,
        Math.min(120, w - 141),
        () -> minecraft.setScreen(new NoticeScreen(ui, RecoveryLedger.human())));
    button("使用手中物品", x, 163, 135, () -> run("使用 *|40|main|hold"));
    button("放置指定方块", x + 141, 163, Math.min(120, w - 141), () -> run("放置 " + productionItem));
    labels.add(new Label(cut("制作=新增产物；熔炼=输入数量。普通原版/模组工作台配方，普通炉。", w), x, 194));
    labels.add(new Label(cut("材料放随身背包，快捷栏满时使用自动工作槽；煤/木炭提供燃料。", w), x, 210));
    labels.add(new Label(cut("合成栏/炉初始清空；缺料按流程核查补料来源；未知机器先观察。", w), x, 226));
    button("配方工坊", x, 248, 100, () -> minecraft.setScreen(new RecipeBrowserScreen(ui)));
    button(
        "查询产物配方",
        x + 106,
        248,
        Math.min(130, w - 106),
        () -> {
          try {
            minecraft.setScreen(
                new NoticeScreen(
                    ui,
                    RecipeBridge.require().lookup(productionItem, "output", "", 0, 10).toString()));
          } catch (Exception e) {
            Companion.notifyUser(e.getMessage());
          }
        });
    button(
        "只读制作计划",
        x,
        275,
        130,
        () -> {
          try {
            minecraft.setScreen(
                new NoticeScreen(
                    ui,
                    RecipeText.plan(
                        RecipeKnowledge.plan(productionItem, Integer.parseInt(productionCount)))));
          } catch (Exception e) {
            Companion.notifyUser(e.getMessage());
          }
        });
  }

  private String controlItem = "minecraft:iron_pickaxe",
      controlDestination = "hotbar",
      controlSlot = "-1";
  private boolean controlSettings;

  private void controls() {
    if (controlSettings) {
      ui.inputSettings();
      return;
    }
    button("打开背包", x, 47, 100, () -> run("背包"));
    button(
        "按键配置",
        x + 106,
        47,
        Math.min(130, w - 106),
        () -> {
          controlSettings = true;
          rebuild();
        });
    field("物品/指纹", controlItem, 85, 256, s -> controlItem = s);
    field("目标位置", controlDestination, 113, 32, s -> controlDestination = s);
    field("快捷栏索引", controlSlot, 141, 2, s -> controlSlot = s);
    button(
        "移动并核验",
        x,
        165,
        105,
        () -> run("备物 " + controlItem + "|" + controlDestination + "|" + controlSlot));
    button("吃一件普通食物", x + 111, 165, Math.min(130, w - 111), () -> run("吃东西"));
    button("收好鼠标物品", x, 190, 105, () -> run("收好鼠标物品"));
    button("关闭物品界面", x + 111, 190, Math.min(130, w - 111), () -> run("关闭界面"));
    labels.add(new Label(cut("目标hotbar/offhand/head/chest/legs/feet；-1自动准备工作槽", w), x, 219));
    button("合并主背包物品", x, 239, 130, () -> run("整理背包"));
    button(
        "库存记忆与变化",
        x + 136,
        239,
        Math.min(145, w - 136),
        () -> minecraft.setScreen(new NoticeScreen(ui, InventoryMemory.human())));
    button(
        "复制库存记忆",
        x,
        266,
        130,
        () -> {
          minecraft.keyboardHandler.setClipboard(InventoryMemory.observe().toString());
          Companion.notifyUser("库存记忆已复制，含物品NBT；不包含API配置。");
        });
    labels.add(new Label(cut("合并只处理主背包，保留快捷栏、装备、副手；不丢弃物品。", w), x, 296));
  }

  private void dashboard() {
    button(
        Agent.enabled ? "人工接管 / 休息" : "AI 接管",
        x,
        47,
        112,
        () -> {
          if (Agent.enabled) Agent.stop("人工接管，休息");
          else Agent.start();
          rebuild();
        });
    button(
        Agent.autonomous ? "自主：开" : "自主：关",
        x + 118,
        47,
        92,
        () -> {
          Agent.autonomous = !Agent.autonomous;
          rebuild();
        });
    button(
        "停止 " + Companion.STOP.getTranslatedKeyMessage().getString(),
        x + 216,
        47,
        Math.min(110, w - 216),
        () -> {
          Agent.stop("面板紧急停止");
          rebuild();
        });
    command = field("任务口令", commandDraft, 81, 4000, s -> commandDraft = s);
    button(
        "下达并返回游戏",
        x,
        106,
        140,
        () -> {
          String s = command.getValue();
          commandDraft = "";
          onClose();
          if (!Agent.enabled) Agent.start();
          Agent.submit(s);
        });
    button(
        "复制当前状态",
        x + 146,
        106,
        124,
        () -> {
          minecraft.keyboardHandler.setClipboard(Actions.observe().toString());
          Companion.log("当前游戏状态已复制");
        });
    int bw = Math.max(60, Math.min(120, (w - 6) / 2));
    button("补给与搬运", x, 136, bw, () -> navigate(9));
    button("预制技能", x + bw + 6, 136, bw, () -> navigate(10));
    button(
        "背包与操作",
        x,
        162,
        bw,
        () -> {
          controlSettings = false;
          navigate(12);
        });
    button("生产工坊", x + bw + 6, 162, bw, () -> navigate(13));
    button(
        "当前计划与进度",
        x,
        190,
        bw,
        () -> minecraft.setScreen(new NoticeScreen(ui, WorkLedger.current())));
    button("刷新账本", x + bw + 6, 190, bw, () -> rebuild());
    int y = 222;
    for (String line : WorkLedger.current().split("\n")) {
      labels.add(new Label(cut(line, w), x, y));
      y += 14;
    }
    labels.add(
        new Label(
            cut("面板打开时动作暂缓；" + Companion.STOP.getTranslatedKeyMessage().getString() + " 随时停止。", w),
            x,
            y + 5));
  }

  private void ledgerHistory() {
    button("世界记忆备份", x, 47, 116, () -> backup());
    button(
        "恢复最近备份",
        x + 122,
        47,
        130,
        () -> {
          Agent.stop("恢复记忆前停止");
          try {
            Store.restoreLatest();
            rebuild();
          } catch (Exception e) {
            Companion.notifyUser(e.getMessage());
          }
        });
    button(
        "生产恢复记录",
        x,
        73,
        116,
        () -> minecraft.setScreen(new NoticeScreen(ui, RecoveryLedger.human())));
    button(
        "本窗口消息",
        x + 122,
        73,
        130,
        () -> {
          settingsRoot = true;
          tab = 7;
          page = 1;
          scroll = 0;
          rebuild();
        });
    labels.add(new Label(cut("按世界保存；补给来源未确认时不冒认生产成果。", w), x, 104));
    var entries =
        Store.data.workLedger == null ? List.<WorkLedger.Entry>of() : Store.data.workLedger;
    int size = Math.max(1, (height - 170) / 43);
    for (int j = 0; j < size && page * size + j < entries.size(); j++) {
      var e = entries.get(entries.size() - 1 - page * size - j);
      int y = 126 + j * 43;
      labels.add(
          new Label(cut(e.dimension + " · " + java.time.Instant.ofEpochMilli(e.at), w - 52), x, y));
      labels.add(new Label(cut(e.text, w - 52), x, y + 14));
      button(
          "详情",
          x + w - 48,
          y,
          48,
          () -> minecraft.setScreen(new NoticeScreen(ui, e.text + "\n" + e.dimension)));
    }
    if (entries.isEmpty()) labels.add(new Label("暂无本世界任务/补给记录。", x, 130));
    pager(entries.size(), size);
  }

  private void places() {
    place = field("地点名", selected, 50, 80, s -> selected = s);
    button(
        "记准星 / 脚下",
        x,
        74,
        118,
        () -> {
          if (minecraft.player != null) {
            Companion.notifyUser(Actions.rememberPlace(place.getValue()));
            rebuild();
          }
        });
    button("前往", x + 123, 74, 60, () -> run("去 " + place.getValue()));
    button(
        "删除地点",
        x + 188,
        74,
        85,
        () -> {
          Store.data.places.remove(place.getValue());
          Store.save();
          rebuild();
        });
    item = field("物品ID/名称", "minecraft:iron_ingot", 105, 256, s -> {});
    count = field("数量", "32", 131, 4, s -> {});
    button(
        "取到背包",
        x,
        155,
        100,
        () -> run("取 " + place.getValue() + "|" + item.getValue() + "|" + count.getValue()));
    button(
        "存入仓库",
        x + 106,
        155,
        100,
        () -> run("存 " + place.getValue() + "|" + item.getValue() + "|" + count.getValue()));
    int available = Math.max(1, (height - 240) / 29);
    List<Store.Place> entries = new ArrayList<>(Store.data.places.values());
    for (int j = 0; j < available && page * available + j < entries.size(); j++) {
      var p = entries.get(page * available + j);
      int y = 190 + j * 29;
      button(
          p.name,
          x,
          y,
          Math.min(140, w / 2),
          () -> {
            selected = p.name;
            rebuild();
          });
      labels.add(
          new Label(
              p.x + ", " + p.y + ", " + p.z + "  " + p.dimension.replace("minecraft:", ""),
              x + Math.min(145, w / 2 + 5),
              y + 5));
    }
    pager(entries.size(), available);
  }

  private void supplies() {
    field("交付地点", supplyDestination, 52, 128, s -> supplyDestination = s);
    field("来源地点", supplySource, 80, 128, s -> supplySource = s);
    field("物品ID/名称", supplyItem, 108, 256, s -> supplyItem = s);
    field("目标库存", supplyTarget, 136, 4, s -> supplyTarget = s);
    button(
        "补足一次并返回",
        x,
        160,
        130,
        () ->
            run(
                "补给 "
                    + supplyDestination
                    + "|"
                    + supplyItem
                    + "|"
                    + supplyTarget
                    + "|"
                    + supplySource));
    button(
        Agent.paused ? "继续任务" : "暂停任务",
        x + 136,
        160,
        90,
        () -> {
          Agent.pause(!Agent.paused);
          rebuild();
        });
    button(
        "停止",
        x + 232,
        160,
        65,
        () -> {
          Agent.stop("补给面板停止");
          rebuild();
        });
    labels.add(new Label("目标指箱内最终数量；来源留空则查已知普通仓库。", x, 193));
    labels.add(new Label("先在地点页/世界认知命名箱子；无需 API 即可测试。", x, 207));
    labels.add(new Label("每一步仍需权限；只补给一次，不会后台反复循环。", x, 221));
    button(
        "查看完整任务回执",
        x,
        244,
        160,
        () -> {
          var card = RestockTask.latest;
          if (card != null) minecraft.setScreen(new NoticeScreen(ui, Store.GSON.toJson(card)));
          else Companion.notifyUser("还没有本窗口的补给任务记录");
        });
    button(
        "采集满包交付：" + (Store.data.resourceDelivery.isBlank() ? "未设置" : Store.data.resourceDelivery),
        x,
        275,
        Math.min(w, 280),
        () -> {
          try {
            com.google.gson.JsonObject a = new com.google.gson.JsonObject();
            a.addProperty("place", supplyDestination);
            Actions.target(a);
            Store.data.resourceDelivery = supplyDestination;
            Store.save();
            rebuild();
          } catch (Exception e) {
            Companion.notifyUser("请先把上方交付地点标记为当前世界的箱子");
          }
        });
    button(
        "制作缺料来源：" + (Store.data.productionSource.isBlank() ? "未设置" : Store.data.productionSource),
        x,
        302,
        Math.min(w, 280),
        () -> {
          try {
            com.google.gson.JsonObject a = new com.google.gson.JsonObject();
            a.addProperty("place", supplySource);
            Actions.target(a);
            Store.data.productionSource = supplySource;
            Store.save();
            rebuild();
          } catch (Exception e) {
            Companion.notifyUser("请先把上方来源地点标记为当前世界的普通仓库");
          }
        });
    button(
        "关闭采集自动交付",
        x,
        329,
        135,
        () -> {
          Store.data.resourceDelivery = "";
          Store.save();
          rebuild();
        });
    button(
        "关闭制作自动补料",
        x + 141,
        329,
        135,
        () -> {
          Store.data.productionSource = "";
          Store.save();
          rebuild();
        });
    labels.add(new Label(cut("点上方两项采用输入的命名地点；只影响新任务，按世界保存。", w), x, 360));
  }

  private boolean presetFertilize = true, presetPlant = true, presetBow;

  private void presets() {
    var preset = SkillCatalog.ALL.get(presetType);
    String id = preset.id();
    boolean farm = Set.of("farm", "plant", "fertilize", "farm_cycle").contains(id);
    button(
        "技能：" + preset.name(),
        x,
        46,
        Math.min(w, 280),
        () -> {
          presetType = (presetType + 1) % SkillCatalog.ALL.size();
          String next = SkillCatalog.ALL.get(presetType).id();
          presetItem =
              switch (next) {
                case "farm", "plant", "fertilize", "farm_cycle" -> "minecraft:wheat";
                case "mine" -> "#forge:ores/iron";
                case "hunt" -> "minecraft:zombie";
                case "craft" -> "minecraft:iron_pickaxe";
                case "smelt" -> "minecraft:raw_iron";
                default -> "minecraft:iron_ingot";
              };
          presetCount =
              Set.of("farm", "plant", "fertilize", "farm_cycle").contains(next)
                  ? "4"
                  : Set.of("craft", "smelt", "hunt", "workshop").contains(next) ? "1" : "16";
          presetRadius = "8";
          rebuild();
        });
    field(
        farm ? "作物ID" : id.equals("mine") ? "方块/标签" : id.equals("hunt") ? "生物ID" : "物品ID",
        presetItem,
        82,
        256,
        v -> presetItem = v);
    field(farm ? "株数" : "数量", presetCount, 110, 4, v -> presetCount = v);
    field("搜索半径", presetRadius, 138, 2, v -> presetRadius = v);
    int y = 166;
    if (id.equals("transport")) {
      field("来源箱", supplySource, y + 5, 128, v -> supplySource = v);
      field("交付箱", supplyDestination, y + 33, 128, v -> supplyDestination = v);
      y += 62;
    }
    if (id.equals("hunt")) {
      button(
          presetBow ? "战斗：普通弓" : "战斗：近战",
          x,
          y,
          220,
          () -> {
            presetBow = !presetBow;
            rebuild();
          });
      y += 28;
    }
    if (id.equals("farm_cycle")) {
      button(
          presetPlant ? "初始播种：开" : "初始播种：关",
          x,
          y,
          140,
          () -> {
            presetPlant = !presetPlant;
            rebuild();
          });
      button(
          presetFertilize ? "骨粉催熟：开" : "骨粉催熟：关",
          x + 146,
          y,
          Math.max(100, w - 146),
          () -> {
            presetFertilize = !presetFertilize;
            rebuild();
          });
      y += 28;
    }
    button(
        "运行并返回游戏",
        x,
        y,
        145,
        () -> {
          try {
            var args = new com.google.gson.JsonObject();
            args.addProperty("count", Integer.parseInt(presetCount));
            args.addProperty("radius", Integer.parseInt(presetRadius));
            if (farm) args.addProperty("crop", presetItem);
            else if (id.equals("mine")) args.addProperty("block", presetItem);
            else if (id.equals("hunt")) {
              args.addProperty("entity", presetItem);
              args.addProperty("style", presetBow ? "bow" : "melee");
            } else args.addProperty("item", presetItem);
            if (id.equals("transport")) {
              args.addProperty("source", supplySource);
              args.addProperty("destination", supplyDestination);
            }
            if (id.equals("farm_cycle")) {
              args.addProperty("plant", presetPlant);
              args.addProperty("fertilize", presetFertilize);
            }
            var request = new com.google.gson.JsonObject();
            request.addProperty("name", preset.id());
            request.add("parameters", args);
            run("预制 " + request);
          } catch (Exception e) {
            Companion.notifyUser(e.getMessage());
          }
        });
    button(
        "参数与核验说明",
        x + 151,
        y,
        140,
        () -> minecraft.setScreen(new NoticeScreen(ui, Store.GSON.toJson(preset))));
    button(
        "查看技能经验",
        x,
        y + 28,
        145,
        () -> minecraft.setScreen(new NoticeScreen(ui, Store.GSON.toJson(Learning.book.outcomes))));
    button(
        "只读准备检查",
        x,
        y + 56,
        145,
        () -> {
          var args = new com.google.gson.JsonObject();
          args.addProperty("kind", farm ? "farm" : id);
          args.addProperty("item", presetItem);
          minecraft.setScreen(
              new NoticeScreen(ui, TaskReadiness.human(TaskReadiness.inspect(args))));
        });
    labels.add(new Label(cut("完整农作：先准备种子/骨粉；已有作物时关闭初始播种。", w), x, y + 86));
  }

  private void visual() {
    button(
        "视觉模式：" + VisualAssist.mode(),
        x,
        47,
        150,
        () -> {
          VisualAssist.nextMode();
          rebuild();
        });
    button(
        VisualAssist.recording ? "停止本地示范" : "开始本地示范",
        x + 156,
        47,
        130,
        () -> {
          if (!VisualAssist.mode().equals("DEMO")) {
            Companion.notifyUser("先切换到 DEMO 模式");
            return;
          }
          VisualAssist.toggleRecording();
          rebuild();
        });
    labels.add(new Label(cut("OFF关闭 / AUTO按需辅助 / DEMO人工示范仅本地保存", w), x, 77));
    field("布局说明", interfaceNote, 101, 1500, s -> interfaceNote = s);
    button(
        "确认当前菜单布局",
        x,
        125,
        145,
        () -> {
          try {
            VisualAssist.confirm(interfaceNote);
            Companion.notifyUser("已保存人工确认的布局经验；操作仍须实时验证");
          } catch (Exception e) {
            Companion.notifyUser(e.getMessage());
          }
        });
    button(
        "查看当前界面线索",
        x + 151,
        125,
        135,
        () -> minecraft.setScreen(new NoticeScreen(ui, VisualAssist.context().toString())));
    button(
        "导出经验副本",
        x,
        152,
        145,
        () -> {
          try {
            Store.save();
            var p = Learning.backup();
            minecraft.keyboardHandler.setClipboard(Store.GSON.toJson(Learning.book));
            Companion.notifyUser("经验已备份并复制；不含地图或密钥");
          } catch (Exception e) {
            Companion.notifyUser("经验导出失败");
          }
        });
    button(
        "清除本菜单布局经验",
        x + 151,
        152,
        135,
        () -> {
          try {
            Learning.ensureWritable();
          } catch (Exception e) {
            Companion.notifyUser(e.getMessage());
            return;
          }
          String key = VisualAssist.signature();
          Learning.book.interfaces.remove(key);
          Store.data.visualNotes.remove(VisualAssist.worldKey(key));
          Store.save();
          Companion.notifyUser("已清除本菜单的视觉线索和布局说明");
        });
    button(
        "导入经验（先备份）",
        x,
        179,
        145,
        () -> {
          try {
            Learning.restore(minecraft.keyboardHandler.getClipboard());
            Companion.notifyUser("经验已导入，旧经验已自动备份；世界地图独立保存");
          } catch (Exception e) {
            Companion.notifyUser("经验导入失败：" + e.getMessage());
          }
        });
    labels.add(new Label(cut("AUTO仅在陌生菜单/目标含糊/执行失败时附图，每任务最多2张。", w), x, 203));
    labels.add(new Label(cut("DEMO记录点击或使用前后观察与截图，最多20步，不上传。", w), x, 219));
    labels.add(new Label(cut("数量/成功始终以实际同步核对；陌生机器仍需人工验证。", w), x, 235));
  }

  private void memories() {
    command =
        field(
            editingMemory < 0 ? "新增记忆" : "编辑记忆",
            editingMemory >= 0 && editingMemory < Store.data.facts.size()
                ? Store.data.facts.get(editingMemory).text
                : "",
            52,
            2000,
            s -> {});
    button(
        "保存事实",
        x,
        77,
        90,
        () -> {
          if (!command.getValue().isBlank()) {
            if (editingMemory >= 0 && editingMemory < Store.data.facts.size()) {
              Store.journal("编辑记忆：" + Store.data.facts.get(editingMemory).text);
              Store.data.facts.set(editingMemory, new Store.Fact(command.getValue(), "玩家修正"));
              Store.save();
            } else Store.fact(command.getValue(), "玩家面板");
          }
          editingMemory = -1;
          ui.clearDrafts("section/2/");
          rebuild();
        });
    button("导出 / 备份", x + 96, 77, 105, () -> backup());
    button(
        "从剪贴板导入",
        x + 207,
        77,
        110,
        () -> {
          Agent.stop("导入记忆前停止");
          try {
            var d = Store.GSON.fromJson(minecraft.keyboardHandler.getClipboard(), Store.Data.class);
            if (d == null
                || d.facts == null
                || d.places == null
                || d.skills == null
                || d.journal == null) throw new IllegalArgumentException("数据不完整");
            Store.backup();
            Store.importData(d);
            editingMemory = -1;
            ui.clearDrafts("section/2/");
            rebuild();
            Companion.notifyUser("导入完成，原记录已自动备份。");
          } catch (Exception e) {
            Companion.notifyUser("导入失败，请复制完整的导出 JSON 文件内容。");
          }
        });
    int available = Math.max(1, (height - 155) / 43);
    int total = Store.data.facts.size();
    for (int j = 0; j < available && page * available + j < total; j++) {
      int idx = total - 1 - (page * available + j);
      var f = Store.data.facts.get(idx);
      int y = 112 + j * 43;
      labels.add(new Label(cut(f.text, w - 120), x, y));
      labels.add(new Label(cut(f.source + " / " + f.at, w - 120), x, y + 12));
      button(
          "编辑",
          x + w - 105,
          y,
          50,
          () -> {
            editingMemory = idx;
            rebuild();
          });
      button(
          "删除",
          x + w - 51,
          y,
          50,
          () -> {
            Store.journal("删除记忆：" + Store.data.facts.get(idx).text);
            Store.data.facts.remove(idx);
            editingMemory = -1;
            ui.clearDrafts("section/2/");
            Store.save();
            rebuild();
          });
    }
    pager(total, available);
  }

  private void skills() {
    labels.add(new Label("全局技能共享；地点和验证记录留在各自世界，仍受权限限制。", x, 49));
    button(
        "导出技能库",
        x,
        70,
        110,
        () -> {
          try {
            var p = Store.backupSkills();
            minecraft.keyboardHandler.setClipboard(java.nio.file.Files.readString(p));
            Companion.notifyUser("技能库已备份并复制；不含世界地图或 API Key：" + p.getFileName());
          } catch (Exception e) {
            Companion.notifyUser("技能导出失败：" + e.getMessage());
          }
        });
    button(
        "从剪贴板导入技能",
        x + 116,
        70,
        150,
        () -> {
          Agent.stop("导入技能前停止");
          try {
            Store.importSkills(minecraft.keyboardHandler.getClipboard());
            Companion.notifyUser("已合并技能库；当前世界地图和库存保持原记录。");
            rebuild();
          } catch (Exception e) {
            Companion.notifyUser("技能导入失败：" + e.getMessage());
          }
        });
    List<String> names = new ArrayList<>(Store.data.skills.keySet());
    int available = Math.max(1, (height - 155) / 48);
    for (int j = 0; j < available && page * available + j < names.size(); j++) {
      String name = names.get(page * available + j);
      var s = Store.data.skills.get(name);
      var check = Store.skillCheck(name);
      int y = 105 + j * 48;
      labels.add(new Label(cut(name + " · " + check.status, w - 146), x, y));
      labels.add(
          new Label(
              "步骤 " + s.steps.size() + " | 本世界完成 " + check.successes + " / 失败 " + check.failures,
              x,
              y + 13));
      button("运行", x + w - 139, y, 62, () -> run("技能 " + name));
      button(
          "复制代码",
          x + w - 72,
          y,
          72,
          () -> minecraft.keyboardHandler.setClipboard(Store.GSON.toJson(s)));
    }
    if (names.isEmpty()) labels.add(new Label("先完成真实任务，再让 AI 保存成技能。", x, 112));
    pager(names.size(), available);
  }

  private void identitySettings() {
    ui.identityForm();
    button(
        Store.config.conversationMemory ? "对话上下文：开" : "对话上下文：关",
        x,
        75,
        165,
        () -> {
          Store.config.conversationMemory = !Store.config.conversationMemory;
          Store.save();
          rebuild();
        });
    button(
        "查看人设与偏好",
        x,
        103,
        145,
        () ->
            minecraft.setScreen(
                new NoticeScreen(
                    ui,
                    CompanionIdentity.persona() + "\n\n偏好：\n" + CompanionIdentity.preferences())));
    button(
        "从剪贴板设置人设",
        x,
        131,
        180,
        () -> {
          try {
            CompanionIdentity.set("persona.md", minecraft.keyboardHandler.getClipboard());
            Companion.notifyUser("人设已保存，旧文件已备份；下次任务生效。");
          } catch (Exception e) {
            Companion.notifyUser(e.getMessage());
          }
        });
    button(
        "从剪贴板设置偏好",
        x,
        159,
        180,
        () -> {
          try {
            CompanionIdentity.set("preferences.md", minecraft.keyboardHandler.getClipboard());
            Companion.notifyUser("跨世界偏好已保存；不会写入世界坐标库存。");
          } catch (Exception e) {
            Companion.notifyUser(e.getMessage());
          }
        });
    button("查看本世界对话", x, 187, 145, () -> minecraft.setScreen(new CompanionHistoryScreen(ui)));
    button(
        "导出对话与人设",
        x,
        215,
        145,
        () -> {
          try {
            Companion.notifyUser("已导出：" + CompanionIdentity.export().getFileName());
          } catch (Exception e) {
            Companion.notifyUser(e.getMessage());
          }
        });
    labels.add(new Label(cut("人设/偏好跨世界共享；对话按世界隔离，不恢复旧动作。", w), x, 246));
  }

  private void maintenance() {
    button("备份 / 导出全部世界记忆", 0, 0, 0, () -> backup());
    button(
        "恢复最近备份",
        0,
        0,
        0,
        () -> {
          Agent.stop("恢复记忆前停止");
          try {
            Store.restoreLatest();
            Companion.notifyUser("已恢复，旧数据另有备份");
            rebuild();
          } catch (Exception e) {
            Companion.notifyUser(e.getMessage());
          }
        });
    button("打开数据目录", 0, 0, 0, () -> net.minecraft.Util.getPlatform().openFile(Store.ROOT.toFile()));
    labels.add(new Label("记忆备份不含模型密钥；共享技能定义、世界库存与经验分别保存。", 0, 0));
  }

  private void sidebar() {
    if (page == -2) {
      button(
          "形象：" + (Store.config.avatar.equals("CHATGPT") ? "ChatGPT龙娘" : "DeepSeek鲸娘"),
          x,
          47,
          180,
          () -> {
            Store.config.avatar = Store.config.avatar.equals("DEEPSEEK") ? "CHATGPT" : "DEEPSEEK";
            Store.save();
            rebuild();
          });
      button(
          "风格：" + Store.config.avatarStyle,
          x,
          75,
          180,
          () -> {
            Store.config.avatarStyle =
                switch (Store.config.avatarStyle) {
                  case "MOE" -> "MC";
                  case "MC" -> "OFF";
                  default -> "MOE";
                };
            Store.save();
            rebuild();
          });
      button(
          Store.config.localDialogue ? "本地交流浮层：开" : "本地交流浮层：关",
          x,
          103,
          180,
          () -> {
            Store.config.localDialogue = !Store.config.localDialogue;
            Store.save();
            rebuild();
          });
      labels.add(new Label(cut("立绘避让侧栏；菜单中隐藏立绘，短窗口自动收起。", w), x, 139));
      labels.add(new Label(cut("交流闲置15秒后收起；Home打开输入，不发公屏。", w), x, 155));
      button(
          "返回显示与交流",
          x,
          185,
          150,
          () -> {
            page = 0;
            scroll = 0;
            rebuild();
          });
      return;
    }
    if (page == -1) {
      labels.add(new Label("侧栏始终保留完整消息；公屏只发短摘要并限制频率。", x, 50));
      button(
          Store.config.publicReply ? "AI回复：侧栏+公屏" : "AI回复：仅侧栏",
          x,
          76,
          220,
          () -> {
            Store.config.publicReply = !Store.config.publicReply;
            Store.save();
            rebuild();
          });
      button(
          Store.config.publicAlerts ? "异常/审批：侧栏+公屏" : "异常/审批：仅侧栏",
          x,
          102,
          220,
          () -> {
            Store.config.publicAlerts = !Store.config.publicAlerts;
            Store.save();
            rebuild();
          });
      button(
          Store.config.publicReceipts ? "完成回执：侧栏+公屏" : "完成回执：仅侧栏",
          x,
          128,
          220,
          () -> {
            Store.config.publicReceipts = !Store.config.publicReceipts;
            Store.save();
            rebuild();
          });
      button(
          Store.config.hudTask ? "侧栏任务卡：开" : "侧栏任务卡：关",
          x,
          158,
          220,
          () -> {
            Store.config.hudTask = !Store.config.hudTask;
            Store.save();
            rebuild();
          });
      button(
          Store.config.hudAlerts ? "异常置顶：开" : "异常置顶：关",
          x,
          184,
          220,
          () -> {
            Store.config.hudAlerts = !Store.config.hudAlerts;
            Store.save();
            rebuild();
          });
      button(
          "已阅当前异常",
          x,
          214,
          106,
          () -> {
            Hud.FEED.acknowledge();
            rebuild();
          });
      button(
          "清空本窗口消息",
          x + 112,
          214,
          130,
          () -> {
            Hud.FEED.clear();
            rebuild();
          });
      button(
          "返回侧栏设置",
          x,
          height - 31,
          130,
          () -> {
            page = 0;
            rebuild();
          });
      return;
    }
    if (page > 0) {
      var peer = Agent.enabled ? null : StatusLink.remote();
      var messages = peer != null ? peer.notices : Hud.FEED.snapshot();
      labels.add(new Label(peer != null ? "小号消息记录（本机只读同步）" : "本窗口消息记录", x, 49));
      int available = Math.max(1, (height - 130) / 40), offset = (page - 1) * available;
      if (messages != null)
        for (int j = 0; j < available && offset + j < messages.size(); j++) {
          var n = messages.get(messages.size() - 1 - offset - j);
          int y = 75 + j * 40;
          var wrapped =
              font.split(
                  Component.literal(n.text + (n.repeats > 1 ? " ×" + n.repeats : "")),
                  Math.max(60, w - 52));
          for (int k = 0; k < Math.min(3, wrapped.size()); k++)
            labels.add(new Label(wrapped.get(k), x, y + k * 11));
          button("全文", x + w - 48, y, 46, () -> minecraft.setScreen(new NoticeScreen(ui, n.text)));
        }
      button(
          "返回侧栏设置",
          x,
          height - 31,
          110,
          () -> {
            page = 0;
            rebuild();
          });
      button("更新消息", x + 116, height - 31, 80, () -> rebuild());
      button(
          "下一页",
          x + 202,
          height - 31,
          70,
          () -> {
            if (messages != null && offset + available < messages.size()) page++;
            else page = 1;
            rebuild();
          });
      return;
    }
    button(
        Store.config.hud ? "侧栏：开 F7" : "侧栏：关 F7",
        x,
        47,
        125,
        () -> {
          Store.config.hud = !Store.config.hud;
          Store.save();
          rebuild();
        });
    button(
        Store.config.hudLeft ? "位置：左侧" : "位置：右侧",
        x + 131,
        47,
        110,
        () -> {
          Store.config.hudLeft = !Store.config.hudLeft;
          Store.save();
          rebuild();
        });
    field(
        "侧栏宽度",
        Integer.toString(Store.config.hudWidth),
        82,
        3,
        s -> {
          try {
            Store.config.hudWidth = Rules.bounded(Integer.parseInt(s), 130, 300);
          } catch (NumberFormatException ignored) {
          }
        });
    field(
        "顶部距离",
        Integer.toString(Store.config.hudY),
        108,
        4,
        s -> {
          try {
            Store.config.hudY = Rules.bounded(Integer.parseInt(s), 8, 500);
          } catch (NumberFormatException ignored) {
          }
        });
    field(
        "消息条数",
        Integer.toString(Store.config.hudMessages),
        134,
        1,
        s -> {
          try {
            Store.config.hudMessages = Rules.bounded(Integer.parseInt(s), 1, 5);
          } catch (NumberFormatException ignored) {
          }
        });
    button(
        Store.config.hudShare ? "双窗口同步：开" : "双窗口同步：关",
        x,
        158,
        136,
        () -> {
          Store.config.hudShare = !Store.config.hudShare;
          Store.save();
          rebuild();
        });
    button(
        Store.config.hudCompact ? "显示：紧凑" : "显示：详细",
        x + 142,
        158,
        99,
        () -> {
          Store.config.hudCompact = !Store.config.hudCompact;
          Store.save();
          rebuild();
        });
    button(
        Store.config.hudMetrics ? "性能统计：开" : "性能统计：关",
        x,
        184,
        136,
        () -> {
          Store.config.hudMetrics = !Store.config.hudMetrics;
          Store.save();
          rebuild();
        });
    button(
        "透明度 " + Store.config.hudOpacity + "%",
        x + 142,
        184,
        99,
        () -> {
          Store.config.hudOpacity =
              Store.config.hudOpacity >= 95 ? 55 : Store.config.hudOpacity + 10;
          Store.save();
          rebuild();
        });
    button(
        "消息记录",
        x,
        210,
        112,
        () -> {
          page = 1;
          rebuild();
        });
    button(
        "消息通道 / 任务卡",
        x + 118,
        210,
        123,
        () -> {
          page = -1;
          rebuild();
        });
    button(
        "观看形象 / 本地交流",
        x,
        237,
        Math.min(241, w),
        () -> {
          page = -2;
          scroll = 0;
          rebuild();
        });
    labels.add(new Label(cut("主号也装本 Mod；只在小号开启 AI 接管。", w), x, 266));
    labels.add(
        new Label(
            cut("同机同服同步；" + Companion.STOP.getTranslatedKeyMessage().getString() + " 只停本窗口。", w),
            x,
            280));
    if (!StatusLink.error().isBlank()) labels.add(new Label(cut(StatusLink.error(), w), x, 294));
  }

  private void world() {
    if (minecraft.player == null || minecraft.level == null) {
      labels.add(new Label("请先进入世界，再观察和查询对象。", x, 55));
      return;
    }
    var candidate = Store.data.world.get(worldSelected);
    var selectedPoint =
        candidate != null
                && candidate.dimension.equals(minecraft.level.dimension().location().toString())
            ? candidate
            : null;
    if (selectedPoint != null) {
      labels.add(
          new Label(
              cut(selectedPoint.name + " · " + WorldSense.categoryName(selectedPoint.category), w),
              x,
              49));
      EditBox label =
          field("地点别名", selectedPoint.label == null ? "" : selectedPoint.label, 77, 80, s -> {});
      button(
          "保存地点名",
          x,
          102,
          98,
          () -> {
            try {
              Companion.notifyUser(
                  Actions.rememberPosition(
                      label.getValue(),
                      new net.minecraft.core.BlockPos(
                          selectedPoint.x, selectedPoint.y, selectedPoint.z)));
              rebuild();
            } catch (Exception e) {
              Companion.notifyUser(e.getMessage());
            }
          });
      button("打开并观察", x + 104, 102, 106, () -> run("打开 " + selectedPoint.key));
      button(
          "完整记录",
          x,
          128,
          98,
          () -> minecraft.setScreen(new NoticeScreen(ui, WorldSense.describe(selectedPoint))));
      button("检查现场", x + 104, 128, 106, () -> run("检查 " + selectedPoint.key));
      labels.add(
          new Label(
              cut(
                  "位置 "
                      + selectedPoint.x
                      + ", "
                      + selectedPoint.y
                      + ", "
                      + selectedPoint.z
                      + " · "
                      + selectedPoint.dimension,
                  w),
              x,
              169));
      labels.add(
          new Label(
              "最后观察 "
                  + Math.max(0, (System.currentTimeMillis() - selectedPoint.lastSeen) / 1000)
                  + " 秒前；"
                  + (selectedPoint.present ? "当时存在" : "已经消失"),
              x,
              185));
      labels.add(
          new Label(
              selectedPoint.inventory == null
                  ? "库存未知，需打开后读取。"
                  : selectedPoint.inventory.contentsConfirmed
                      ? "有历史库存，打开后才能确认现在的数量。"
                      : "有历史可见槽位，用途/可取库存尚未确认。",
              x,
              201));
      button(
          "返回对象列表",
          x,
          height - 31,
          130,
          () -> {
            worldSelected = "";
            rebuild();
          });
      return;
    }
    field("搜索对象", worldQuery, 50, 128, s -> worldQuery = s);
    button(
        "查询",
        x,
        75,
        64,
        () -> {
          page = 0;
          rebuild();
        });
    button(
        "环视 " + Store.config.perceptionRadius + " 格",
        x + 70,
        75,
        100,
        () -> run("环视 " + Store.config.perceptionRadius));
    button(
        "范围切换",
        x + 176,
        75,
        90,
        () -> {
          Store.config.perceptionRadius =
              switch (Store.config.perceptionRadius) {
                case 8 -> 12;
                case 12 -> 16;
                case 16 -> 24;
                default -> 8;
              };
          Store.save();
          rebuild();
        });
    var result = WorldSense.query(worldQuery, "", 0, 64).getAsJsonArray("objects");
    int available = Math.max(1, (height - 153) / 34);
    for (int j = 0; j < available && page * available + j < result.size(); j++) {
      var o = result.get(page * available + j).getAsJsonObject();
      String key = o.get("key").getAsString();
      var p = Store.data.world.get(key);
      int y = 111 + j * 34;
      button(
          cut(
              (p.label == null || p.label.isBlank() ? p.name : p.label)
                  + " · "
                  + WorldSense.categoryName(p.category),
              w - 8),
          x,
          y,
          Math.max(80, w - 8),
          () -> {
            worldSelected = key;
            rebuild();
          });
      labels.add(
          new Label(
              cut(
                  p.x
                      + ", "
                      + p.y
                      + ", "
                      + p.z
                      + " · "
                      + o.get("distance").getAsDouble()
                      + "格 · "
                      + (p.inventory == null
                          ? "库存未知"
                          : p.inventory.contentsConfirmed ? "历史库存" : "历史可见槽位"),
                  w - 8),
              x + 3,
              y + 22));
    }
    if (result.isEmpty()) labels.add(new Label("暂无匹配记录，先站定环视；未知不等于没有。", x, 117));
    pager(result.size(), available);
  }

  private void backup() {
    try {
      var p = Store.backup();
      Companion.notifyUser("备份已创建：" + p.getFileName());
    } catch (Exception e) {
      Companion.notifyUser("备份失败：" + e.getMessage());
    }
  }

  private void run(String text) {
    onClose();
    if (!Agent.enabled) Agent.start();
    Agent.submit(text);
  }

  private void pager(int total, int size) {
    button(
        "上一页",
        x,
        height - 31,
        75,
        () -> {
          page = Math.max(0, page - 1);
          rebuild();
        });
    button(
        "下一页",
        x + 81,
        height - 31,
        75,
        () -> {
          if ((page + 1) * size < total) page++;
          rebuild();
        });
    labels.add(new Label("第 " + (page + 1) + " 页 / " + total + " 条", x + 165, height - 25));
  }

  private String cut(String s, int pixels) {
    return s;
  }
}
