# MineCompanion 0.8.7

构建 `release-0.8.7-20261124-01`，正式发布版。适用于 **Minecraft Java 1.20.1 · Forge 47.4.x · Java 17**。安装包内的运行文件为 `logistics-companion-0.8.7.jar`（`16,341,288 B`，SHA-256 `70DB841D7CCEB9AC6FDDA9A201CE5B849BCB87615A122A917CDEFCDA6CB783DF`）。

## 本版分工

- **项目维护者 & Hydrogen** 主导 `0.8.6 → 0.8.7rc* → 0.8.7` 迭代周期与全仓审阅调度，提出「带玩家回家」引路功能，并完成《阈限工业》1.20.1 多轮实机定位与回归验收（确认精妙背包/箱子存取、FTB 任务书 `claim_all`、AE2 `ae_deposit` 自动找槽、Baritone 密集机器绕行零破坏与 `explore_return` 返航稳定，并定位收口阶段 `explore_roam` 后室折返与 `stuck_ms` 统计偏差）。
- **Muse** 确立 `explore_roam` 主动探图核心架构准则（步时护栏取代出程全图证明、`20s/16m` 朝向锁定、`5s` 脱困阶梯 + 5 分钟负缓存、以 `EXPLORE_NO_PROGRESS` 消灭 0 步假成功）。
- **awsquick** 完成全仓 379 个 Java 类与资源文件深度审阅及 **79 项**安全与稳健性修复（10 条 P0 + 28 条 P1 + 41 条 P2）。
- **Gemini** 完成 `0.8.7rc1`–`rc3` 架构落地、兼容与全仓修复集成调优，并在 `0.8.7` 正式版重写 `ExplorationRoamPolicy` 与 `RoamExplorationJob` 迷宫走廊优先寻路与精确统计。

各阶段完整记录与历史署名见 [版本与贡献记录](../../VERSION-HISTORY.md)。

## 0.8.7 核心更新（对比 `0.8.6`）

1. **主动探图开图与「带玩家回家」引路（`rc1` + `0.8.7` 收口重写）**：
   - 新增 `explore_roam` 出程主动探图引擎，结合开阔地前置校验与迷宫 `openExplorationStep` 走廊优先 BFS、±45°/±90° 侧向绕墙、`20s/16m` 朝向锁定、独立 `SESSION_SEEN_CELLS`、25 tick 步进宽限真实 `stuck_ms` 统计、岩浆/敌怪脱困护栏及目标级失败冷却，彻底解决迷宫折返与原地 0 步空转。
   - `ExplorationMemory` / `ExplorationGraph` 在 `observe()` 与 `MAP_NO_ROUTE` 时自动重扫自愈 `STALE` 邻格边。
   - 新增 `explore_return(guide_player=true)`「**带玩家回家**」引路模式（含聊天口令 `带我回家` / `带我去 <地标>` 与工作台按钮），沿已记录 3D 路线领路并在玩家掉队时自动停步转身等待重聚。
2. **室内 `go_to` 安全回退与容器/值守体验升级（`rc1` / `rc2`）**：
   - `Navigator` + `ArrivalGate.fallbackCandidate` 对 24 格内已加载移动优先本地过门/活板门寻路，被家具或占格阻挡时自动回退至同高度 1~2 格最近安全格（严守 `|dy|<=0.65` 不误判隔层）。
   - 适配 `sophisticatedbackpacks:backpack` 潜行放置与 `confirm=true` 安全挖起；新增 `StorageMenuPolicy` 放行无特殊控件的标准 `ChestMenu`（如《精致家具》储物柜/抽屉）存取；修复 `gui_slot` 误报 `CURSOR_OCCUPIED` 与 `move_inventory` 返回 `hotbar_slot` + `select=true` 主手即选。
   - `ae_deposit` 支持只传 `item`（及可选 `count`）自动匹配背包槽位；`query_world` 分页上限放宽至 `256`。
   - 新增 `ESCORT_GUARD` 队友目标锁定、后台拾取失败冷却不死循环、`ChatBuffer` 多人聊天暴露、`waystones:waystone` 自动点亮，以及 `STANDBY` 待命可配置饥饿阈值自动吃到满（默认关闭，仅用随身背包普通食物）。
3. **全仓 79 项 P0/P1/P2 安全与稳健性全面加固（`rc3` + 调优）**：
   - **P0 关键防崩溃/防丢物/防越权**：AI 暂停/人工接手/自卫即刻停止 Baritone；`PlatformJob` 搭桥全阶段维持潜行防坠；探索分片损坏只读降级 + `Companion.tick` 子系统异常隔离；`DropInventoryJob`/`HandoffJob` 跨 tick 切槽防误丢手里装备；FTB 任务书 `claim_all` 跳过自选奖励与排除项；畸形技能包校验与工作台安全复位；云端切服务商清空 API Key；拦截 `game_input` 聊天框 `/` 偷跑指令及 `summon`/`give` NBT 选择器绕过。
   - **P1/P2 全面加固**：低血自愈/待命进食不打断、身高全身致命方块校验、4096 节点图 `trim` 引脚保护与悬空边跳过、`InteractionReceipt` 因果证据收紧、`SmeltFuelPlan` 最小浪费防烧容器工作台、四段与 MC 前缀版本号解析、`BundleAdaptation.uniqueFileStem` 单射防覆盖、多线程异步截图与存盘合并写、兼容《阈限工业》联机/后室野外容器安全搜刮等。

## 安装与验证说明

1. 下载 **`MineCompanion-0.8.7.zip`**，关闭游戏后将 `logistics-companion-0.8.7.jar` 放入对应 Forge 1.20.1 客户端的 `mods/` 目录（更新前请备份旧 JAR 与配置）。
2. 本版已通过离线洁净构建及 **139 个 JUnit 测试套件 / 718 项自动化测试**（`0` 失败、`0` 错误、`0` 跳过），`coremods/companion_input.js` 保持未改。
3. 本版已完成全仓审计加固与实机关键链路（FTB `claim_all`、AE2 自动找槽、Baritone 防拆、`explore_return` 回基地）验证及主动探图迷宫收口。
