# MineCompanion 0.8.8

构建 `release-0.8.8-20261127-01`，正式发布版。适用于 **Minecraft Java 1.20.1 · Forge 47.4.x · Java 17**。安装包内的运行文件为 `logistics-companion-0.8.8.jar`（`16,842,005 B`，SHA-256 `99C4176D2AC852C2B07B96B38388CB72CD6FFBFC64485E6C4C973AC3495A263C`）。

## 本版分工

- **项目维护者** 主导 `0.8.7 → 0.8.8rc1..rc17 → 0.8.8` 大更新周期与架构拍板，提出长程目标板、Agent 自维护白名单、自动 3D 脚手架与拆架、亮色工作台方案 A 重构，以及平地/填充/大规模开垦统一扁平参数引擎（含创造与服务器 `/fly`、模组飞行落地安全）。
- **Muse** 确立声音事件感知、静音/无字幕兼容、白名单分级与 0ms 客户端急救反射核心架构。
- **Hydrogen** 结合多格/压缩抽屉实机操作反馈了左键放行与面内局部 UV/子槽（`hit_u`/`hit_v`/`sub_slot`）需求。
- **Codex / Carbon / Neon / DeepSeek** 在独立 1.20.1 Forge 实例完成多轮 `.litematic`/`.nbt` 蓝图建造、3D 脚手架、抽屉交互与梯井活板门实机验收定位，提出屋檐站位、固体坡道 LIFO 拆架与独立 `scaffold_build` 原语关键改进。
- **Astra (`gpt-6.2-astra`) & Claude Opus** 提供弹道/最大流合成/3D 探索图/脚手架 0-1 BFS 核心算法方案与全仓只读审阅，并与 Gemini 完成多人 `@` 收件箱、`rc9/rc10` 边界审计及统一三维整地/筑造/开垦架构两轮深度收敛。
- **Gemini** 完成 `0.8.8rc1`–`0.8.8rc17` 全量 Java 实现、工作台方案 A 重构、算法 Wave 1/2 落地、21 个新测试套件扩充（累计 799 项单测）、多轮实机闭环调试及零代码改动正式构建与交付。

各阶段完整记录与历史署名见 [版本与贡献记录](../../VERSION-HISTORY.md)。

## 0.8.8 核心更新（对比 `0.8.7`）

1. **统一三维整地、筑造与大规模灌溉农田引擎（`rc13`–`rc17`）**：
   - 新增 `terraform_preview`、`terraform_run`、`terraform_control` 与 `SkillCatalog`（`VERSION = "10"`）三项技能预设，以统一扁平参数 Schema 覆盖 `flatten`（`cut_only`/`cut_and_fill` 自顶向下切削、`[FALLING_COLUMN_ABOVE]` 落砂柱保护、箱子等方块实体防护）、`fill`（`solid`/`hollow`/`walls`/`replace_air` 自底向上建造与活体生物占格礼让）及 `farm_grid`（9×9 分段水源网格、含水上半砖封盖、`pool_2x2`/`inventory` 取水循环、防困退后叶序单轮开垦即播）。
   - 按 `abilities.flying` 无差别自动执行 `LAND` 潜行安全降落（兼顾创造与 `/fly`/模组飞行）；每次至多 1 个在途定站微操作（`FixedStanceOp`）；扩展 `Navigator.startKnownWalk(..., allowedEdge)` 严禁越边踩踏耕地；支持保留 `expected_plan_hash` 的 `pause`/`resume`/`abort` 断点重采样去碰撞与满背包堆叠空桶（`INVENTORY_FULL`）守卫；拆分 `MiningJob` 的 `BULK` 与 `EXPLICIT_TARGET` 授权。
2. **纯 Java 蓝图引擎、多模式 3D 脚手架与诚实破坏回滚日志（`rc1`–`rc4`, `rc8`–`rc12`）**：
   - 新增零 Minecraft 依赖的 `BlueprintFormat` / `BlueprintPlanner` / `BlueprintLibrary` / `BlueprintBuildJob`，支持 `.nbt` 与 `.litematic` 解析、0/90/180/270 旋转镜像、BOM 缺口对比、3 波次支撑优先施工与检查点续建。
   - 新增 `BlueprintScaffoldPlanner`（Forge 1.20.1 脚手架 0-1 BFS + 固体阶梯/支柱中继）、独立 `scaffold_build` 原语工具（`ScaffoldJob`）、严格 LIFO 退步拆架、拆架后自动巡回收集散落材料（`CollectionJob`），以及低血/饥饿扣血自动断点暂停（`SurvivalPolicy`）。
   - 新增纯客户端诚实版 `RollbackJournal`（500 条环形日志，诚实统计 `retained_now`/`evicted`/`confirmed_journalable` 与不可逆容器内容警告）与 `RollbackJob` 物理撤销。
3. **寻路、攀爬、立体探索图与核心作业算法升级（`rc5`, `rc6`, `rc8`–`rc12`）**：
   - `GridSearch` 升级零 GC 开放寻址与索引最小堆；`ExplorationGraph`/`Memory`/`RoamExplorationJob` 升级 v2 立体竖井（梯子/脚手架）探索图；`ClimbRules` / `MovementPolicy` 加固木活板门自动开门与居中滞回攀爬、脚手架防潜行下穿及跑酷 180° 防打转。
   - `BowAim` 双弧弹道与移动提前量求解接入 `RangedHuntJob`；`CraftAllocation`/`RecipePlanner` 引入最大流可行性剪枝与同构多重集防组合爆炸；`MiningSearch` 提供 3D 聚类/有向 2-opt 采矿顺序规划；`GeneratorPolicy` 严堵流体邻接漏采与误拆。
4. **声音感知、0ms 客户端避险、白名单与抽屉精细交互（`rc1`, `rc3`）**：
   - 新增 `SoundSense` / `SoundReflex` / `EnvironmentWatch`（主音量静音前拦截危险音效并 0ms 急停避险，监视昼夜、雷雨与幻翼风险）与 `policy_whitelist` 五类运行期自维护兼容白名单。
   - 新增 `InteractionPolicy` + `FaceHitMath`（支持 `hit_u`/`hit_v`/`sub_slot` 投射与 `StorageDrawers` 5 盒内凹边框识别防误触、`CountUpdateMessage` 客户端计数同步与 `[PLACED_ADJACENT_NOT_INSERTED]` 精准报错）。
5. **工作台方案 A 全面重构、`GoalBoard` 与多人 `@` 收件箱（`rc1`, `rc2`, `rc7`, `rc10`）**：
   - 重构 `WorkbenchScreen` 顶栏/左栏、自适应态势遥测、一键亮/暗主题切换、独立「蓝图与建造」及「破坏回滚日志」视图，并在「输入与感知」集中管理感知开关与 5 类白名单。
   - 接入 `GoalBoard` 跨模式长程目标板、`player_mention` 两阶段 `@` 收件箱、非阻塞 `wait_events` 长轮询与 `GUEST` 只读硬沙箱（含显式 `mention_id` 失效 `[MENTION_EXPIRED]` 硬拒绝）。

## 安装与验证说明

1. 下载 **`MineCompanion-0.8.8.zip`**，关闭游戏后将 `logistics-companion-0.8.8.jar` 放入对应 Forge 1.20.1 客户端的 `mods/` 目录（更新前请备份旧 JAR 与配置）。
2. 本版基于实机通过的 `0.8.8rc17` **零 Java 源码改动**转正构建，通过离线全量构建、**160 个 JUnit 测试套件 / 799 项自动化测试**（`0` 失败、`0` 错误、`0` 跳过）及 `test-harness-lease-client.py` **28 项全通过**；`coremods/companion_input.js` 保持未改。
3. 本版在 1.20.1 Forge 实机环境完成 6/6 全套地形开垦验收（`flatten` 9/9、`fill hollow` 66/66、`fill replace_air` 16/16、`9×9 farm_grid` 断点暂停/续传 163/163 含中心含水石砖上半砖及 80/80 小麦、`10×3 farm_grid + pool_2x2` 双轮自动打水 60/60 含 28/28 小麦，另附 108 格全格实机方块复核及白昼航拍截图），并累计验证蓝图搭拆、3D 脚手架 LIFO 退拆、Storage Drawers 精准交互、梯井活板门与多人 `@` 收件箱链路。
