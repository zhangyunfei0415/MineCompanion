package dev.mibao.companion;

import java.util.*;

/**
 * Maid-style dedicated「跟随与陪同」workbench page allowing direct switching between
 * Standby, Base Steward, Escort & Guard, and Outbound Expedition/Scavenging modes.
 */
final class WorkbenchDutyView {
  private final WorkbenchScreen ui;

  WorkbenchDutyView(WorkbenchScreen ui) {
    this.ui = ui;
  }

  void render() {
    UiTheme t = ui.theme();
    CompanionDuty duty = DutyScheduler.duty();
    CompanionDuty.Mode activeMode = duty.modeEnum();
    boolean armed = DutyScheduler.isArmed();
    var status = DutyScheduler.status();

    String statusBadge =
        activeMode == CompanionDuty.Mode.STANDBY
            ? "待命"
            : armed ? status.get("runtime_state").getAsString() : "已预设(未启动)";
    int statusColor =
        activeMode == CompanionDuty.Mode.STANDBY
            ? t.textMuted()
            : armed ? t.success() : t.warning();

    ui.card(
        "值守总控 · " + activeMode.label,
        statusBadge,
        statusColor,
        () -> {
          ui.text("当前行动 · " + activeMode.summary);
          ui.mutedText(
              "状态明细 · "
                  + status.get("detail").getAsString()
                  + (status.has("suspend_reason")
                      ? "\n挂起原因 · " + status.get("suspend_reason").getAsString()
                      : ""));
          if (armed) {
            ui.row(
                "暂停当前值守",
                () -> {
                  DutyScheduler.arm(false, "工作台暂停值守");
                  ui.rebuild();
                },
                "立即切回纯待命",
                () -> {
                  DutyScheduler.setMode(CompanionDuty.Mode.STANDBY, false, "手动切回纯待命");
                  ui.rebuild();
                });
          } else if (activeMode != CompanionDuty.Mode.STANDBY) {
            ui.row(
                "立即启动「" + activeMode.label + "」",
                () -> {
                  DutyScheduler.setMode(activeMode, true, "工作台启动值守");
                  ui.rebuild();
                },
                "重设为纯待命",
                () -> {
                  DutyScheduler.setMode(CompanionDuty.Mode.STANDBY, false, "手动切回纯待命");
                  ui.rebuild();
                });
          }
          ui.button(
              "以当前位置设为基地中心锚点",
              () -> {
                DutyScheduler.setHomeHere(true);
                ui.setStatus("已登记基地中心锚点并存入当前世界记忆");
                ui.rebuild();
              });
          ui.mutedText("AI 自主调档权限（未锁定时，AI 可按主人动向与满包返程自动切模式）：");
          ui.segmentRow(
              List.of(
                  autonomyOption(duty, DutyAutonomyPolicy.Level.AUTO_FULL),
                  autonomyOption(duty, DutyAutonomyPolicy.Level.AUTO_SAFE),
                  autonomyOption(duty, DutyAutonomyPolicy.Level.MANUAL_LOCK)));
        });

    List<String> gaps =
        duty.missingPermissions(
            Store.config.fullGameControl || "FULL".equals(Store.config.permission),
            Store.config.permission,
            Store.config.allowMine,
            Store.config.allowPlace,
            Store.config.allowHunt,
            Store.config.allowContainers,
            Store.config.navigationDetourOnly,
            Store.config.navigationBreak);
    if (!gaps.isEmpty()) {
      ui.alertCard(
          "权限与先决提示",
          gaps.size() + " 项",
          t.warning(),
          () -> {
            for (String g : gaps) ui.mutedText("• " + g);
            ui.button("前往 设置 / 权限与安全 调整开关", ui::goToPermissions);
          });
    }

    ui.card(
        "女仆式主行动预设（一键切换）",
        "4 种模式",
        t.accent(),
        () -> {
          ui.mutedText("点击即切换当前主打行为；日常巡逻/守护/搜刮均由本地状态机驱动，不消耗 API Token。");
          ui.segmentRow(
              List.of(
                  new WorkbenchScreen.SegmentOption(
                      "纯待命",
                      activeMode == CompanionDuty.Mode.STANDBY,
                      () -> {
                        DutyScheduler.setMode(CompanionDuty.Mode.STANDBY, false, "切为纯待命");
                        ui.rebuild();
                      }),
                  new WorkbenchScreen.SegmentOption(
                      "驻守基地",
                      activeMode == CompanionDuty.Mode.BASE_STEWARD,
                      () -> {
                        DutyScheduler.setMode(
                            CompanionDuty.Mode.BASE_STEWARD, true, "切为驻守基地自主值守");
                        ui.rebuild();
                      })));
          ui.segmentRow(
              List.of(
                  new WorkbenchScreen.SegmentOption(
                      "跟随与守护",
                      activeMode == CompanionDuty.Mode.ESCORT_GUARD,
                      () -> {
                        DutyScheduler.setMode(
                            CompanionDuty.Mode.ESCORT_GUARD, true, "切为跟随陪同与守护");
                        ui.rebuild();
                      }),
                  new WorkbenchScreen.SegmentOption(
                      "外出探索采集",
                      activeMode == CompanionDuty.Mode.EXPEDITION_GATHER,
                      () -> {
                        DutyScheduler.setMode(
                            CompanionDuty.Mode.EXPEDITION_GATHER, true, "切为外出探索与搜刮采集");
                        ui.rebuild();
                      })));
        });

    switch (activeMode) {
      case BASE_STEWARD -> renderBaseSteward(duty, t);
      case ESCORT_GUARD -> renderEscortGuard(duty, t);
      case EXPEDITION_GATHER -> renderExpedition(duty, t);
      case STANDBY -> renderStandbyTips();
    }

    renderMovementAndParkour(t);

    renderAutoActionCard(t);

    ui.card(
        "三维探索领路（带玩家回家）",
        "安全回程",
        t.accent(),
        () -> {
          ui.mutedText("沿已走通的 3D 安全回程图领路回到基地/入口/安全点，会在拐角与过远处主动停下等待玩家跟上。");
          ui.row(
              "带玩家回最近安全点/基地",
              () -> {
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (!Agent.enabled) Agent.start();
                if (!Agent.enabled) {
                  ui.setStatus("当前窗口未持有AI控制权，请在AI窗口启动");
                  return;
                }
                DutyScheduler.yieldCurrentJob("手动开启带玩家回家");
                var args = new com.google.gson.JsonObject();
                args.addProperty("target", "nearest_safe");
                args.addProperty("guide_player", true);
                args.addProperty("max_seconds", 240);
                try {
                  ExplorationJob.start(
                      "explore_return",
                      args,
                      r -> Companion.notifyUser(Rules.clean(r, 180)));
                  mc.setScreen(null);
                } catch (IllegalArgumentException ex) {
                  ui.setStatus(ex.getMessage());
                }
              },
              "带玩家回维度入口",
              () -> {
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (!Agent.enabled) Agent.start();
                if (!Agent.enabled) {
                  ui.setStatus("当前窗口未持有AI控制权，请在AI窗口启动");
                  return;
                }
                DutyScheduler.yieldCurrentJob("手动开启带玩家回入口");
                var args = new com.google.gson.JsonObject();
                args.addProperty("target", "nearest_entry");
                args.addProperty("guide_player", true);
                args.addProperty("max_seconds", 240);
                try {
                  ExplorationJob.start(
                      "explore_return",
                      args,
                      r -> Companion.notifyUser(Rules.clean(r, 180)));
                  mc.setScreen(null);
                } catch (IllegalArgumentException ex) {
                  ui.setStatus(ex.getMessage());
                }
              });
        });

    ui.card(
        "安全护栏与生活自理",
        "防呆防丢",
        t.accentAlt(),
        () -> {
          ui.worldToggleRow(
              "饥饿时自动进食（背包有可食用食物时）",
              () -> duty.autoEat,
              v -> duty.autoEat = v,
              false,
              duty.autoEat ? "推荐开启" : "",
              t.success());
          ui.worldToggleRow(
              "待命空闲按阈值自动进食（仅用随身背包普通食物，不触碰精妙背包）",
              () -> duty.standbyAutoEat,
              v -> duty.standbyAutoEat = v,
              duty.standbyAutoEat,
              duty.standbyAutoEat ? "待命自理" : "",
              duty.standbyAutoEat ? t.accentAlt() : 0);
          ui.mutedText(
              "自动进食触发与目标（当前："
                  + duty.autoEatThreshold
                  + " → "
                  + duty.autoEatTarget
                  + "）：");
          ui.segmentRow(
              List.of(
                  eatThresholdOption(duty, 8),
                  eatThresholdOption(duty, 10),
                  eatThresholdOption(duty, 12),
                  eatThresholdOption(duty, 14),
                  eatTargetOption(duty, 18),
                  eatTargetOption(duty, 20)));
          ui.worldToggleRow(
              "遇疑难异常允许低频求助大模型（默认关 · 防烧 Token）",
              () -> duty.allowAiEscalation,
              v -> duty.allowAiEscalation = v,
              duty.allowAiEscalation,
              duty.allowAiEscalation ? "限频求助" : "0 Token",
              duty.allowAiEscalation ? t.warning() : t.success());
          ui.mutedText(
              "抢占红线：工作台/人工鼠标介入 > 低血/溺水自救 > 贴脸自卫 > 显式派单 > 值守。\n"
                  + "断线重连、切维度或角色死亡后将自动挂起为安全待命，保留预设但不盲目重放。");
        });
  }

  private void renderStandbyTips() {
    ui.card(
        "待命模式说明",
        "低扰动",
        ui.theme().textMuted(),
        () -> {
          ui.text("当前不主动外出或巡逻，安静站在原地等待聊天口令、工作台派单或外部控制。");
          ui.mutedText("支持本地口令：驻守基地 / 跟随守护 / 外出探索采集 / 纯待命 / 暂停值守 / 恢复值守");
        });
  }

  private void renderBaseSteward(CompanionDuty duty, UiTheme t) {
    ui.card(
        "驻守基地 · 电子围栏与自主工作",
        duty.baseRadius + " 格围栏",
        t.accent(),
        () -> {
          ui.text(
              duty.hasHome
                  ? "基地锚点 · "
                      + (duty.homePlaceName.isBlank() ? "已登记" : duty.homePlaceName)
                      + " ("
                      + duty.homeX
                      + ", "
                      + duty.homeY
                      + ", "
                      + duty.homeZ
                      + ") · 维度 "
                      + duty.homeDimension
                  : "基地锚点 · 尚未设定（请站到基地核心后点击上方“设为基地中心锚点”）");
          if (Store.data.places != null && !Store.data.places.isEmpty()) {
            ui.mutedText("快捷绑定已有地点为基地中心（展示前 4 处）：");
            int shown = 0;
            for (var place : Store.data.places.values()) {
              if (++shown > 4) break;
              ui.button(
                  "绑定基地中心 · " + place.name + " (" + place.x + "," + place.y + "," + place.z + ")",
                  () -> {
                    DutyScheduler.bindHomePlace(place);
                    ui.setStatus("已绑定基地锚点：" + place.name);
                    ui.rebuild();
                  });
            }
          }
          ui.mutedText("基地活动半径（绝不追怪或捡物跑出此围栏）：");
          ui.segmentRow(
              List.of(
                  radiusOption(duty, 16),
                  radiusOption(duty, 32),
                  radiusOption(duty, 48),
                  radiusOption(duty, 64)));
          ui.worldToggleRow(
              "捡拾基地围栏内的散落掉落物",
              () -> duty.basePickup,
              v -> duty.basePickup = v,
              false,
              "",
              0);
          ui.worldToggleRow(
              "自动收割成熟原版农田作物并补种",
              () -> duty.baseFarm,
              v -> duty.baseFarm = v,
              false,
              "",
              0);
          ui.worldToggleRow(
              "发现基地昏暗角落自动补插火把照明",
              () -> duty.baseLighting,
              v -> duty.baseLighting = v,
              false,
              "",
              0);
          ui.worldToggleRow(
              "空闲时自动走回基地中心站位休整",
              () -> duty.baseReturnIdle,
              v -> duty.baseReturnIdle = v,
              false,
              "",
              0);
        });
  }

  private WorkbenchScreen.SegmentOption radiusOption(CompanionDuty duty, int r) {
    return new WorkbenchScreen.SegmentOption(
        r + " 格",
        duty.baseRadius == r,
        () -> {
          duty.baseRadius = r;
          Store.save();
          ui.rebuild();
        });
  }

  private WorkbenchScreen.SegmentOption eatThresholdOption(CompanionDuty duty, int threshold) {
    return new WorkbenchScreen.SegmentOption(
        "≤" + threshold + " 触发",
        duty.autoEatThreshold == threshold,
        () -> {
          duty.autoEatThreshold = threshold;
          duty.normalize();
          Store.save();
          ui.rebuild();
        });
  }

  private WorkbenchScreen.SegmentOption eatTargetOption(CompanionDuty duty, int target) {
    return new WorkbenchScreen.SegmentOption(
        "吃到 " + target,
        duty.autoEatTarget == target,
        () -> {
          duty.autoEatTarget = target;
          duty.normalize();
          Store.save();
          ui.rebuild();
        });
  }

  private void renderEscortGuard(CompanionDuty duty, UiTheme t) {
    String targetName = FollowControl.currentTargetName();
    ui.card(
        "跟随陪同 · 守护与护卫策略",
        targetName.isBlank() ? "未选主人" : targetName,
        targetName.isBlank() ? t.warning() : t.success(),
        () -> {
          ui.text("跟随主人 · " + (targetName.isBlank() ? "尚未指定（请点下方选择在线玩家）" : targetName));
          ui.row(
              "选择跟随玩家",
              ui::openFollowTargets,
              FollowControl.active() ? "停止底层跟随" : "立即跟上主人",
              () -> {
                if (FollowControl.active()) FollowControl.stop("面板手动停止跟随");
                else FollowControl.ensureFollowingSaved();
                ui.rebuild();
              });
          ui.mutedText("跟随距离档位：");
          ui.segmentRow(
              List.of(
                  rangeOption(duty, CompanionDuty.FollowRange.CLOSE),
                  rangeOption(duty, CompanionDuty.FollowRange.NORMAL),
                  rangeOption(duty, CompanionDuty.FollowRange.WIDE)));
          ui.mutedText("传送门随行预设：");
          ui.segmentRow(
              List.of(
                  portalOption("仅同维跟随", "follow", false),
                  portalOption("跟进下界门", "nether", false),
                  portalOption("允许末地门", "end", true)));
          ui.worldToggleRow(
              "自动守护玩家（反击靠近主人或攻击主人的敌对怪）",
              () -> duty.guardOwner,
              v -> duty.guardOwner = v,
              false,
              duty.guardOwner ? "护主" : "",
              t.accent());
          ui.worldToggleRow(
              "避开破坏地形生物近战（苦力怕/恶魂；防爆服可关闭以允许近战）",
              () -> duty.avoidTerrainDestroyingMobs,
              v -> duty.avoidTerrainDestroyingMobs = v,
              !duty.avoidTerrainDestroyingMobs,
              duty.avoidTerrainDestroyingMobs ? "防炸保护" : "防爆已关",
              duty.avoidTerrainDestroyingMobs ? t.success() : t.warning());
          ui.worldToggleRow(
              "安全距离弓箭牵制苦力怕（需开防炸保护且背着弓箭）",
              () -> duty.guardRangedSafe,
              v -> duty.guardRangedSafe = v,
              false,
              "",
              0);
          ui.worldToggleRow(
              "随行顺手拾取主人身旁掉落物",
              () -> duty.escortPickup,
              v -> duty.escortPickup = v,
              false,
              "",
              0);
          ui.worldToggleRow(
              "暗处为主人插火把开路（需背包有火把与放置权限）",
              () -> duty.escortTorch,
              v -> duty.escortTorch = v,
              false,
              "",
              0);
          ui.mutedText(
              "护栏：硬屏蔽其他玩家(除非开PvP)、已驯服宠物、村民与铁傀儡；最大追击绳长 "
                  + duty.guardLeash
                  + " 格，超距强制归队。");
        });
  }

  private WorkbenchScreen.SegmentOption rangeOption(
      CompanionDuty duty, CompanionDuty.FollowRange range) {
    return new WorkbenchScreen.SegmentOption(
        range.label,
        duty.followRangeEnum() == range,
        () -> {
          duty.followRange = range.name();
          Store.save();
          ui.rebuild();
        });
  }

  private WorkbenchScreen.SegmentOption portalOption(String label, String profile, boolean end) {
    boolean selected =
        profile.equals(Store.data.followProfile) && (!end || Store.data.followEndPortal);
    return new WorkbenchScreen.SegmentOption(
        label,
        selected,
        () -> {
          Store.data.followProfile = profile;
          Store.data.followEndPortal = end;
          Store.save();
          ui.rebuild();
        });
  }

  private void renderExpedition(CompanionDuty duty, UiTheme t) {
    ui.card(
        "外出探索 · 导航地形 · 战利品搜刮",
        duty.expeditionFocusEnum().label,
        t.accent(),
        () -> {
          ui.mutedText("外出目标倾向（默认根据附近方块可破坏性自动适配自然挖矿或后室探图搜刮）：");
          ui.segmentRow(
              List.of(
                  focusOption(duty, CompanionDuty.ExpeditionFocus.AUTO),
                  focusOption(duty, CompanionDuty.ExpeditionFocus.SCAVENGE_MAP),
                  focusOption(duty, CompanionDuty.ExpeditionFocus.ORE_MINE),
                  focusOption(duty, CompanionDuty.ExpeditionFocus.WOOD_GATHER)));
          ui.toggleRow(
              "导航仅绕行（不破坏墙壁、不搭路；迷宫/后室探图优先）",
              () -> Store.config.navigationDetourOnly,
              v -> {
                Store.config.navigationDetourOnly = v;
                if (v) Store.config.navigationBreak = false;
              },
              false,
              Store.config.navigationDetourOnly ? "仅绕行" : "",
              t.accent());
          ui.toggle(
              "允许导航与外出破坏开路（非仅绕行且需开启采集权限）",
              () -> Store.config.navigationBreak,
              v -> {
                Store.config.navigationBreak = v;
                if (v) Store.config.navigationDetourOnly = false;
              });
          ui.toggle(
              "允许导航垫脚搭路（需非仅绕行且开启放置权限）",
              () -> Store.config.navigationPlace,
              v -> Store.config.navigationPlace = v);
          ui.worldToggleRow(
              "允许自主搜刮野生容器（关=仅扫描登记到地图，开=安全收取非基地箱子）",
              () -> duty.scavengeWildContainers,
              v -> duty.scavengeWildContainers = v,
              duty.scavengeWildContainers,
              duty.scavengeWildContainers ? "搜刮开启" : "仅扫描",
              duty.scavengeWildContainers ? t.warning() : t.success());
          ui.worldToggleRow(
              "优先识别 Lootr 每人独立战利品箱（单独归类标记，永不误当基地箱）",
              () -> duty.preferLootrContainers,
              v -> duty.preferLootrContainers = v,
              false,
              duty.preferLootrContainers ? "Lootr 识别" : "",
              t.accentAlt());
          ui.mutedText("单趟时间预算与满包安全回城阈值：");
          ui.segmentRow(
              List.of(
                  minutesOption(duty, 5),
                  minutesOption(duty, 10),
                  minutesOption(duty, 20)));
          ui.segmentRow(
              List.of(
                  slotsOption(duty, 2),
                  slotsOption(duty, 3),
                  slotsOption(duty, 5)));
        });
  }

  private WorkbenchScreen.SegmentOption focusOption(
      CompanionDuty duty, CompanionDuty.ExpeditionFocus focus) {
    return new WorkbenchScreen.SegmentOption(
        focus.label,
        duty.expeditionFocusEnum() == focus,
        () -> {
          duty.expeditionFocus = focus.name();
          Store.save();
          ui.rebuild();
        });
  }

  private WorkbenchScreen.SegmentOption minutesOption(CompanionDuty duty, int minutes) {
    return new WorkbenchScreen.SegmentOption(
        "限时 " + minutes + " 分钟",
        duty.expeditionMinutes == minutes,
        () -> {
          duty.expeditionMinutes = minutes;
          Store.save();
          ui.rebuild();
        });
  }

  private WorkbenchScreen.SegmentOption slotsOption(CompanionDuty duty, int slots) {
    return new WorkbenchScreen.SegmentOption(
        "留空 ≤ " + slots + " 槽回城",
        duty.minFreeSlots == slots,
        () -> {
          duty.minFreeSlots = slots;
          Store.save();
          ui.rebuild();
        });
  }

  private void renderMovementAndParkour(UiTheme t) {
    String pk = MovementPolicy.normalizeParkourMode(Store.config.parkourMode);
    ui.card(
        "移动、涉水与跑酷能力",
        "跑酷 · " + ("OFF".equals(pk) ? "关" : "FULL".equals(pk) ? "完整" : "安全短跳"),
        t.accent(),
        () -> {
          ui.toggleRow(
              "跟随与长距移动自动疾跑（饱食 > 6 且非拐角时）",
              () -> Store.config.followSprint,
              v -> Store.config.followSprint = v,
              false,
              Store.config.followSprint ? "自动跑步" : "",
              t.success());
          ui.toggleRow(
              "允许涉水通过 1 格浅水坑（泳池层 / 积水走廊）",
              () -> Store.config.wadeWater,
              v -> Store.config.wadeWater = v,
              false,
              Store.config.wadeWater ? "涉水开启" : "",
              t.accent());
          ui.toggleRow(
              "允许攀爬梯子、藤蔓与脚手架",
              () -> Store.config.allowClimb,
              v -> Store.config.allowClimb = v,
              false,
              Store.config.allowClimb ? "爬梯开启" : "",
              t.accent());
          ui.mutedText("悬空缺口跑酷策略（有平路始终优先走路，仅在必要缺口启用跳跃）：");
          ui.segmentRow(
              List.of(
                  new WorkbenchScreen.SegmentOption(
                      "关闭跑酷",
                      "OFF".equals(pk),
                      () -> {
                        Store.config.parkourMode = "OFF";
                        Store.saveSettingsOnly();
                        ui.rebuild();
                      }),
                  new WorkbenchScreen.SegmentOption(
                      "安全短跳 (1~2格)",
                      "SAFE".equals(pk),
                      () -> {
                        Store.config.parkourMode = "SAFE";
                        Store.saveSettingsOnly();
                        ui.rebuild();
                      }),
                  new WorkbenchScreen.SegmentOption(
                      "完整助跑跳 (含3格)",
                      "FULL".equals(pk),
                      () -> {
                        Store.config.parkourMode = "FULL";
                        Store.saveSettingsOnly();
                        ui.rebuild();
                      })));
        });
  }

  private WorkbenchScreen.SegmentOption autonomyOption(
      CompanionDuty duty, DutyAutonomyPolicy.Level level) {
    return new WorkbenchScreen.SegmentOption(
        level.label,
        duty.autonomyLevelEnum() == level,
        () -> {
          duty.autonomyLevel = level.name();
          Store.save();
          ui.rebuild();
        });
  }

  private void renderAutoActionCard(UiTheme t) {
    boolean running = Actions.job instanceof AutoActionJob;
    ui.card(
        "后台智能连点 / 挂机（不抢真实鼠标）",
        running ? "挂机执行中" : "就绪",
        running ? t.success() : t.accentAlt(),
        () -> {
          ui.mutedText(
              "面向刷石机、刷怪塔、手摇曲柄或筛网；点击后自动回到世界开始执行，可配合 F4 省电静音。");
          ui.row(
              "刷石机连挖（准星方块）",
              () -> startManualAutoAction(GeneratorPolicy.AutoMode.MINE_HOLD, 0, true),
              "刷怪塔满蓄力打怪",
              () -> startManualAutoAction(GeneratorPolicy.AutoMode.ATTACK_CLICK, 0, false));
          ui.row(
              "曲柄长按右键",
              () -> startManualAutoAction(GeneratorPolicy.AutoMode.USE_HOLD, 2, false),
              "筛网/骨粉连点右键",
              () -> startManualAutoAction(GeneratorPolicy.AutoMode.USE_CLICK, 4, false));
          if (running) {
            ui.button(
                "停止当前后台挂机",
                () -> {
                  Actions.cancel();
                  ui.setStatus("已停止后台挂机");
                  ui.rebuild();
                });
          }
        });
  }

  private void startManualAutoAction(
      GeneratorPolicy.AutoMode mode, int intervalTicks, boolean untilToolsSpent) {
    var mc = net.minecraft.client.Minecraft.getInstance();
    if (!Agent.enabled) Agent.start();
    if (!Agent.enabled) {
      ui.setStatus("当前窗口未持有AI控制权，请在AI窗口启动");
      return;
    }
    if (Agent.paused || Cooperation.yielding() || HarnessController.busy()) {
      ui.setStatus("[BUSY] 当前已暂停、交给玩家或有前台作业，请先恢复控制或等待作业结束");
      return;
    }
    if (Actions.job != null && !DutyScheduler.ownsCurrentJob()) {
      ui.setStatus("[BUSY] 已有连续作业在执行，请先停止当前作业再开启后台挂机");
      return;
    }
    DutyScheduler.yieldCurrentJob("手动开启后台挂机：" + mode.label);
    if (Actions.job != null) Actions.cancel();
    mc.setScreen(null);
    Actions.job =
        new AutoActionJob(
            mode,
            null,
            0,
            intervalTicks,
            300,
            untilToolsSpent,
            0,
            "",
            r -> Companion.notifyUser(Rules.clean(r, 160)));
  }
}
