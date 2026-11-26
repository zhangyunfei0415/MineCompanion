package dev.mibao.companion;

import java.util.*;

/** Searchable index of every settings option and sub-editor across the G4Neon Workbench. */
public final class SettingsCatalog {
  public record Entry(
      String id,
      String title,
      String summary,
      String keywords,
      int page,
      int section,
      String category) {}

  public static final List<Entry> ALL =
      List.of(
          // Page 0: 模型与连接
          new Entry(
              "planner_driver",
              "规划入口（API 模型 / 本地 Harness）",
              "切换由内置 API 模型或外部 MCP Harness 控制器规划",
              "driver api harness mcp 规划 入口 连接",
              0,
              -1,
              "模型与连接"),
          new Entry(
              "planning_effort",
              "模型推理强度与执行风格",
              "独立调整低/中/高思考深度、快速/严谨核对风格与常用预设",
              "推理 思考 强度 执行风格 fluent strict 预算 原生",
              0,
              -1,
              "模型与连接"),
          new Entry(
              "compact_context",
              "精简工具菜单与现场观察",
              "面向小模型的精简工具集、现场槽位折叠与历史请求字符预算",
              "工具 精简 现场 context 上下文 预算 token",
              0,
              -1,
              "模型与连接"),
          new Entry(
              "goal_board",
              "长程目标板（GoalBoard 断点持久化）",
              "通过 report_plan 按世界持久保存多步清单与检查点（API 模式核心断点工具，Harness 零强迫）",
              "目标板 长程 拆解 清单 断点 report_plan goal api harness",
              0,
              -1,
              "模型与连接"),
          new Entry(
              "decision_layer",
              "局部判断层（OFF / CLOUD / LOCAL）",
              "配置独立的 GOOGLE / JEV / OPENAI 决策选优端点、Key 与自动提交",
              "判断 decision cloud local google jev openai 自动提交",
              0,
              -1,
              "模型与连接"),
          new Entry(
              "api_connection",
              "API 地址、模型名与密钥配置",
              "编辑协议(CHAT/RESPONSES/ANTHROPIC/GEMINI)、Endpoint、API Key、输出上限与端口",
              "endpoint model key api protocol chat responses anthropic gemini 端口",
              0,
              4,
              "模型与连接"),
          new Entry(
              "harness_recording",
              "Harness 本地接口与观察录制",
              "开关本机 MCP 服务、启停 JSONL 观察记录、复制 MCP 连接凭据",
              "mcp harness 录制 record token port 凭据",
              0,
              14,
              "模型与连接"),

          // Page 1: 权限与安全
          new Entry(
              "permission_mode",
              "游戏授权模式（LIMITED / ASK / FULL）",
              "切换有限权限、逐次审批或完全放权，并实时查看生效权限矩阵",
              "permission limited ask full 权限 模式 放权 审批",
              1,
              -1,
              "权限与安全"),
          new Entry(
              "full_game_control",
              "完全游戏控制与玩家放权预设",
              "一键应用 FULL + 自主准备 + 附近补给，或开关通用键鼠完全控制",
              "full game control 预设 自主 补给 放权",
              1,
              -1,
              "权限与安全"),
          new Entry(
              "external_channels",
              "外部通道与安全护栏（公屏 / 访客聊天 / 截图 / 联网）",
              "独立控制回复公屏、未授权玩家仅安全聊天、Harness 截图、联网资料、聊天提交及传送/关键方块保护",
              "公屏 访客 安全聊天 guest 截图 联网 聊天 打字 提交 安全 传送 方块 confirm",
              1,
              -1,
              "权限与安全"),
          new Entry(
              "limited_subgrants",
              "有限模式细分权限",
              "分别授权容器、GUI、丢弃、实体交互、采集、种植放置、通用输入与主动清怪",
              "容器 gui 丢弃 实体 采集 放置 种植 输入 清怪 limited",
              1,
              -1,
              "权限与安全"),
          new Entry(
              "navigation_terrain",
              "导航地形策略（仅绕行 / 破坏 / 搭路）",
              "设置寻路时是否允许破坏方块或垫脚搭路，以及仅绕行保护",
              "导航 地形 绕行 破坏 搭路 垫脚 baritone",
              1,
              -1,
              "权限与安全"),
          new Entry(
              "movement_parkour",
              "跟随疾跑、涉水过坑、爬梯与助跑跑酷",
              "在账本「跟随与陪同」控制自动疾跑、1格浅水涉水、爬梯子藤蔓及安全/完整助跑跑酷模式",
              "疾跑 跑步 跑酷 梯子 藤蔓 涉水 水坑 跳跃 跟随 parkour sprint climb water",
              1,
              -1,
              "权限与安全"),
          new Entry(
              "duty_companion",
              "女仆式值守、防爆苦力怕与 Lootr 容器搜刮",
              "配置驻守基地围栏、跟随守护防爆服开关、自主切档权限、野生容器与刷石机/后台连点挂机",
              "女仆 值守 驻守 守护 陪同 自主切档 苦力怕 防爆 lootr 搜刮 探索 重同步 刷石机 连点 挂机",
              1,
              -1,
              "权限与安全"),
          new Entry(
              "world_commands",
              "当前世界指令策略",
              "玩家常用指令白名单、自定义单行指令、clone/summon 及坐标范围上限",
              "指令 command 自定义 半径 方块 选择器 @a @e",
              1,
              -1,
              "权限与安全"),
          new Entry(
              "members_directory",
              "主人与授权成员管理",
              "添加/搜索/停用多人玩家授权、绑定 UUID、设置个人默认交付箱与汇报详情",
              "成员 member admin uuid 授权 交付 汇报",
              1,
              17,
              "权限与安全"),
          new Entry(
              "advanced_combat_death",
              "进阶行为、自卫与死亡处理策略",
              "死亡后暂停/重生待命/重评、狩猎资源动物、主动防卫、测试低血不停工",
              "死亡 恢复 狩猎 防卫 玩家 攻击 低血",
              1,
              5,
              "权限与安全"),

          // Page 2: 输入与感知
          new Entry(
              "sound_sense",
              "声音事件感知与 0ms 本地避险",
              "通过客户端声音监听器实时识别苦力怕引燃/岩浆/恶魂（关字幕与 F4 静音下仍生效）并快速撤离",
              "声音 听觉 避险 反射 苦力怕 岩浆 恶魂 静音 f4 sound reflex",
              2,
              -1,
              "输入与感知"),
          new Entry(
              "environment_watch",
              "昼夜、天气与幻翼监视",
              "按维度天空光照门控监测黄昏/入夜/雷暴/未睡眠幻翼风险（后室与下界自动静默）",
              "昼夜 天气 雷暴 幻翼 天空 日落 环境 监视 environment weather phantom",
              2,
              -1,
              "输入与感知"),
          new Entry(
              "policy_whitelist",
              "模组兼容白名单（抽屉 / 刷石机 / 音效）",
              "查看与编辑 left_tap（Storage Drawers等）、generator_blocks 与自定义音效分级白名单",
              "白名单 抽屉 刷石机 音效 兼容 storagedrawers left_tap whitelist policy",
              2,
              -1,
              "输入与感知"),
          new Entry(
              "input_behavior",
              "开门导航、后台输入与诊断显示",
              "自动开关木门/栅栏门、失焦后台按键、目标碰撞框诊断及脱手观看",
              "门 后台 输入 碰撞 诊断 观看 watch",
              2,
              -1,
              "输入与感知"),
          new Entry(
              "keybindings_overview",
              "模组快捷键总览",
              "查看工作台、一键启动、观看模式、本地对话、协同、侧栏与急停按键",
              "快捷键 面板 急停 热键 key f4 f6 f7 f8 f9 f10 home",
              2,
              -1,
              "输入与感知"),
          new Entry(
              "keys_whitelist",
              "LIMITED 模组按键白名单与导航后端",
              "配置有限模式下允许的按键白名单，检查输入能力与 Baritone 后端状态",
              "按键 白名单 模组 baritone 导航 后端",
              2,
              12,
              "输入与感知"),
          new Entry(
              "visual_assist",
              "视觉辅助与人工示范记录",
              "切换 OFF/AUTO/DEMO 视觉辅助模式、录制本地菜单示范与导出经验",
              "视觉 示范 demo auto 截图 经验 布局",
              2,
              11,
              "输入与感知"),

          // Page 3: 显示与陪伴
          new Entry(
              "visual_themes",
              "视觉主题工坊（6 套明暗主题）",
              "在 Gemini 暗/亮、DeepSeek 深海蓝、纸白、终端、原版 6 套实时调色板间切换",
              "主题 gemini deepseek 霓虹 霓虹亮 深海 纸白 终端 原版 明暗 亮色 暗色 颜色 皮肤 theme",
              3,
              -1,
              "显示与陪伴"),
          new Entry(
              "streaming_process_hud",
              "原生流式接收与前台工作过程",
              "控制 API SSE 流式推送、侧栏详细过程模式、侧栏及本地对话显隐",
              "流式 stream 详细 简洁 过程 hud 侧栏 对话",
              3,
              -1,
              "显示与陪伴"),
          new Entry(
              "hud_layout_editor",
              "拖动编辑观看布局与缩放",
              "可视化拖拽侧栏与角色立绘位置、滚轮调节尺寸及一键复位",
              "布局 拖动 缩放 复位 头像 侧栏 editor",
              3,
              -1,
              "显示与陪伴"),
          new Entry(
              "sidebar_avatar_channels",
              "侧栏参数、角色形象与消息通道",
              "调整侧栏宽度/透明度/消息数、切换角色立绘(龙娘/鲸娘/Gemini星灵)与公屏/伴侣联动通道",
              "侧栏 宽度 透明度 头像 龙娘 鲸娘 gemini 星灵 双窗口 伴侣 公屏",
              3,
              7,
              "显示与陪伴"),
          new Entry(
              "watch_power_saver",
              "F4 观看节能降占（一键限帧与静音）",
              "开启 F4 脱手观看或手动省电时自动限制 FPS（15/20/30）并关闭主音量，退出无损还原",
              "f4 帧率 fps 音量 静音 省电 挂机 占用 watch power eco",
              3,
              -1,
              "显示与陪伴"),
          new Entry(
              "identity_persona",
              "伙伴人设、主人ID与跨世界偏好",
              "修改伙伴昵称、主人、成长目标，从剪贴板导入人设与偏好文档",
              "人设 名字 主人 目标 偏好 对话 上下文",
              3,
              16,
              "显示与陪伴"),

          // Page 4: 数据与维护
          new Entry(
              "harness_lease",
              "Harness 控制租约时长",
              "预设 5分钟~24小时 / 手动停止租约，或应用自定义分钟数",
              "租约 lease 超时 心跳 分钟",
              4,
              -1,
              "数据与维护"),
          new Entry(
              "shared_library_dir",
              "共享技能库目录",
              "指定跨实例共享技能包目录并立即刷新目录校验",
              "技能 目录 共享 刷新 library",
              4,
              -1,
              "数据与维护"),
          new Entry(
              "backup_restore",
              "世界记忆备份、恢复与数据目录",
              "一键导出本世界记忆快照、恢复最近备份、打开 config 数据目录",
              "备份 恢复 导出 目录 统计 backup",
              4,
              6,
              "数据与维护"),
          new Entry(
              "blueprint_and_rollback",
              "蓝图建造与破坏回滚说明",
              "蓝图 (.nbt / .litematic) 施工位于账本「蓝图与建造」；破坏回滚日志位于账本「历史与回滚」",
              "蓝图 建造 施工 litematic nbt create bom 回滚 日志 撤销 误拆 恢复 blueprint rollback",
              4,
              -1,
              "数据与维护"));

  private SettingsCatalog() {}

  public static List<Entry> search(String query) {
    if (query == null) return List.of();
    String trimmed = query.trim().toLowerCase(Locale.ROOT);
    if (trimmed.isEmpty()) return List.of();
    String[] tokens = trimmed.split("\\s+");
    var results = new ArrayList<Entry>();
    for (var e : ALL) {
      String haystack =
          (e.title() + " " + e.summary() + " " + e.keywords() + " " + e.category())
              .toLowerCase(Locale.ROOT);
      boolean allMatch = true;
      for (String token : tokens) {
        if (!haystack.contains(token)) {
          allMatch = false;
          break;
        }
      }
      if (allMatch) results.add(e);
    }
    return results;
  }
}
