package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.Minecraft;

/** Redesigned G4Neon Settings Views: card-based, live permission overview, search, dirty-state. */
final class WorkbenchSettingsViews {
  private final WorkbenchScreen ui;

  WorkbenchSettingsViews(WorkbenchScreen ui) {
    this.ui = ui;
  }

  void render(int page) {
    String q = ui.settingsQuery().trim();
    if (!q.isEmpty()) {
      renderSearchResults(q);
      return;
    }
    switch (page) {
      case 0 -> connection();
      case 1 -> permissions();
      case 2 -> input();
      case 3 -> appearance();
      default -> maintenance();
    }
  }

  private void renderSearchResults(String query) {
    UiTheme t = ui.theme();
    var hits = SettingsCatalog.search(query);
    ui.card(
        "设置搜索结果",
        hits.size() + " 项",
        hits.isEmpty() ? t.warning() : t.accent(),
        () -> {
          if (hits.isEmpty()) {
            ui.mutedText("未找到与「" + query + "」匹配的设置项；可尝试：判断、公屏、导航、租约、主题、成员。");
          } else {
            ui.mutedText("点击下方条目直接跳转到对应设置卡片或编辑页：");
          }
          ui.button("清空搜索条件", ui::clearSettingsQuery);
        });
    for (var entry : hits) {
      ui.card(
          entry.title(),
          entry.category(),
          t.accent(),
          () -> {
            ui.mutedText(entry.summary());
            ui.primaryButton(
                entry.section() >= 0 ? "打开子页面 · " + entry.title() : "前往 · " + entry.category(),
                () -> {
                  ui.clearSettingsQueryOnly();
                  if (entry.section() >= 0) ui.section(entry.section(), true);
                  else ui.go(true, entry.page());
                });
          });
    }
  }

  private void connection() {
    UiTheme t = ui.theme();
    var hs = HarnessController.status();
    boolean leaseActive = hs.get("lease_active").getAsBoolean();
    long leaseSec = hs.get("lease_remaining_ms").getAsLong() / 1000;

    // 1. Planner Driver & Connection Status
    ui.card(
        "规划入口与连接",
        "HARNESS".equals(Store.config.driver)
            ? (leaseActive ? "Harness 在线 " + leaseSec + "s" : "Harness 待连接")
            : "API · " + Store.config.apiProtocol,
        "HARNESS".equals(Store.config.driver) ? (leaseActive ? t.success() : t.warning()) : t.accent(),
        () -> {
          ui.segmentRow(
              List.of(
                  new WorkbenchScreen.SegmentOption(
                      "API 模型",
                      "API".equals(Store.config.driver),
                      () -> {
                        Agent.stop("切换API规划入口");
                        Store.config.driver = "API";
                        Store.saveSettingsOnly();
                        ui.rebuild();
                      }),
                  new WorkbenchScreen.SegmentOption(
                      "本地 Harness (MCP)",
                      "HARNESS".equals(Store.config.driver),
                      () -> {
                        Agent.stop("切换Harness规划入口");
                        Store.config.driver = "HARNESS";
                        Store.config.mcp = true;
                        Store.saveSettingsOnly();
                        McpBridge.sync();
                        ui.rebuild();
                      })));
          ui.mutedText(
              "当前模型："
                  + Store.config.model
                  + " ("
                  + Store.config.apiProtocol
                  + ") · 游戏控制："
                  + (Agent.enabled ? (Agent.paused ? "已暂停" : "AI 接管中") : "人工控制"));
          ui.row(
              "编辑 API 参数与密钥",
              () -> ui.section(4, true),
              "Harness 接口与录制",
              () -> ui.section(14, true));
          ui.button(
              "复制无凭据接入说明",
              () -> {
                Minecraft.getInstance()
                    .keyboardHandler
                    .setClipboard(
                        "Minecraft后勤伙伴 "
                            + BuildInfo.ID
                            + "；使用harness-client.py读取本机连接配置。先status、capabilities、tools，游戏中选择HARNESS并明确AI接管后connect。使用原request_id查询回执；截图需独立授权。不要公开连接凭据。");
                ui.setStatus("已复制不含令牌的接入说明");
                ui.rebuild();
              });
        });

    // 2. Reasoning Effort, Execution Style & Context Budget
    ui.card(
        "推理强度与执行风格",
        PlanningPolicy.title(Store.config.planningEffort)
            + " · "
            + (ExecutionStyle.fluent(Store.config.executionStyle) ? "快速" : "严谨"),
        t.accentAlt(),
        () -> {
          ui.strategyControls(true);
          ui.toggleRow(
              "精简模型工具菜单（按任务动态筛选）",
              () -> Store.config.compactToolMenu,
              v -> Store.config.compactToolMenu = v,
              false,
              "小模型推荐",
              t.accent());
          ui.toggleRow(
              "精简现场观察（完整槽位/NBT按需读取）",
              () -> Store.config.compactObservation,
              v -> Store.config.compactObservation = v,
              false,
              "",
              0);
          ui.field(
              "model_context_chars",
              "历史请求字符预算（12000..200000，非模型 token 窗口）",
              Integer.toString(Store.config.contextChars),
              v -> {});
          ui.button(
              "应用请求预算",
              () -> {
                ui.saveDrafts();
                int chars;
                try {
                  chars =
                      Integer.parseInt(
                          ui.value("model_context_chars", Integer.toString(Store.config.contextChars))
                              .trim());
                } catch (NumberFormatException e) {
                  throw new IllegalArgumentException("历史请求预算必须是 12000..200000 的整数");
                }
                if (chars < 12000 || chars > 200000)
                  throw new IllegalArgumentException("历史请求预算需在 12000..200000 之间");
                Store.config.contextChars = chars;
                Store.saveSettingsOnly();
                ui.setStatus("已保存历史请求预算：" + chars + " 字符");
                ui.rebuild();
              });
        });

    // 3. Local / Cloud Decision Layer (OFF / CLOUD / LOCAL)
    boolean local = "LOCAL".equals(Store.config.decisionMode);
    boolean off = "OFF".equals(Store.config.decisionMode);
    String prefix = local ? "decision/local/" : "decision/cloud/";
    boolean dirty = ui.hasDraftPrefix(prefix);
    ui.card(
        "局部判断层（候选动作优选）",
        dirty ? "未保存修改" : Store.config.decisionMode,
        dirty ? t.warning() : (off ? t.textMuted() : t.success()),
        () -> {
          ui.segmentRow(
              List.of(
                  decisionModeOption("关闭 OFF", "OFF"),
                  decisionModeOption("云端 CLOUD", "CLOUD"),
                  decisionModeOption("本地 LOCAL", "LOCAL")));
          ui.toggleRow(
              "允许判断后自动提交候选任务（仍检查游戏权限）",
              () -> Store.config.decisionAutoSubmit,
              Store::setDecisionAutoSubmit,
              false,
              "",
              0);
          if (off) {
            ui.mutedText("判断层当前关闭；切换到 CLOUD 或 LOCAL 后可配置端点与密钥。本地与云端配置分开保留。");
          } else {
            String activeProto =
                local ? Store.config.decisionLocalProtocol : Store.config.decisionCloudProvider;
            ui.segmentRow(
                List.of(
                    decisionProtocolOption("GOOGLE", activeProto, local, prefix),
                    decisionProtocolOption("JEV", activeProto, local, prefix),
                    decisionProtocolOption("OPENAI", activeProto, local, prefix)));
            ui.field(
                prefix + "endpoint",
                "判断接口完整 URL",
                local ? Store.config.decisionLocalEndpoint : Store.config.decisionCloudEndpoint,
                v -> {});
            ui.field(
                prefix + "model",
                "判断模型 ID",
                local ? Store.config.decisionLocalModel : Store.config.decisionCloudModel,
                v -> {});
            var key =
                ui.field(
                    prefix + "key",
                    "判断 API Key（本地服务可留空）",
                    local ? Store.config.decisionLocalKey : Store.config.decisionCloudKey,
                    v -> {});
            key.setFormatter(
                (s, i) ->
                    net.minecraft.util.FormattedCharSequence.forward(
                        "•".repeat(s.length()), net.minecraft.network.chat.Style.EMPTY));
            ui.row(
                "保存判断配置",
                () -> {
                  ui.saveDrafts();
                  if (local) {
                    Store.config.decisionLocalEndpoint =
                        ui.value(prefix + "endpoint", Store.config.decisionLocalEndpoint).trim();
                    Store.config.decisionLocalModel =
                        ui.value(prefix + "model", Store.config.decisionLocalModel).trim();
                    Store.config.decisionLocalKey =
                        ui.value(prefix + "key", Store.config.decisionLocalKey).trim();
                  } else {
                    Store.config.decisionCloudEndpoint =
                        ui.value(prefix + "endpoint", Store.config.decisionCloudEndpoint).trim();
                    Store.config.decisionCloudModel =
                        ui.value(prefix + "model", Store.config.decisionCloudModel).trim();
                    Store.config.decisionCloudKey =
                        ui.value(prefix + "key", Store.config.decisionCloudKey).trim();
                  }
                  ui.clearDrafts(prefix);
                  DecisionService.invalidate();
                  Store.saveSettingsOnly();
                  ui.setStatus("判断配置已保存；可通过 decision_select 验证连通性");
                  ui.rebuild();
                },
                "取消草稿",
                () -> {
                  ui.clearDrafts(prefix);
                  ui.setStatus("已还原判断层未保存的草稿");
                  ui.rebuild();
                });
          }
          ui.row(
              "从磁盘重载判断配置",
              () -> {
                try {
                  Store.reloadDecisionConfig();
                  ui.clearDrafts("decision/");
                  ui.setStatus("判断配置已从 settings.json 重载");
                } catch (Exception e) {
                  ui.setStatus("判断配置读取失败，保留现有配置");
                }
                ui.rebuild();
              },
              "判断层状态与最近结果",
              () -> ui.openNotice(DecisionService.status().toString()));
        });

    // 4. Diagnostics & Stats
    ui.card(
        "诊断与统计",
        "",
        0,
        () ->
            ui.row(
                "查看能力目录",
                () -> ui.openNotice(ui.capabilities()),
                "模型用量统计",
                () -> ui.openNotice(UsageStats.human(Store.data.usageStats))));
  }

  private WorkbenchScreen.SegmentOption decisionModeOption(String label, String mode) {
    return new WorkbenchScreen.SegmentOption(
        label,
        mode.equals(Store.config.decisionMode),
        () -> {
          Store.config.decisionMode = mode;
          DecisionService.invalidate();
          Store.saveSettingsOnly();
          ui.rebuild();
        });
  }

  private WorkbenchScreen.SegmentOption decisionProtocolOption(
      String protocol, String active, boolean local, String prefix) {
    return new WorkbenchScreen.SegmentOption(
        protocol,
        protocol.equals(active),
        () -> {
          if (local) Store.config.decisionLocalProtocol = protocol;
          else {
            Store.config.decisionCloudProvider = protocol;
            if ("GOOGLE".equals(protocol)) {
              Store.config.decisionCloudEndpoint =
                  "https://generativelanguage.googleapis.com/v1beta/decisions";
              Store.config.decisionCloudModel = "gemini-decision-1";
            } else if ("JEV".equals(protocol)) {
              Store.config.decisionCloudEndpoint = "https://api.typesafe.ai/v1/systemone";
              Store.config.decisionCloudModel = "jev-latest";
            }
          }
          ui.clearDrafts(prefix);
          DecisionService.invalidate();
          Store.saveSettingsOnly();
          ui.rebuild();
        });
  }

  private void permissions() {
    UiTheme t = ui.theme();
    var perm = PermissionView.current();
    boolean full = perm.get("game_freedom").getAsBoolean();
    String effMode = perm.get("mode").getAsString();

    // Conditional pending approval alert card
    if (!Agent.pendingTool.isBlank()) {
      ui.alertCard(
          "待人工审批操作 · " + Agent.pendingTool,
          "阻塞中",
          t.warning(),
          () -> {
            ui.mutedText("请确认是否允许执行当前高风险工具请求：");
            ui.row(
                "批准一次",
                () -> {
                  ui.onClose();
                  Agent.approve(true);
                },
                "批准本次连续流程",
                () -> {
                  ui.onClose();
                  Agent.approveTask();
                });
            ui.dangerButton(
                "拒绝本次操作",
                () -> {
                  Agent.approve(false);
                  ui.rebuild();
                });
          });
    }

    // 1. Authorization Mode + Live Effective Matrix
    ui.card(
        "游戏授权模式与生效总览",
        "FULL".equals(effMode) ? "完全放权" : ("ASK".equals(effMode) ? "逐次批准" : "有限权限"),
        "FULL".equals(effMode) ? t.warning() : ("ASK".equals(effMode) ? t.accent() : t.success()),
        () -> {
          ui.segmentRow(
              List.of(
                  new WorkbenchScreen.SegmentOption(
                      "LIMITED 有限",
                      "LIMITED".equals(Store.config.permission) && !Store.config.fullGameControl,
                      () -> ui.mode("LIMITED")),
                  new WorkbenchScreen.SegmentOption(
                      "ASK 逐次批准",
                      "ASK".equals(Store.config.permission) && !Store.config.fullGameControl,
                      () -> ui.mode("ASK")),
                  new WorkbenchScreen.SegmentOption(
                      "FULL 完全放权",
                      "FULL".equals(Store.config.permission) || Store.config.fullGameControl,
                      () -> ui.mode("FULL"))));
          ui.toggleRow(
              "完全游戏控制（免游戏操作审批，允许通用键鼠）",
              () -> Store.config.fullGameControl,
              v -> Store.config.fullGameControl = v,
              true,
              "高权限",
              t.warning());
          ui.primaryButton(
              "应用玩家式放权预设（FULL + 自主准备 + 附近补给）",
              () -> {
                Agent.stop("玩家应用放权预设，旧作业结束");
                Store.config.permission = "FULL";
                Store.config.playerAutonomy = true;
                Store.config.nearbySupplyPickup = true;
                Store.config.executionStyle = "FLUENT";
                Store.config.interruptImmediately = false;
                Store.saveSettingsOnly();
                ui.setStatus("已应用玩家式放权；联网、截图、公屏与模型未改动");
                ui.rebuild();
              });
          ui.mutedText("实时生效权限矩阵（权限不授予服务器 OP 或未适配的能力）：");
          ui.badgeGrid(
              List.of(
                  permBadge("采集", perm.get("mine").getAsBoolean(), t),
                  permBadge("放置", perm.get("plant_and_place").getAsBoolean(), t),
                  permBadge("清怪", perm.get("hunt").getAsBoolean(), t),
                  permBadge("容器", perm.get("containers").getAsBoolean(), t),
                  permBadge("GUI", perm.get("gui").getAsBoolean(), t),
                  permBadge("丢弃", perm.get("drop").getAsBoolean(), t),
                  permBadge("实体", perm.get("entity_use").getAsBoolean(), t),
                  permBadge("键鼠", perm.get("generic_input").getAsBoolean(), t),
                  permBadge("导航破块", perm.get("navigation_break").getAsBoolean(), t),
                  permBadge("导航搭路", perm.get("navigation_place").getAsBoolean(), t),
                  permBadge("联网", perm.get("network_learning").getAsBoolean(), t),
                  permBadge("截图", perm.get("screenshots").getAsBoolean(), t),
                  permBadge("公屏", perm.get("public_replies").getAsBoolean(), t),
                  permBadge("聊天发送", perm.get("chat_submit").getAsBoolean(), t)));
        });

    // 2. Independent External Channels & Safety Guards
    ui.card(
        "外部通道与安全护栏（独立于 FULL）",
        "高敏隔离",
        t.danger(),
        () -> {
          ui.toggleRow(
              "回复公屏（关闭则仅显示在本地侧栏）",
              () -> Store.config.publicReply,
              v -> Store.config.publicReply = v,
              true,
              "对外可见",
              t.warning());
          ui.toggleRow(
              "Harness 截图授权",
              () -> Store.config.harnessImages,
              v -> Store.config.harnessImages = v,
              false,
              "",
              0);
          ui.toggleRow(
              "联网参考资料",
              () -> Store.config.web,
              v -> Store.config.web = v,
              false,
              "",
              0);
          ui.toggleRow(
              "允许 Agent 打开聊天框自由打字",
              () -> Store.config.allowChatTyping,
              v -> Store.config.allowChatTyping = v,
              true,
              "敏感",
              t.warning());
          ui.toggleRow(
              "允许 Agent 提交聊天框（发公屏或插件指令）",
              () -> Store.config.allowChatSubmit,
              v -> Store.config.allowChatSubmit = v,
              true,
              "高危",
              t.danger());
          ui.toggleRow(
              "fill / setblock 覆盖关键方块需 confirm",
              () -> Store.config.protectCriticalCommandBlocks,
              v -> Store.config.protectCriticalCommandBlocks = v,
              false,
              "保护",
              t.success());
          ui.toggleRow(
              "传送落点预检（未加载区块按未知拒绝）",
              () -> Store.config.safeTeleport,
              v -> Store.config.safeTeleport = v,
              false,
              "",
              0);
          ui.toggleRow(
              "允许 Harness 恢复人工暂停（急停仍需重授权）",
              () -> Store.config.harnessResumeHumanPause,
              v -> Store.config.harnessResumeHumanPause = v,
              false,
              "",
              0);
        });

    // 3. Limited-mode granular grants (auto-collapsed when FULL overrides them)
    ui.collapsibleCard(
        "perm/limited",
        "有限模式细分权限",
        full ? "已被 FULL 覆盖" : "当前生效",
        full ? t.textMuted() : t.accent(),
        !full,
        () -> {
          if (full) {
            ui.mutedText("提示：当前处于 FULL 或完全游戏控制模式，下列开关仅在切回 LIMITED / ASK 后生效。");
          }
          ui.toggle("容器与仓储", () -> Store.config.allowContainers, v -> Store.config.allowContainers = v);
          ui.toggle("GUI 界面操作", () -> Store.config.allowGui, v -> Store.config.allowGui = v);
          ui.toggle("丢弃物品", () -> Store.config.allowDrop, v -> Store.config.allowDrop = v);
          ui.toggle("实体交互", () -> Store.config.allowEntityUse, v -> Store.config.allowEntityUse = v);
          ui.toggle("采集挖掘", () -> Store.config.allowMine, v -> Store.config.allowMine = v);
          ui.toggle("种植与放置", () -> Store.config.allowPlace, v -> Store.config.allowPlace = v);
          ui.toggle("通用键鼠输入", () -> Store.config.allowInput, v -> Store.config.allowInput = v);
          ui.toggle("主动清怪", () -> Store.config.allowHunt, v -> Store.config.allowHunt = v);
        });

    // 4. Navigation Terrain & Autonomy
    ui.card(
        "导航地形与自主行为",
        NavigationPermissions.canBreak() ? "可开路" : "仅绕行",
        NavigationPermissions.canBreak() ? t.warning() : t.success(),
        () -> {
          ui.toggleRow(
              "导航仅绕行（禁止破坏和搭路）",
              () -> Store.config.navigationDetourOnly,
              v -> Store.config.navigationDetourOnly = v,
              false,
              Store.config.navigationDetourOnly ? "优先生效" : "",
              t.success());
          ui.toggle(
              "有限权限：允许导航破坏地形",
              () -> Store.config.navigationBreak,
              v -> Store.config.navigationBreak = v);
          ui.toggle(
              "有限权限：允许垫脚与搭路",
              () -> Store.config.navigationPlace,
              v -> Store.config.navigationPlace = v);
          ui.toggle(
              "玩家式：自主准备与有界恢复策略",
              () -> Store.config.playerAutonomy,
              v -> Store.config.playerAutonomy = v);
          ui.toggle(
              "玩家式：空闲时拾取身边补给（仅 FULL/API）",
              () -> Store.config.nearbySupplyPickup,
              v -> Store.config.nearbySupplyPickup = v);
          ui.toggle("本地自卫", () -> Store.config.selfDefense, v -> Store.config.selfDefense = v);
          ui.toggle("求助通知", () -> Store.config.helpNotifications, v -> Store.config.helpNotifications = v);
          ui.toggle(
              "显示 Agent 虚拟指针（不移动系统鼠标）",
              () -> Store.config.agentPointerVisible,
              v -> Store.config.agentPointerVisible = v);
        });

    // 5. World Command Policy (fixed: explicit draft + Save/Cancel instead of saving per keystroke!)
    var commands = CommandSettings.profile();
    boolean cmdDirty = ui.hasDraftPrefix("cmd/");
    ui.collapsibleCard(
        "perm/commands",
        "当前世界 / 服务器指令策略",
        cmdDirty ? "未保存修改" : "不授予 OP",
        cmdDirty ? t.warning() : t.textMuted(),
        false,
        () -> {
          ui.field("cmd/player", "普通玩家指令与别名（逗号分隔）", commands.playerCommands, v -> {});
          ui.field("cmd/radius", "坐标命令最大半径（1..1024）", Integer.toString(commands.radius), v -> {});
          ui.field(
              "cmd/volume", "区域命令最大方块数（1..32768）", Integer.toString(commands.maxBlocks), v -> {});
          ui.row(
              "保存指令策略",
              () -> {
                ui.saveDrafts();
                int r, vol;
                try {
                  r =
                      Integer.parseInt(
                          ui.value("cmd/radius", Integer.toString(commands.radius)).trim());
                  vol =
                      Integer.parseInt(
                          ui.value("cmd/volume", Integer.toString(commands.maxBlocks)).trim());
                } catch (NumberFormatException e) {
                  throw new IllegalArgumentException("坐标半径和区域方块数必须是整数");
                }
                commands.playerCommands = ui.value("cmd/player", commands.playerCommands).trim();
                commands.radius = Rules.bounded(r, 1, 1024);
                commands.maxBlocks = Rules.bounded(vol, 1, 32768);
                Store.save();
                ui.clearDrafts("cmd/");
                ui.setStatus("当前世界指令策略已保存");
                ui.rebuild();
              },
              "取消草稿",
              () -> {
                ui.clearDrafts("cmd/");
                ui.rebuild();
              });
          ui.toggle(
              "允许自定义单行指令（仍须 Agent 授权）",
              () -> commands.customCommands,
              v -> commands.customCommands = v);
          ui.toggle(
              "允许 clone / summon 测试指令",
              () -> commands.advancedCommands,
              v -> commands.advancedCommands = v);
          ui.toggle(
              "允许广泛对象选择器（@a / @e 等）",
              () -> commands.broadSelectors,
              v -> commands.broadSelectors = v);
          ui.button(
              "查看已检测模组与接口能力",
              () -> ui.openNotice(Store.GSON.toJson(IntegrationStatus.view())));
        });

    // 6. Members & Advanced
    ui.card(
        "成员授权与进阶策略",
        Store.data.members.size() + " 位成员",
        t.accent(),
        () ->
            ui.row(
                "主人与授权成员管理",
                () -> ui.section(17, true),
                "进阶战斗与死亡处理",
                () -> ui.section(5, true)));
  }

  private WorkbenchScreen.BadgeItem permBadge(String name, boolean on, UiTheme t) {
    return new WorkbenchScreen.BadgeItem(
        name + (on ? " ✓" : " ✗"), on ? t.success() : t.textMuted());
  }

  private void input() {
    UiTheme t = ui.theme();
    ui.card(
        "输入隔离与感知开关",
        WatchMode.active() ? "脱手观看中" : "正常控制",
        WatchMode.active() ? t.accent() : t.textMuted(),
        () -> {
          ui.toggle(
              "导航自动打开木门 / 栅栏门",
              () -> Store.config.autoOpenDoors,
              v -> Store.config.autoOpenDoors = v);
          ui.toggle(
              "后台游戏输入（失焦时继续）",
              () -> Store.config.backgroundInput,
              v -> Store.config.backgroundInput = v);
          ui.toggle(
              "目标与碰撞诊断渲染",
              () -> Store.config.debugScene,
              v -> Store.config.debugScene = v);
          ui.mutedText("仅作用于当前游戏窗口，停止时只释放本模组虚拟键，不打断玩家人工长按。");
          ui.row(
              "按键白名单与导航后端",
              () -> ui.section(12, true),
              "视觉辅助与人工示范",
              () -> ui.section(11, true));
          ui.row(
              WatchMode.active() ? "退出脱手观看" : "开启脱手观看",
              () -> {
                WatchMode.toggle();
                ui.rebuild();
              },
              "查看战斗认知边界",
              () -> {
                var p = Minecraft.getInstance().player;
                String weapon =
                    p == null
                        ? "未进入世界"
                        : MeleeOps.weapon(p.getMainHandItem())
                            ? "当前主手可被普通近战识别"
                            : "当前主手不符合普通近战武器规则";
                ui.openNotice(
                    weapon
                        + "\n索敌使用已加载客户端实体与射线；通用清怪仍有目标白名单。\n"
                        + "装备选择目前主要看普通攻击属性，不代表 Mine and Slash 实际 DPS 排名。\n"
                        + (p == null ? "" : DynamicScene.inspect()));
              });
        });

    ui.card(
        "模组快捷键总览",
        "7 项",
        t.accentAlt(),
        () -> {
          ui.keybindRow("打开工作台面板", Companion.PANEL.getTranslatedKeyMessage().getString());
          ui.keybindRow("一键开启 AI 接管与观看", Companion.QUICK_START.getTranslatedKeyMessage().getString());
          ui.keybindRow("脱手观看 / 交还输入", Companion.WATCH.getTranslatedKeyMessage().getString());
          ui.keybindRow("本地伙伴对话浮层", Companion.DIALOGUE.getTranslatedKeyMessage().getString());
          ui.keybindRow("切换人工协同模式", Companion.COOP.getTranslatedKeyMessage().getString());
          ui.keybindRow("显示 / 隐藏伙伴侧栏", Companion.HUD.getTranslatedKeyMessage().getString());
          ui.keybindRow("紧急停止 AI（含界面内）", Companion.STOP.getTranslatedKeyMessage().getString());
        });
  }

  private void appearance() {
    UiTheme t = ui.theme();
    ui.card(
        "G4Neon 视觉主题工坊",
        Store.config.theme,
        t.accent(),
        () -> {
          ui.mutedText("即时切换工作台、侧栏、对话浮层与所有辅助界面的配色方案；不影响任务与权限。");
          ui.swatchRow(UiTheme.ALL);
        });

    ui.card(
        "前台过程与流式展示",
        Store.config.processDetailed ? "详细过程" : "简洁侧栏",
        t.accentAlt(),
        () -> {
          ui.toggleRow(
              "原生 API 流式接收（SSE）",
              () -> Store.config.apiStreaming,
              v -> Store.config.apiStreaming = v,
              false,
              "实时工作说明",
              t.accent());
          ui.toggleRow(
              "前台侧栏：详细过程模式（关=简洁）",
              () -> Store.config.processDetailed,
              v -> Store.config.processDetailed = v,
              false,
              "",
              0);
          ui.toggle(
              "显示伙伴侧栏 (HUD)",
              () -> Store.config.hud,
              v -> Store.config.hud = v);
          ui.toggle(
              "显示本地对话浮层",
              () -> Store.config.localDialogue,
              v -> Store.config.localDialogue = v);
          ui.mutedText("Home 进入本地输入后可点击侧栏切换简洁/详细，滚轮翻阅历史；工作说明不作为执行证明。");
        });

    ui.card(
        "布局、角色形象与人设",
        Store.config.avatarStyle.equals("OFF") ? "立绘关闭" : Store.config.avatar,
        t.accent(),
        () -> {
          ui.row(
              "拖动编辑观看布局 / 缩放",
              () -> Minecraft.getInstance().setScreen(new HudLayoutEditor(ui)),
              "恢复默认侧栏布局",
              () -> {
                Store.config.hudLeft = false;
                Store.config.hudY = 42;
                Store.config.hudWidth = 190;
                Store.config.hudOpacity = 85;
                Store.saveSettingsOnly();
                ui.setStatus("已恢复默认侧栏位置与宽度");
                ui.rebuild();
              });
          ui.row(
              "侧栏、角色形象与消息通道",
              () -> ui.section(7, true),
              "伙伴人设与跨世界偏好",
              () -> ui.section(16, true));
        });
  }

  private void maintenance() {
    UiTheme t = ui.theme();
    ui.card(
        "Harness 控制租约时长",
        HarnessLeasePolicy.label(Store.config.harnessLeaseSeconds),
        t.accent(),
        () -> {
          ui.row("5 分钟", () -> leaseSetting(300), "1 小时", () -> leaseSetting(3600));
          ui.row("6 小时", () -> leaseSetting(21600), "12 小时", () -> leaseSetting(43200));
          ui.row("24 小时", () -> leaseSetting(86400), "直到手动停止", () -> leaseSetting(0));
          ui.field(
              "lease/minutes",
              "自定义时长（分钟，1..43200）",
              Integer.toString(Math.max(1, Store.config.harnessLeaseSeconds / 60)),
              v -> {});
          ui.button(
              "应用自定义租约时长",
              () -> leaseSetting(HarnessLeasePolicy.customMinutes(ui.value("lease/minutes", "60"))));
          ui.mutedText("有限时长为空闲超时（心跳/请求自动续期）；急停键、主动释放与退出世界仍立即撤销控制。");
        });

    ui.card(
        "共享技能库与数据维护",
        Store.config.libraryFolder.isBlank() ? "当前实例目录" : "自定义目录",
        t.accentAlt(),
        () -> {
          ui.field(
              "library-root",
              "共享库目录（留空使用本实例；修改后保存）",
              Store.config.libraryFolder,
              v -> {});
          ui.button(
              "保存目录并刷新技能库",
              () -> {
                ui.saveDrafts();
                Store.config.libraryFolder = ui.value("library-root", "").trim();
                Store.saveSettingsOnly();
                SkillLibrary.refresh();
                ui.setStatus(
                    SkillLibrary.errors.isEmpty()
                        ? "共享技能库目录已刷新"
                        : String.join("；", SkillLibrary.errors));
                ui.rebuild();
              });
          ui.row(
              "备份 / 恢复 / 打开数据目录",
              () -> ui.section(6, true),
              "查看本次运行统计",
              () -> ui.openNotice(Store.GSON.toJson(RuntimeStats.snapshot())));
          ui.mutedText("技能定义跨世界共享；世界库存、坐标与当前任务按服务器独立保存。备份不含 API Key 或令牌。");
        });
  }

  private void leaseSetting(int seconds) {
    HarnessController.configureLease(seconds);
    ui.setStatus("Harness 租约已应用：" + HarnessLeasePolicy.label(seconds));
    ui.rebuild();
  }
}
