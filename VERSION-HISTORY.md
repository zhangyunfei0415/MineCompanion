# MineCompanion 版本与贡献记录

这里记录公开发行和影响下一版的重要开发节点。贡献按可核对的工程、测试和产品决策分工记录。自动化测试和实机验收在各版本内分别说明。

## 0.8.7 · 2026-11-24

**正式发布版：整合主动探图开图与「带玩家回家」引路（`rc1`）、室内障碍同高度自动回退与 AE/背包/待命自理进食体验升级（`rc2`）、全仓 379 个 Java 类审阅 79 项 P0/P1/P2 安全与稳健性加固（`rc3`），并在最终收口彻底重构 `RoamExplorationJob` 迷宫走廊优先寻路与精确统计（消除虚高 `stuck_ms`、消除 `newCells` 同步竞态、防止逆向 `FRONTIER` 折返并实现 ±45°/±90° 绕墙切向选路）。** 构建版本为 `0.8.7` / `release-0.8.7-20261124-01`，产物为 `logistics-companion-0.8.7.jar`（`16,341,288 B`，SHA-256 `70DB841D7CCEB9AC6FDDA9A201CE5B849BCB87615A122A917CDEFCDA6CB783DF`）。

| 贡献 | 完成方 | 变更 |
| --- | --- | --- |
| 三轮实机验证、需求定型与全仓审阅调度 | 项目维护者 & Hydrogen | 完成《阈限工业》1.20.1 连续三轮（`0.8.6` / `rc1` / `rc3`）实机定位与回归验收：验证通过普通箱子/精妙背包方块存取、FTB 任务书 `claim_all`（安全跳过自选奖励）、AE2 终端联动与 `ae_deposit` 自动找槽、Baritone 密集机器零误拆绕行与 `explore_return` 精准返航，并锁定 `explore_roam` 后室折返与 `stuck_ms` 虚高两项收口根因；项目维护者提出「带玩家回家」功能并主导 `0.8.7` 迭代周期与全仓审阅调度。 |
| 主动探图底层原则确立 | Muse | 确立 `explore_roam`“以步时护栏取代出程全图证明、20s/16m 朝向锁定、5s 脱困阶梯 + 5 分钟负缓存、消除 0 步假成功”核心架构设计。 |
| 全仓 79 项安全与稳定性审计修复（`rc3`） | awsquick | 对全仓 379 个 Java 类与资源文件审计并修复 10 条 P0（Baritone 暂停/自卫急停、搭桥潜行保持、探图岩浆脱困状态机、探索分片损坏防崩、跨 tick 切槽防错丢、FTB 自选保护、畸形技能包防御、切换云端清密钥、拦截聊天框 `/` 指令与 NBT 选择器绕过）、28 条 P1 与 41 条 P2 问题。 |
| 主动探图、「带玩家回家」、`rc1/rc2` 兼容重构与最终迷宫走廊收口 | Gemini | 在 `rc1`/`rc2`/`rc3` 集成与调优之上，于 `0.8.7` 正式版重写 `ExplorationRoamPolicy`（`STUCK_GRACE_TICKS` 25 tick 步进宽限、`headingAlignment`、`pickHeadingGuided`、`scoreFrontier` 与 `scoreCorridorCell`）与 `RoamExplorationJob`：引入独立 `SESSION_SEEN_CELLS` 修正 `newCells` 竞态、`FAILED_TARGETS` 目标级冷却免误伤当前区块、开阔地前置校验 + 迷宫 `openExplorationStep` 走廊 BFS 优先选路、前后方向加权防逆向折返，以及岩浆/撤退通路可行性校验；调优 `DutyScheduler` 后室野生容器兼容并完成 `0.8.7` 正式构建。 |

离线全量构建及 **139 个测试套件 / 718 项测试全部通过**（0 失败 / 0 错误 / 0 跳过，新增 `ExplorationRoamAndCompatTest#roamGuidedHeadingAndCorridorScoringAvoidUturnsAndFalseStuck`）；`coremods/companion_input.js` 未修改（SHA-256 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4`）。已完成 `0.8.7` 正式版 JAR 打包，待发布至 GitHub。

## 0.8.7rc3 · 2026-11-24

**安全与稳健性全面加固候选版：合入基于全仓 379 个 Java 类与资源深度审阅的全部 79 项缺陷修复（P0 10 / P1 28 / P2 41，改动 74 个 Java 文件 + 1 个内置技能包 JSON），在保持默认后室/野外搜刮与正常操控零负担的前提下完成全量编译集成、协议与因果证据测试升级、回归单测补全及后室野生容器兼容调优。** 构建版本为 `0.8.7rc3` / `audit-hardening-rc3-20261124-01`，产物为 `logistics-companion-0.8.7rc3.jar`（`16,336,164 B`，SHA-256 `578281A8756A0EF9D5C2A94C84ECDDBD72E2A69E0EC7560C65E417DAE1B40909`）。

| 贡献 | 完成方 | 变更 |
| --- | --- | --- |
| 全仓静态审阅与 79 项 P0/P1/P2 修复产出 | awsquick & 项目维护者 | 在高智能模式下完成 `0.8.7rc2` 全仓 379 个 Java 类、`companion_input.js`、技能包与配置文件通读，产出涵盖 **10 条 P0 + 28 条 P1 + 41 条 P2** 的定位与修复包（`companion-fix-all-src.zip`，共 75 个源码/资源文件，经 SHA-256 逐一校验对齐基线）。 |
| P0 关键安全性与防崩溃/防丢物修复（10 项） | awsquick | 补齐 `BaritoneNavigation.cancelActive` 暂停/人工接手/伙伴面板/自卫急停、`PlatformJob` 全阶段维持 `sneak=true` 防坠、`RoamExplorationJob` 岩浆脱困 `escapeTicks` 状态机、`ExplorationStore.reloadReadOnly` 防 NPE 崩溃及 `Companion.tick` 子系统异常隔离、`DropInventoryJob`/`HandoffJob` 跨 tick 切槽防错丢、`QuestBookBridge.claim_all` 跳过自选与排除项、`SkillPackage`/`SkillLibrary`/`WorkbenchScreen` 畸形字段防御、`WorkbenchSettingsViews`/`DecisionService` 切换云端服务商清密钥与一致性校验、`GameInputJob` 拦截聊天框 `/` 绕过，以及 `GameCommandPolicy`/`CommandWords` 拦截 `summon`/`give` NBT 与引号内广泛选择器。 |
| P1 功能与调度可靠性加固（28 项） | awsquick | 修复 `Navigator.ownsInput` 与 `EatJob` 低血自愈碰撞、`Navigator.stand` 全身高致命方块检查、`waterRecoveries` 入口清零、`RangedHuntJob` 近身 4 格威胁退让、`BaritoneNavigation` `EntityBlock` 防误拆保护、`ElytraRouteJob` 烟花内联不中断滑翔、`ExplorationGraph` 满 4096 节点引脚保护与悬空边跳过、`ExplorationMemory` 分布式锁原子抢占、`RoamExplorationJob` 敌怪护栏 `ticks` 递增与撤退推进、`ContainerJob`/`MenuClickJob`/`InventorySession`/`ControlJob` 光标与关门防掉物核验、`InteractionOutcome`/`InteractionReceipt` 因果证据收紧、`GuiProfileActions`/`VersionRange`/`SmeltFuelPlan`/`FarmJob` 匹配与燃料安全、`DutyScheduler` 回城退避与 `tickStandby` 待命进食接入、`HarnessController` 只读查询免打断值守与异步截图编码、`Agent` 中断 `tool_call` 协议补全，以及 `WatchMode` F4 防误触。 |
| P2 边界缺陷、性能与资源安全性修复（41 项） | awsquick | 修复 `Move`/`InventoryMemory`/`CollectionJob`/`MachineObservation`/`AeTerminalBridge`/`Hud` 等主线程性能与 NBT 热路径卡顿，补充 `BundleLibrary`/`BundleAdaptation` `uniqueFileStem` 单射防覆盖与 `builtin:` 伪装拦截、`Store` 坏档防覆盖备份、`ChatOutbox` 过滤 `§` 防踢线、`read_web` 总超时与私网段拦截、`RecentWrites` 换世界清理、`CommandJob` 精确 `tp` 坐标与 `clone` 保护、以及内置熔炉示例技能收紧批量至 `8` 件并重算 SHA-256。 |
| 合入集成、后室搜刮 UX 调优、回归测试与 `rc3` 构建发布 | Gemini | 校验合入全量 75 个文件并保留 `coremods/companion_input.js` 未改；调整 `DutyScheduler` 野外容器搜刮策略——在严格保留基地围栏、`Store.data.places`、世界记忆已登记容器（含双箱半格）及潜影盒隔离的前提下，移除硬性“仅限单人+刷怪笼旁”限制以兼容《阈限工业》联机/后室搜刮；同步更新 `GameCommandPolicyTest` 与 `InteractionReceiptTest` 至加固后契约并新增 `AuditHardeningRc3Test`（覆盖满图引脚、最小浪费燃料、四段/前缀版本、指令 NBT/选择器及单射文件名），完成 `0.8.7rc3` 离线构建与回归验收。 |

离线全量构建及 **139 个测试套件 / 717 项测试全部通过**（0 失败 / 0 错误 / 0 跳过，包含更新后的 `GameCommandPolicyTest`/`InteractionReceiptTest` 与新增 `AuditHardeningRc3Test`）；`coremods/companion_input.js` 未修改（SHA-256 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4`）。

## 0.8.7rc2 · 2026-11-23

**候选更新版：围绕 `0.8.7rc1` 实机五项待修清单定向加固——近距室内 `go_to` 优先本地网格寻路与同高度 1~2 格安全空位自动回退、`ae_deposit` 支持按 `item`（及可选 `count`）自动匹配背包槽位、`move_inventory` 回执显式返回目标 `hotbar_slot` 并支持 `select=true` 即时选中、新增 `STANDBY` 待命可配置饥饿阈值自动吃到满策略，以及 `query_world` 单页上限放宽至 `256`。** 构建版本为 `0.8.7rc2` / `roam-compat-rc2-20261123-02`，产物为 `logistics-companion-0.8.7rc2.jar`（`16,298,959 B`，SHA-256 `B36BF9DD9B459A9F3B308FE73C76992E98DF66175667DDD12AB0BE750BE0C910`）。

| 贡献 | 完成方 | 变更 |
| --- | --- | --- |
| `0.8.7rc1` 实机回归验收与五项待修清单定位 | Hydrogen & 项目维护者 | 在实机确认普通箱子/精妙背包存取（含 280 灰尘/187 混凝土块/羽毛）、任务书、AE2 终端识别及 `eat_food`/`recent_chat` 等稳定通过的同时，准确定位基地内 `go_to` 遇障碍/活板门卡死与 `[NOT_REACHABLE]`、`ae_deposit` 强依赖 `inventory_slot`、`move_inventory` 未回传具体 `hotbar_slot`、`STANDBY` 下缺可配自理吃到满触发器，以及 `query_world` 单页被截断在 64 条等问题。 |
| 室内 `go_to` 本地优先与同高度安全回退 | Gemini | 在 `ArrivalGate` 新增纯逻辑 `fallbackCandidate`（严守同层 `|dy|<=0.65`，水平覆盖 1~2 格空位）；`Navigator` 对 24 格内已加载短距移动优先走本地 A*+过门开活板门处理，Baritone 在 96 格内卡停时自动接管到本地寻路，当原目标格被家具/地毯等占格或孤立受阻时经 `collectFallbackGoals` 自动回退至同高度最近可达安全格，并由 `Actions.move` 在回执中写明回退落点坐标。 |
| `ae_deposit` 按 `item` 自动匹配背包槽位 | Gemini | 在 `AeTransferProof` 新增 `selectDepositSlot`（`inventory_slot` 省略或为 `-1` 时按 `item` 优先主背包 `9..35` 再快捷栏 `0..8` 匹配整组，可选匹配或省略 `count` 整组存入，并保持数量不符时明确报错提示不自动拆分）；同步更新 `AeTransferJob`、`Agent` 与 `ToolSchemas`。 |
| `move_inventory` 回传 `hotbar_slot` 与 `select` 支持 | Gemini | `InventoryMove` 在 `destination=hotbar` 的所有成功路径均固定写入 `hotbar_slot=<n>`；`Agent` 与 `ToolSchemas` 为 `move_inventory` 新增 `select=true` 可选参数（移动并在服务器快照确认后直接切换主手选中该格），未传时在成功回执中标明当前是否已选中及对应 `select_slot`。 |
| `STANDBY` 待命饥饿阈值自动吃到满策略 | Gemini | 在 `CompanionDuty` 新增持久化字段 `standbyAutoEat`（默认关）、`autoEatThreshold`（默认 `10`、`2..19`）与 `autoEatTarget`（默认 `20`、`>threshold..20`）及纯逻辑判定 `shouldTriggerAutoEat`；`DutyScheduler` 在待命或值守空闲时触发连续补食至目标饱食度（仅扫描原版随身背包 `0..35` 普通食物，不触碰精妙背包，并由任何显式任务即刻抢占），`duty_set`/`duty_status`、工作台 UI 与 `ToolSchemas` 同步开放调节。 |
| `query_world` 分页上限放宽至 `256` | Gemini | 将 `Agent`、`WorldSense.query` 与 `ToolSchemas` 的 `query_world` 单页 `limit` 上限由 `64` 放宽至 `256`（默认仍为 `16`），减少对象密集基地翻页次数。 |

离线全量构建及 **138 个测试套件 / 712 项测试全部通过**（0 失败 / 0 错误 / 0 跳过，新增 `Rc2CompatAndFallbackTest` 覆盖同层安全格回退、`ae_deposit` 槽位匹配与 `STANDBY` 阈值进食）；`coremods/companion_input.js` 未修改（SHA-256 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4`）。等待实机二次验收后定版 `0.8.7` 正式版。

## 0.8.7rc1 · 2026-11-23

**候选更新版：围绕《阈限工业》实机反馈与探图架构重构，上线 `explore_roam` 主动开图与「带玩家回家」引路模式、修复 `STALE` 边自愈与 `AUTO_FULL` 死亡循环、打通后台收集全路径零 `[BUSY]` 抢占与队友目标 `ESCORT_GUARD`、适配精妙背包潜行放收与标准 `ChestMenu` 容器存取、加固岩浆窄格避险并补充多人聊天缓冲/自动点亮指路石/刷石机容错。** 构建版本为 `0.8.7rc1` / `roam-compat-rc1-20261123-01`，产物为 `logistics-companion-0.8.7rc1.jar`（`16,290,981 B`，SHA-256 `FD98EC58FBAB3DB2BD5906341BF5336356CE97F2C2BD917E7D4AACFE7EFF4CC5`）。

| 贡献 | 完成方 | 变更 |
| --- | --- | --- |
| 后室实机缺陷定位与「带玩家回家」需求提出 | 项目维护者 & Hydrogen | 在《阈限工业》1.20.1 `0.8.6` 实机排查中定位 `STALE` 边锁死、`AUTO_FULL` 降级死磕掉落物、室内与岩浆旁脱困、精妙背包与《精致家具》`ChestMenu` 适配、`BASE_STEWARD` 阻塞 `duty_set`/`go_to`、队友跟随抢回主人、`[CHAT]` 暴露、指路石点亮及刷石机切态等问题；项目维护者补充提出由伴侣沿已记录三维路线「带玩家回家」功能。 |
| 主动探图核心架构提案 | Muse | 指出旧探索流程「出程先预验证路线」与 `stop_on_junction` 起点 `0` 步假成功两大根因，提出朝向推进与步时护栏、区块/扇区新颖度、`20s/16m` 朝向锁定、`5s` 卡住恢复阶梯 + `5` 分钟负缓存及 `EXPLORE_NO_PROGRESS` 验收体系。 |
| 主动开图、`STALE` 自愈与「带玩家回家」引路实现 | Gemini | 新增 `ExplorationRoamPolicy` 与 `RoamExplorationJob` 实现 `explore_roam` 出程主动探图（区块新颖度、朝向锁定、室内出口 BFS、敌对与岩浆护栏、5 秒脱困与 5 分钟负缓存）；在 `ExplorationGraph` / `ExplorationMemory` / `ExplorationJob` 中加入 `revalidateEdges`/`revalidateLocalEdges` 自愈、修复 `explore_branch` `steps > 0` 防 0 步假成功、为 `direction()` 增加开口回退，并落地 `explore_return(guide_player=true)` 与 `ChatCommand.DutyAction.GUIDE_HOME`（「带我回家」/「带我去 \<地标\>」）及工作台按钮。 |
| 值守降级止损、全路径抢占、队友护卫与岩浆安全脱困 | Gemini | `CollectionJob` 恒定触发 `skipListener` 维护 `blockedDrops`（同世界切模式保留）并抑制零收获开包闪屏；`DutyScheduler` 阻止探索失败盲切 `BASE_STEWARD`、扩展 `CompanionDuty` 支持 `escortTargetUuid`/`escortTargetName` 队友 `ESCORT_GUARD`；`HarnessController`/`TaskGateway`/`Agent` 全路径自动抢占后台收集消除 `[BUSY]`；`SwimRecovery` 与 `Navigator`（`lethalHazard`/`isNarrowHazardAdjacent`）禁止向紧邻岩浆或火焰窄格侧移。 |
| 精妙背包、标准 `ChestMenu` 与 `gui_slot` 兼容修复 | Gemini | 修复 `SneakHold` 真实按键预热保持及 `ItemOnBlockOperation`/`PlaceJob` 潜行放置确认；`BreakPolicy` 放行自保留 NBT 便携容器（经 `confirm=true`）及确认清空的通用储物方块并保留未知机器拦截；新增 `StorageMenuPolicy`（仅校验简单类名与界面结构，拦截 `workbench`/`machine`/`craft`/`factory` 等危险界面）自动放行无特殊控件的标准 `ChestMenu` 存取；修正 `MenuClickJob` 与 `ReasonCodes` 的 `QUICK_MOVE_NO_EFFECT` / `CURSOR_OCCUPIED` 判定。 |
| 聊天记录暴露、自动指路石与刷石机左键容错 | Gemini | 新增 `ChatBuffer` 并在 `observe`/`events` 输出最近多人 `[CHAT]`；`DutyScheduler` 自动点亮附近 `waystones:waystone` 并标为 `SAFE_POINT`；`GeneratorPolicy` 与 `AutoActionJob` 增强刷石机切态容错与左键持续挖掘支持。 |

离线全量构建及 **137 个测试套件 / 709 项测试全部通过**（0 失败 / 0 错误 / 0 跳过，新增 `ExplorationRoamAndCompatTest`）；`coremods/companion_input.js` 未修改（SHA-256 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4`）。等待《阈限工业》与平原实机双端验收后冲刺 `0.8.7` 正式版。

## 0.8.6 · 2026-11-22

**正式发布版：整合三维探索记忆（`rc1`）、通用科技模组菜单重同步与全主题输入框（`rc2`）、G9/G10 易变 NBT 修复与女仆式值守系统（`rc3`）、涉水/爬梯/跑酷与 F4 节能降占（`rc4`），并在收口波次及最终审阅加固中完成驻守持续清场拾取、双开防串台伴侣联动、AI 自主切档、主人视线感知/递物资、活板门与按钮铁门通行、后台值守零 `[BUSY]` 抢占、刷石机流体安全白名单、内置智能后台连点/长按挂机引擎与「Gemini 星灵」双画风立绘。** 构建版本为 `0.8.6` / `release-0.8.6-20261122-02`，产物为 `logistics-companion-0.8.6.jar`（`16,252,660 B`，SHA-256 `0560FAA992C7465D24D53C4F1F52D2609834F660F25A358F93327ED01FBF0572`）。

| 贡献 | 完成方 | 变更 |
| --- | --- | --- |
| 实机连续问题定位与 Gemini 形象素材提供 | 项目维护者 | 实机定位「双开公屏自回」「驻守基地死盯蠹虫笼内掉落物导致新掉落不收」「后台收集把 `duty_set`/`go_to` 拦成 `[BUSY]`」「刷石机触发 `NEIGHBOR_HAZARD` 与缺左键连挖」，拟定 `0.8.6 → 0.8.7rc* → 0.8.7` 版本节奏并提供「Gemini 星灵」高清（`MOE`）与 MC 像素（`MC`）双 `4×2` 透明图集。 |
| 驻守基地持续清场与拾取加固 | Gemini | `CollectionJob` 补全 `!hasPickUpDelay()`、按「空地可直达优先于碰撞方块内受阻」排序、缩短到达未入包超时至 18 刻并接入跳过回调；`PickupGeometry.preferredCloseTouches` + `Navigator` 优先贴近内圈落脚消除 1.3 格临界边缘干等；`DutyScheduler` 将驻守拾取改为 `sweep=true` 清场、维护 45 秒跨轮次卡死黑名单，并在返回锚点途中持续监测新掉落物。 |
| 双开防串台与真人主人伴侣联动 | Gemini | 新增 `ClientRolePolicy` 在 `Companion.chat` 入口拦截未控窗与真人号自己的公屏 `@` 派单自回；非控制锁窗口通过 `Hud` 显示 `伴侣同步 · 联动` 标题并在 `ExplorationMemory` / `ExplorationStore.reloadReadOnly` 每 3 秒只读热加载 AI 写盘的 3D 探索图。 |
| AI 自主调档、共享注意力与通道拓展 | Gemini | 新增 `DutyAutonomyPolicy` 与三档调档权限（`AI全权自主调档` / `仅安全自主调档` / `人工锁定模式`），支持 20 秒防抖与 5 分钟人工覆盖宽限期；将值守状态与 `OwnerContext` 主人视线摘要注入 `AgentObservation` 及系统提示词；新增只读工具 `observe_owner_focus` 与安全递交工具 `handoff_to_owner`（`HandoffJob`）；`NavigationPassage` 补齐木质活板门与带按钮/拉杆铁门通行。 |
| 零 `[BUSY]` 抢占、刷石机连刷与智能连点挂机引擎 | Gemini | `DutyScheduler` 记录并允许 `HarnessController` / `Agent` 自动取消后台值守独占任务，彻底消除 `[BUSY]` 阻塞；新增 `GeneratorPolicy` 放行干地安全条件下水平流体槽中的刷石/刷玄武岩定点挖掘；新增 `AutoActionJob` + `auto_action` 工具及工作台一键挂机卡（`MINE_HOLD` 刷石机自动等刷新与爆镐换镐、`ATTACK_CLICK` 刷怪塔满蓄力只打敌对怪、`USE_HOLD` 曲柄长按带低饱食自动进食、`USE_CLICK` 筛网/骨粉连点自动补料）。 |
| Gemini 双画风星灵立绘接入 | Gemini | 导入 `gemini_states_moe.png` 与 `gemini_states_mc.png`（`1774×887`，透明通道）及 `gemini_states.json`，更新 `WatchAvatar`、`WorkbenchPages`、`WorkbenchSettingsViews` 与 `SettingsCatalog`，支持 `DeepSeek鲸娘 / ChatGPT龙娘 / Gemini星灵` 三角色 × `MOE / MC / OFF` 风格切换。 |
| 0.8.6 最终审阅与 7 项补强后公开正式版发布 | Gemini | 经最终源码与资源逐项审阅，修补 `AutoActionJob` 到位清除 `movingToReach` / 下方收集水流放行 / 模组钻头与空手兼容 / `USE_HOLD` 进食切回原格 / `USE_CLICK` 首帧锁物自动补料、`ExplorationMemory` 旁观窗禁抢写锁、`NavigationPassage` 铁门可达开关优先与悬空地板活板门防坠、`DutyScheduler` 自主切跟自动绑主与 `MANUAL_LOCK` 严格拦截、`WorkbenchDutyView` 无权保护、`HandoffJob` 窄道直丢兜底与原版 40 刻冷却提示、以及 `StatusFeed` 挂机/拾取八态立绘映射，完成官方 GitHub `v0.8.6` 公开构建与打包发布。 |

离线全量构建及 **136 个测试套件 / 705 项测试全部通过**（0 失败 / 0 跳过，新增 `BasePickupAndAutonomyTest` 含双开锁/八态扩展断言）；`coremods/companion_input.js` 未修改。已打包并发布至官方 GitHub `v0.8.6` 正式版。

## 0.8.6rc4 · 2026-11-22

**候选更新版：移动与寻路全面强化（跟随自动疾跑、1 格浅水涉水、梯子/藤蔓攀爬、助跑跑酷）、新增 F4 一键限帧静音节能、前后端持久化/HUD 修复及二轮极限审阅加固。** 构建版本为 `0.8.6rc4` / `movement-eco-rc4-20261122-02`，产物为 `logistics-companion-0.8.6rc4.jar`（`13,142,712 B`，SHA-256 `9B5C310CBF921A99A2E32D8634BC4AC800A60D88BD3C14DBF30671C63214571C`）。

| 贡献 | 完成方 | 变更 |
| --- | --- | --- |
| 实机跑酷/水坑/疾跑/F4 节能需求反馈 | 项目维护者 | 在阈限工业（`Liminal Industries`）泳池层/积水走廊及多层后室场景中，准确定位「小水坑寻路绕死」「不爬梯子」「悬空缺口需助跑跳」及 `F4` 后台高帧率/叠音降占需求。 |
| 移动、涉水、爬梯与助跑跑酷升级 | Gemini | 扩展 `GridSearch.weighted()` 加权代价搜索与纯逻辑策略 `MovementPolicy`；`Navigator` + `PlayerControls.motionState(forward, jump, sprint, sneak)` 区分致命流体（岩浆/未知液体）与 `FluidTags.WATER` 普通浅水，支持 1 格涉水（含水草）不触发脱水阻断；放行 `BlockTags.CLIMBABLE` 垂直攀爬上下边与防摔下落；新增 `OFF` / `SAFE (1~2格)` / `FULL (含3格)` 三档助跑跑酷；支持跟随与长距移动自动疾跑（带饱食度与拐角降速护栏）。 |
| F4 节能降占与前后端审阅修复 | Gemini | 新增 `WatchPowerState` + `WatchPowerSaver`：F4 脱手观看或首页手动省电时限制窗口帧率（`15/20/30 FPS`）并仅通过 `SoundManager` 静音主音量；修复 `WorkbenchSettingsViews` 世界指令策略 3 项开关未立即写入世界文件的缺陷；统一权限页与值守页的导航地形互斥开关；`Hud` 接入值守短状态与省电徽章；`DutyScheduler` 补齐 `baseRestock` 自动归交付箱及外出容器扫描节流；`WorkbenchDutyView` 新增「移动、涉水与跑酷」开关组及多地点基地快捷绑定；`SettingsCatalog` 增补帧率/跑酷/女仆值守与 Lootr 搜索词条。 |
| `rc4` 二轮代码审阅关键加固 | Gemini | 修复 `Navigator` 爬梯 `onGround=false` 导致路点不前进死循环与零横向位移 360° 甩头，改按 `LadderBlock.FACING` 对墙攀爬、下行取消潜行卡死并在梯顶上跃平台；重构无状态跑酷为锁定起跳/落点格的 4 相位状态机（`ALIGN_RUNUP` / `SPRINT_RUNUP` / `AIRBORNE`）及边缘未达速刹车防掉崖，`dy=+1` 补全 `y+3` 矮顶碰撞与空隙致命方块校验，补强 1 格浅水上岸跳跃与涉水路点推进；修复 `WatchPowerSaver` 手动「一键省电降占」在默认关闭两项 F4 勾选时不生效的问题，彻底移除 `OptionInstance.set(0.0)` 防止玩家进设置点完成误将静音写入 `options.txt`，并在 `Companion` tick 保活静音/限帧；修复 `DutyScheduler.baseRestock` 原传 `"*"` 误存快捷栏武器/工具/食物/火把问题（新增 `CompanionDuty.isSafeAutoDepositCandidate` 仅存主背包 `9..35` 富余非装备杂物），节流离线目标跟随重连并将发现容器的 `SAFE_POINT` 修正为周围可站格；`StackSyncMatcher` 增加 `ItemStack.matches` 快路径与机械动力背罐 `Air` / 植物魔法 `mana` 易变标签剥离。 |

离线全量构建及 **135 个测试套件 / 701 项测试全部通过**（0 失败 / 0 跳过，覆盖 `MovementAndPowerSaverTest`、`CompanionDutyTest` 与 `StackSyncMatcherTest`）；`coremods/companion_input.js` 未修改。

## 0.8.6rc3 · 2026-11-22

**候选更新版：根治 G9/G10 易变 NBT 背包与菜单同步死锁、新增生存可用 `resync_inventory`、落地工作台左侧第 7 大类「跟随与陪同」女仆式值守预设系统。** 构建版本为 `0.8.6rc3` / `duty-sync-rc3-20261122-01`，产物为 `logistics-companion-0.8.6rc3.jar`（`13,119,495 B`，SHA-256 `7DF616991D6B00A510BB3A8C076161EFBF58A2AF5BD0224FFD97AEE89C75F984`）。

| 贡献 | 完成方 | 变更 |
| --- | --- | --- |
| 复测问题定位与产品开关拍板 | DeepSeek Harness（DSH）与项目维护者 | 实测确认 `thermal:energy_cell` 等储能物品引发的 G9/G10 同步死锁与后室采集特性（`docs/0.8.6rc2-复测报告-合并版-20261122.md`），确定左侧新增女仆式「跟随与陪同」大类及防爆服破坏地形生物开关、野生容器搜刮 + Lootr 识别开关、外出探索与导航合并开关。 |
| 易变 NBT 同步根治与重同步（G9/G10） | Gemini | 新增 `VolatileTagPolicy` + `StackSyncMatcher` 剥离 `ForgeCaps`、Thermal `BlockEntityTag.Energy`、Mekanism 动态储罐键（保留 `componentConfig` 静态身份）及流体/进度易变键并绕开懒加载 Cap 误判；补全 `InventoryEvidence.slot()` 接入 `ClientboundContainerSetSlotPacket`；更新 `InventorySession`（双次退避重同步与明细诊断）、`MenuContext`、`MenuSyncEvidence`（带物品 ID 点名）、`InventoryMemory`、`MenuClickJob`、`GuiGestureJob`、`GuiContext`、`PlayerEvidence`；新增生存安全工具 `resync_inventory`（`ResyncInventoryJob`）；优化 `MiningSpec` 非标签普通方块默认 `item` 推导。 |
| 女仆式「跟随与陪同」与值守引擎 | Gemini | 新增 `CompanionDuty` 与确定性 0-Token 本地调度器 `DutyScheduler`（支持 `纯待命` / `驻守基地` / `跟随与守护` / `外出探索采集` 四大预设，断线/切维/死亡自动落回安全待命，严格抢占防抢键鼠）；升级 `FollowPolicy`/`FollowControl` 三档跟随距离与 `CombatEvidence` 主人受击追踪；工作台左侧账本追加第 7 项「跟随与陪同」及 `WorkbenchDutyView`（含防爆服开关、野生容器与 Lootr `lootr:*` 独立箱分类及 `ContainerJob`/`MenuContext` 适配、导航地形合并开关与小屏防重叠布局）；扩展 `ChatCommand` 中文本地口令与 `duty_status` / `duty_set` 工具。 |

离线全量构建及 **134 个测试套件 / 694 项测试全部通过**（0 失败 / 0 跳过，新增 `StackSyncMatcherTest` 与 `CompanionDutyTest`）；`coremods/companion_input.js` 未修改。待进入游戏实机验收携带 `thermal:energy_cell` 回归、女仆四种值守预设与 Lootr/后室搜刮闭环。

## 0.8.6rc2 · 2026-11-22

**候选更新版：打通第三方科技模组通用 GUI 菜单重同步、新增全套主题输入框 `DeskEditBox`、工作台左侧新增「空间与地图」一级分类并全面重构探索地图界面。** 构建版本为 `0.8.6rc2` / `exploration-gui-rc2-20261122-01`，产物为 `logistics-companion-0.8.6rc2.jar`。

| 贡献 | 完成方 | 变更 |
| --- | --- | --- |
| 科技模组 GUI 实测问题定位 | DeepSeek Harness（DSH）与项目维护者 | 实测 Thermal / Mekanism / EnderIO / IE，准确定位第三方菜单点击后 `waiting_for_stable_server_contents` 永久阻塞、`gui_put_role` 误报、缺参数 NPE 等关键缺陷（`docs/0.8.5-科技模组GUI与外出采集-实测报告-20261122.md`），并提出「空间与地图」独立大类与探索地图可用性反馈。 |
| 通用菜单同步与工具链修复（G1–G5） | Gemini | 扩展 `MenuRefresh.supported()` 放行已收到标准 `ClientboundContainerSetContentPacket` 的常规容器（解决 Thermal / EnderIO 等非白名单机器点一次即卡死同步的问题，排除创造物品选择页）；`MenuSyncEvidence.diagnoseMismatch()` + `MenuContext.diagnoseSync()` 在 `MenuClickJob` 超时中输出精确槽位与模式诊断；`GuiProfileActions` 拆分 `[SLOT_REJECTED_ITEM]` / `[SLOT_OCCUPIED]` / `[ITEM_NOT_FOUND]`（避免误把电池/电容槽认成缺物品）；`GuiExitJob` / `ControlJob` 增加重同步重试与安全退场兜底；新增 `Rules.requiredLong/Int/Double` 消除 `game_input` 与 GUI 工具缺参裸抛 NPE。 |
| 全主题输入框与「空间与地图」体验重构 | Gemini | 新增继承自 `EditBox` 的 `DeskEditBox`（关闭原版黑底白框，全面适配 6 套明暗主题、焦距/草稿未保存态提示、占位符与滚动裁剪），替换工作台、探索地图、配方工坊、本地对话全部 5 处输入框；工作台账本左侧新增第 6 大类「空间与地图」，支持维度探索总览、地标一键定位与前往、岔口探索派发及快捷标记；重构 `ExplorationMapScreen` 为左画布 + 右侧紧凑面板，支持 `全景` / `±2层` / `单层` 三模式切换、路径压在未探边框之上防遮挡、玩家朝向指针、已验证路线虚线预览、比例尺、悬停世界坐标、快捷楼层及重要锚点跳转。 |

离线全量构建及测试全部通过，待游戏内实机复测科技模组 `gui_slot` / `gui_put_role` 投料产出闭环及探索地图/主题输入框视觉表现。

## 0.8.6rc1 · 2026-11-22

**内部候选版（基于 `0.8.6` 首轮三维探索记忆完成联合审阅、优化与 6 主题视觉升级）。** 构建版本为 `0.8.6rc1` / `exploration-memory-rc1-20261122-01`，产物为 `logistics-companion-0.8.6rc1.jar`。

| 贡献 | 完成方 | 变更 |
| --- | --- | --- |
| 探索记忆审阅与性能优化 | Gemini | 在 `0.8.6` 首轮基线上修复多入口观测岔口锚点与无变更冗余版本号递增；补充 `adjacentObservedAnchors` 与 `layers()`；直廊重通改为双端合并压缩并保留最新时间戳；跨维度传送落地回退保护（`WARP_TRANSITION`）；地标筛选消除临时 `Cell` 分配；`explore_branch` 优先探未走格并保持直行连续性；分片存储显式指定 UTF-8 编码。 |
| 探索地图与首页集成打磨 | Gemini | 重构 `ExplorationMapScreen`：缩放重绘保留视口状态、按视口边界裁剪离屏图元、增加 8 格参考网格、相邻 `Y±1` 层幽灵投影、已记录楼层快捷跳转胶囊、节点名称标签与悬停预览、彩色图例；工作台首页新增「三维探索记忆」状态卡与入口/安全点快捷标记。 |
| 6 套前端主题与视觉升级 | Gemini | 保留全部已有配置 ID 兼容的前提下新增 **Gemini 晨曦亮色（`霓虹亮`）** 并升级 **Gemini 暗夜极光（`霓虹`）**，重制 `深海` 为 **DeepSeek Blue** 风格，精调 `纸白`、`终端`、`原版` 配色；新增三段极光渐变柔光顶条 `UiDraw.fillAuroraBar` 并同步应用于工作台、探索地图与 5 个辅助界面；升级主题缩略预览卡与亮色主按钮对比度；`UiDesignSystemTest` 新增全 6 套主题 WCAG 对比度数学校验。 |
| 三维探索记忆首轮底座 | OpenAI Codex & 项目维护者 | 确定阈限工业与矿洞回程需求；完成首轮 `ExplorationGraph` / `ExplorationStore` / `ExplorationMemory` / `ExplorationJob` / 4 个探索工具及采矿检查点联动。 |

离线构建及 **132 个测试套件 / 685 项测试全部通过**（0 失败 / 0 跳过）。阈限工业迷宫与矿洞闭环待实机验收；原版 `EditBox` 黑色输入底框统一主题化改造已规划归入下一波前端批次。

## 0.8.6 · 三维探索记忆首轮 · 2026-11-22

- **项目维护者**确定阈限工业迷宫、跨层回程和矿洞工作面作为首轮目标。
- **Gemini**提供 G4Neon 与开放技能记忆基线，并补充分片、单向边和只走不拆等联合设计。
- **OpenAI Codex**实现探索图、分片、分层地图、四个 Harness 工具和采矿作业联动，完成本地 JAR 构建与离线回归。

132 个套件、684 项测试通过。阈限工业和矿洞仍待实机验收。 [首轮交接与验收说明](docs/0.8.6-EXPLORATION-MEMORY-ROUND1-20261122.md)

## 0.8.5rc1 · 2026-11-21

**公开候选版，沿用 0.8.5 G4Neon。** 安装文件为 `logistics-companion-0.8.5rc1.jar`。

| 贡献 | 完成方 | 变更 |
| --- | --- | --- |
| 产品方向 | 项目维护者 | 确定原版优先、陌生模组靠技能记忆复用的路线，确定候选版命名和发布范围。 |
| 界面与开放技能记忆基础 | Gemini | 交付 G4Neon 界面、热路径优化、通用 Tooltip 与 `UiProfile`、`.mcskill.json` 和本机适配。 |
| 派发校验与发布工程 | OpenAI Codex | 让轨道 A 验证绑定生产回执、技能、包内容和环境。轨道 B 在运行及审批后检查是否允许复用。整理 README、候选版文档和发布附件。 |
| 前序实机反馈 | DeepSeek Harness（DSH）与项目维护者 | 验收基础生存、采矿和陪伴流程，提供后续修复所用的回执与复现记录。 |

离线构建及 673 项自动化测试通过。G4Neon 界面、原版熔炉技能和跨整合包复用仍待此版实机验收。 [0.8.5rc1 发布页](https://github.com/zhangyunfei0415/MineCompanion/releases/tag/v0.8.5rc1) · [RC 行为修正提交](https://github.com/zhangyunfei0415/MineCompanion/commit/8f5ef3371759c6c312c4c46409b622d8aedf67d6)

## 0.8.5 G4Neon · 2026-11-21

- **Gemini**新增开放技能记忆中心、`.mcskill.json` 共享包、语义槽位和按钮映射、Tooltip 探测、本机适配，以及 AE2 复用轨道。包含原版熔炉示例和 Mekanism 模板。

这是 0.8.5rc1 采用的开发基础。 [开发提交](https://github.com/zhangyunfei0415/MineCompanion/commit/a8825939a7b967fba9263197151f9296e3d16324)

## 0.8.4 G4Neon · 2026-11-20

- **Gemini**重做工作台、设置、HUD 和二级页面，加入主题、搜索、草稿提示和滚动交互。同期优化设置写盘、日志后台化、反射缓存与会话清理。

[开发提交](https://github.com/zhangyunfei0415/MineCompanion/commit/7c0743ae25ea99b11c4ee2720e8cc3feb3e27088)

## 0.8.4 Alpha · 2026-11-20

- **OpenAI Codex**扩展原版生存能力和跨维度陪伴，加入开局、采铁返矿和营地技能，以及作物、工作站、载具与恢复操作。
- **项目维护者与 DeepSeek Harness（DSH）**提供前序双人跟随、采矿、装备加工和回执问题的实机反馈。

[0.8.4 发布页](https://github.com/zhangyunfei0415/MineCompanion/releases/tag/v0.8.4)

## 0.8.3 · 内部开发阶段

- **OpenAI Codex**完善任务取消、配置同步、熔炼产物与原料回执、采矿路线，以及按玩家 UUID 的跟随口令。
- **项目维护者与 DeepSeek Harness（DSH）**分批验证熔炼、采矿、双人跟随和工作站，把现场结果反馈到后续候选包。

此阶段成果随 0.8.4 Alpha 公开。 [0.8.4 源码快照](https://github.com/zhangyunfei0415/MineCompanion/tree/v0.8.4)

## 0.8.2 Alpha · 2026-11-17

- **OpenAI Codex**整理并交付连续采矿、可选本地与云端判断层，以及熔炼恢复、菜单共享视图和执行回执修复。

[0.8.2 发布页](https://github.com/zhangyunfei0415/MineCompanion/releases/tag/v0.8.2)

## 0.8.1 Alpha · 2026-11-12

- **项目维护者**选定 MineCompanion 作为公开项目名。
- **OpenAI Codex**整理首次公开源码和安装包，交付区域照明、初版自动钓鱼、方块搜索和原版界面交互。

[0.8.1 发布页](https://github.com/zhangyunfei0415/MineCompanion/releases/tag/v0.8.1)
