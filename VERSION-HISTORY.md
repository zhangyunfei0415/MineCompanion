# MineCompanion 版本与贡献记录

这里记录公开发行和影响下一版的重要开发节点。贡献按可核对的工程、测试和产品决策分工记录。自动化测试和实机验收在各版本内分别说明。

## 0.8.8 · 2026-11-27

- 构建标识：`release-0.8.8-20261127-01`（`logistics-companion-0.8.8.jar`，`16,842,005` 字节，SHA-256 `99C4176D2AC852C2B07B96B38388CB72CD6FFBFC64485E6C4C973AC3495A263C`）
- 贡献记录：
  - **项目维护者**：主导 `0.8.7 → 0.8.8rc1..rc17 → 0.8.8` 大更新规划，拍板长程目标板、自维护兼容白名单、自动 3D 脚手架/拆架、亮色工作台方案 A 重构，以及平地/填充/大规模灌溉开垦统一扁平参数引擎（含创造与 `/fly` 落地安全）。
  - **Muse**：确立声音事件感知、0ms 客户端避险与白名单分级核心架构。
  - **Hydrogen**：反馈抽屉多格精细点击、局部 UV 命中参数（`hit_u`/`hit_v`/`sub_slot`）与压缩抽屉左键取物需求。
  - **Codex / Carbon / Neon / DeepSeek**：完成 `rc2`/`rc4`/`rc5` 多轮 1.20.1 实机验收与根因定位，提出高层屋檐站位、固体坡道退步 LIFO 拆架与独立 `scaffold_build` 原语架构并落地实机对照反馈。
  - **Astra (`gpt-6.2-astra`) & Claude Opus**：产出弹道/合成/3D 探索图/脚手架 0-1 BFS 核心算法方案与全仓审阅，并完成多人 `@` 收件箱、`rc9/rc10` 边界审计及统一三维整地/筑造/开垦架构两轮深度收敛。
  - **Gemini**：完成 `0.8.8rc1`–`0.8.8rc17` 全模块 Java 工程实现、工作台方案 A 重构、算法 Wave 1/2 落地、JUnit 5 回归单测扩充、`rc13`–`rc17` 实机全链路调试及零代码改动正式打包发布。
- 核心改动（汇总 `0.8.8rc1`–`0.8.8rc17`）：
  1. **统一三维整地、筑造与大规模灌溉农田引擎（`TerraformSpec`, `TerraformPlanner`, `TerraformJob`, `FixedStanceOp`, `SkillCatalog` v10）**：扁平参数 Schema 支持 `flatten`（`cut_only`/`cut_and_fill` + `FALLING_COLUMN_ABOVE` 落砂柱与方块实体防护）、`fill`（`solid`/`hollow`/`walls`/`replace_air` + 活体占格礼让与自底向上填充）及 `farm_grid`（9×9 分段水格、含水上半砖封盖、`pool_2x2`/`inventory` 取水循环、防困退后叶序单轮开垦即播）；支持创造及 `/fly`/模组飞行自动 `LAND` 安全降落、`Navigator.startKnownWalk(..., allowedEdge)` 严禁越边踩踏耕地、托管背包换槽闭环、满背包堆叠空桶（`INVENTORY_FULL`）守卫及检查点 `pause`/`resume`/`abort`（保留 `expected_plan_hash` 与重采样 ID 去碰撞）。
  2. **纯 Java 蓝图施工、多模式 3D 脚手架与诚实回滚日志（`BlueprintFormat`, `BlueprintPlanner`, `BlueprintLibrary`, `BlueprintBuildJob`, `BlueprintScaffoldPlanner`, `ScaffoldJob`, `TeardownReachPlanner`, `RollbackJournal`, `RollbackJob`）**：零 MC 依赖解析 `.nbt`/`.litematic`，支持 BOM 核算、旋转镜像、Forge 1.20.1 脚手架 0-1 BFS 校验、`solid_ramp`/`pillar` 中继、严格 LIFO 逆序退步拆架与掉落物自动回收（`CollectionJob`）、低生命/饥饿扣血自动暂停（`SurvivalPolicy`）及 500 条环形破坏回滚日志（`rollback_coverage` 真实统计 `retained_now`/`evicted`）。
  3. **寻路、攀爬、探索图与战斗/合成/采矿核心算法升级（`GridSearch`, `Navigator`, `ClimbRules`, `MovementPolicy`, `ExplorationGraph`, `BowAim`, `CraftAllocation`, `RecipePlanner`, `MiningSearch`, `MiningJob`）**：零 GC 开放寻址寻路、`ExplorationGraph` v2 竖井梯子/脚手架立体探索图、木活板门自动开门与居中滞回攀爬（`ClimbRules`）、脚手架防潜行下穿及跑酷 180° 防打转护栏、弹道双弧提前量求解、`RecipePlanner` 同构多重集防爆炸与 3D 聚类采矿；拆分 `MiningJob` 的 `BULK` 与 `EXPLICIT_TARGET` 保护授权。
  4. **声音感知、0ms 客户端避险、白名单与抽屉精细交互（`SoundSense`, `SoundReflex`, `SoundPolicy`, `EnvironmentWatch`, `WhitelistPolicy`, `InteractionPolicy`, `FaceHitMath`）**：主音量静音前监听危险音效并 0ms 急停/避险；新增昼夜/雷雨/幻翼监视、`policy_whitelist` 五类自维护白名单、Storage Drawers 多盒内凹射线防误触边框与 `CountUpdateMessage` 计数感知。
  5. **工作台方案 A 重构、`GoalBoard` 长程目标板与多人 `@` 收件箱（`WorkbenchScreen`, `WorkbenchBlueprintView`, `WorkbenchRollbackView`, `GoalBoard`, `MentionPolicy`, `PlayerMentionInbox`, `HarnessEventHub`, `HarnessHttp`）**：去除硬编码横幅替换为自适应态势遥测、亮/暗一键切换与独立蓝图/回滚账本页；接入跨模式持久化 `GoalBoard`、`player_mention` 两阶段 `@` 收件箱、非阻塞 `wait_events` 长轮询与 `GUEST` 只读硬沙箱。
- 自动化验证与实机验收：
  - 基于实机全过版本 `0.8.8rc17` **零 Java 源码改动**转正构建。
  - `.\build-local.ps1 -Tasks @('test','build','--offline','--console=plain')` 全绿（**160 个测试套件 / 799 项测试**，0 失败 / 0 错误 / 0 跳过）；`test-harness-lease-client.py` **28 项全部通过**（`0.045s`）；`coremods/companion_input.js` SHA-256 保持 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4` 不变。
  - 实机多阶段验证通过：`rc17` 完成 6/6 全套扫测（`flatten` 9/9、`fill hollow` 66/66、`fill replace_air` 16/16、`9×9 farm_grid` 暂停/续传 163/163 与中心含水石砖上半砖、80/80 小麦、`10×3 farm_grid + pool_2x2` 双轮自动取水 60/60 与 28/28 小麦，另附 108 格全格方块状态复核及白昼航拍截图）；此前 `rc1`–`rc12` 并完成 Storage Drawers 精准存取、`.litematic`/`.nbt` 蓝图搭拆、梯井活板门上下行与多人 `@`/访客沙箱实机校验。

## 0.8.8rc17 · 2026-11-27

- 构建标识：`rc17-0.8.8-20261127-01`（`logistics-companion-0.8.8rc17.jar`，`16,842,004` 字节，SHA-256 `E522938866D0AC2D71029A4CDE966D23205F1CC2A24AF48D6923B02170C832D1`）
- 贡献记录：
  - Gemini：基于 `0.8.8rc16` 实机验收（`TEST 1`、`TEST 2a`、`TEST 2b` 与无 `SCREEN_OPEN` 的换槽闭环全过；`TEST 3` 在 `terraform_control(resume)` 暴露检查点重解析拦截及重采样 ID 碰撞隐患，`TEST 4` 在 `36/36` 满背包堆叠空桶舀水时因掉落地面而误报水池枯竭），完成全链路修复与 JUnit 5 回归测试补充。
- 核心改动：
  1. **断点续接 `resume` 允许保留 `expected_plan_hash` 与重采样 ID 去碰撞（`TerraformJob`, `TerraformExecutionPolicyTest`）**：`resumeFromCheckpoint` 改用 `TerraformSpec.parse(..., true)` 解析持久化规格，构造恢复作业时不沿用旧计划数字 `doneOps` ID（改以 `priorVerifiedOps` / `priorConfirmedJournalable` / `verifiedByPhase` 累加统计，`tickSample()` 替换 `plan` 时清空 `doneOps`），防止重采样生成的新工单被旧 ID 误跳过。
  2. **农田重采样跳过已耕格与退路防割点/未耕播种护栏（`TerraformPlanner.planFarmGrid`, `TerraformJob`, `TerraformPlannerTest`）**：重采样时将 `FARMLAND` 与未耕土层分离，已耕未播格直接生成 `SEED` 且不再纳入退路踩踏路径，无未耕土层时不再误报 `NO_SAFE_EXIT`；`Priority 1`/`Priority 2` 与 `canActFromEye` 在土块未变耕地前不抢先派发 `SEED`，并在 `Priority 2` 通过 `preservesFarmExitOnHoeFrom(op, null)` 过滤会切断剩余退路的割点耕作目标。
  3. **2×2 取水池满背包堆叠空桶识别与状态收敛（`FixedStanceOp.Use`, `TerraformJob.tickRefillWait`）**：`REFILL_BUCKET` 在生存模式下遇空桶堆叠且 `getFreeSlot() < 0` 时返回 `DENIED("INVENTORY_FULL")`，`tickRefillWait` 分别透传 `INVENTORY_FULL` / `MISSING_EMPTY_BUCKET` 而不再误归类为 `WATER_POOL_DEPLETED`，退出时兜底关闭托管换槽界面。
- 自动化验证：`.\build-local.ps1 -Tasks @('test','build','--offline','--console=plain')` 全绿（**160 个测试套件 / 799 项测试**，0 失败 / 0 错误 / 0 跳过）；`test-harness-lease-client.py` **28 项全部通过**（`0.049s`）；`coremods/companion_input.js` SHA-256 保持 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4` 不变。

## 0.8.8rc16 · 2026-11-27

- 构建标识：`rc16-0.8.8-20261127-01`（`logistics-companion-0.8.8rc16.jar`，`16,840,965` 字节，SHA-256 `008B9ED30DCBC20F8AAA0B69BCE6E8CC1FA3F4CF4CE016229A63FE9F278793BF`）
- 贡献记录：
  - Gemini：基于 `0.8.8rc15` 实机验收（`TEST 1` 9/9、`TEST 2a` 66/66 及 `TEST 2b` 16/16 均已全过），定位 `TEST 3` 在 `op 3` 取用背包石砖台阶时于 `hotbarSlot` 换槽第 2 tick 提前绕过 `prepare` 同步收口的精确根因并完成修复与单包部署。
- 核心改动：
  1. **`FixedStanceOp` 托管换槽状态优先闭环（`FixedStanceOp.Break/Place/Use`, `TerraformJob`）**：在三类微执行器每次 tick 扫描快捷栏或选工具前优先推进 `if (prepare != null)`，确保 `InventoryMove` 完整走完第 2~4 tick 服务器背包同步与 `closeManagedInventoryIfOpen()`（`closeContainer()` + `setScreen(null)`）收口后才清理 `prepare` 并允许进入下一步；并在 `TerraformJob.tickWork()` 退出活跃 `activeOp` 时兜底清理仍处于 `isManagingScreen()` 态的子操作。
- 自动化验证：`.\build-local.ps1 -Tasks @('test','build','--offline','--console=plain')` 全绿（**160 个测试套件 / 797 项测试**，0 失败 / 0 错误 / 0 跳过）；`test-harness-lease-client.py` **28 项全部通过**（`0.046s`）；`coremods/companion_input.js` SHA-256 保持 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4` 不变。

## 0.8.8rc15 · 2026-11-27

- 构建标识：`rc15-0.8.8-20261127-01`（`logistics-companion-0.8.8rc15.jar`，`16,840,380` 字节，SHA-256 `F27B42E6733A32220993EDAADC555EBD85325F0CF014D3053AC7704E65185EE6`）
- 贡献记录：
  - Gemini：基于 `0.8.8rc14` 实机扫测（`TEST 1` 与 `TEST 2b` 全过，同时在 `TEST 3` 与 `TEST 2a` 暴露出背包换槽误判开屏及游荡蜜蜂占格/拒放自愈问题），完成 `rc15` 根治修复、纯 Java 单测补充及单包部署。
- 核心改动：
  1. **托管背包换槽界面免误停（`TerraformPlanner.shouldPauseForScreen`, `TerraformJob`, `FixedStanceOp`, `TerraformExecutionPolicyTest`）**：新增 `FixedStanceOp.isManagingScreen()` 与纯 Java 策略方法 `TerraformPlanner.shouldPauseForScreen(...)`；仅当非托管的 `InventoryMove` 界面打开时才触发 `SCREEN_OPEN` 暂停，避免 `9..35` 格取料/取种时 `InventorySession` 自开 `InventoryScreen` 导致任务自掐断并遗留界面；并在 `FixedStanceOp.cancel()` 安全释放/关闭托管界面，且为创造模式补充 `9..35` 格 `handlePickItem` 支持。
  2. **非玩家活体生物占格礼让与服务端/客户端拒放快速恢复（`FixedStanceOp.Place`, `FixedStanceOp.Use`）**：放置前检查目标格非玩家、非掉落物的活体生物占用（如游荡蜜蜂）并礼让最多 `30` tick，超时则切 `ENTITY_OBSTRUCTED` 换位；将玩家防蹭盒略微外扩 `+0.08`；捕获 `useItemOn` / `useItem` 的 `InteractionResult` 以及等待 `>=10` tick 后服务器仍退回空位且未耗料的回执，返回 `NEED_STANCE` 换位重试而非卡满 5 秒触发 `PLACE_UNCONFIRMED`。
- 自动化验证：`.\build-local.ps1 -Tasks @('test','build','--offline','--console=plain')` 全绿（**160 个测试套件 / 797 项测试**，0 失败 / 0 错误 / 0 跳过）；`test-harness-lease-client.py` **28 项全部通过**（`0.046s`）；`coremods/companion_input.js` SHA-256 保持 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4` 不变。

## 0.8.8rc14 · 2026-11-27

- 构建标识：`rc14-0.8.8-20261127-01`（`logistics-companion-0.8.8rc14.jar`，`16,838,427` 字节，SHA-256 `143FD553B9B0865277963DA6C06B00C685279DEFCED8865B03B9C21DF114FA60`）
- 贡献记录：
  - Gemini：基于 `0.8.8rc13` 实机 `TEST 1` 在 `6/56` 处触发的 `stance_moves=782` 选站死循环现场，联动审计并根治 3 项执行器与取水/放置回归隐患，补充 JUnit 5 回归单测并完成 `0.8.8rc14` 构建。
- 核心改动：
  1. **站位选择复用真实视线与触达判定并阻断零进度原地循环（`TerraformJob`, `TerraformPlanner`, `TerraformExecutionPolicyTest`）**：`Priority 2` 的 `canAct` 从纯欧氏距离改为调用 `canActFromEye(op, targetBp, hypEye)` 真实射线/打击面校验，显式排除当前脚下站位（`curStand`）与已触发零进度的站位（`noProgressStances`）；到站后若 `verifiedOps == opsAtLastArrival` 则拉黑该站位并在重试超限后标记 `STANCE_NO_PROGRESS` 跳过目标，防止墙角/遮挡原地选站死循环；任意工单核实通过即清空站位黑名单以释放新视线。
  2. **2×2 取水池寻路期间保持 `REFILL_WAIT` 状态机并增加水面视线预检（`TerraformJob`, `FixedStanceOp`）**：`startRefillFromPool` 优先选取距离最近的安全干燥池边站位并直接切入 `EnginePhase.REFILL_WAIT` 追踪 `refillStandGoal` 行走与到站后接续取水，不再误留在 `WORK` 导致走到水池后立即折返；`FixedStanceOp.Use(REFILL_BUCKET)` 瞄准水面 `y+0.8` 并以 `ClipContext.Fluid.SOURCE_ONLY` 校验池边无遮挡，被挡时触发换贴边站位重试。
  3. **方块放置幂等预检（`FixedStanceOp.Place`）**：当目标格已存在期望方块且满足 `PlacementIntent`（以及可选的源水保留要求）时直接返回 `Status.VERIFIED` 并记录 `afterState`，防止断点续传或部分重复派发误报 `TARGET_OCCUPIED`。
- 自动化验证：`.\build-local.ps1 -Tasks @('test','build','--offline','--console=plain')` 全绿（**160 个测试套件 / 796 项测试**，0 失败 / 0 错误 / 0 跳过）；`test-harness-lease-client.py` **28 项全部通过**（`0.042s`）；`coremods/companion_input.js` SHA-256 保持 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4` 不变。

## 0.8.8rc13 · 2026-11-27

- 构建标识：`rc13-0.8.8-20261127-01`（`logistics-companion-0.8.8rc13.jar`，`16,836,627` 字节，SHA-256 `E37A9992FDD73054008B23C7D39FB702A4193F1828997A4F4B98865BC1030EC9`）
- 贡献记录：
  - User：提出将平地、三维填充与大规模耕地内化为统一算法与技能预设，并明确涵盖服务器 `/fly` / 模组飞行与低心智负担扁平参数需求。
  - Astra (`gpt-6.2-astra`)、Claude Opus（2 轮深度架构收敛） & Gemini：协同设计统一三维整地/筑造/开垦架构及护栏协议；由 Gemini 完成全量 Java 实现、JUnit 5 单测覆盖与 `0.8.8rc13` 构建部署。
- 核心改动：
  1. **统一三维地形整改系统（`TerraformSpec`, `TerraformPlanner`, `TerraformJob`, `FixedStanceOp`）**：提供扁平参数 Schema（`min_x..max_z`、`mode`、共享 `block` 与可选 `cover_slab`），支持 `flatten`（`cut_only`/`cut_and_fill`，严格自顶向下挖掘 + `[FALLING_COLUMN_ABOVE]` 柱级保护 + 自然方块白名单与箱子保护）、`fill`（`solid`/`hollow`/`walls`/`replace_air` 严格自底向上填充）及 `farm_grid`（9×9 分段水格网格 + 上半砖封盖 + `pool_2x2`/`inventory` 取水 + 退后叶序单轮开垦即播）。
  2. **定站微执行与无践踏寻路护栏（`FixedStanceOp`, `TerraformJob`, `Navigator`, `MiningJob`）**：每次至多 1 个在途 `FixedStanceOp`（`Break`/`Place`/`Use`），优先耗尽当前站位可达工单；拆分 `MiningJob` 的 `BULK` 与 `EXPLICIT_TARGET` 挖掘授权；为 `Navigator.startKnownWalk` 增加 `allowedEdge` 边约束防踩踏新耕地；接入 `RollbackJournal` 500 条环形日志与诚实 `rollback_coverage` 留存统计。
  3. **飞行无差别降落守卫与断点续接（`TerraformJob`, `Actions`, `Cooperation`）**：按 `abilities.flying` 状态无差别触发 `LAND` 潜行安全降落，适配创造模式与服务器 `/fly`/模组飞行环境；外部干预触发 `MANUAL_FLIGHT_INTERRUPTED` 暂停锁并落盘 `Store.data.checkpoints`。
  4. **工具注册与技能预设（`ToolSchemas`, `Rules`, `SkillCatalog`, `CapabilityCatalog`, `TaskActions`, `SessionPlan`, `ToolMenu`, `HarnessCapabilities`, `HarnessController`, `Agent`）**：新增 `terraform_preview`、`terraform_run`、`terraform_control` 工具及 `SkillCatalog`（`VERSION = "10"`）三项预设（`terraform_flatten`、`terraform_fill`、`terraform_farm_grid`）。
- 自动化验证：`.\build-local.ps1 -Tasks @('test','build','--offline','--console=plain')` 全绿（**160 个测试套件 / 794 项测试**，0 失败 / 0 错误 / 0 跳过）；`test-harness-lease-client.py` **28 项全部通过**（`0.045s`）；`coremods/companion_input.js` SHA-256 保持 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4` 不变。已完成 `rc12` 备份至 `mods_backup\` 并部署 `logistics-companion-0.8.8rc13.jar` 至实机 `mods\` 目录，待重启客户端执行 `rc13` 实机联调后转正 `0.8.8`。

## 0.8.8rc12 · 2026-11-26

- 构建标识：`rc12-0.8.8-20261126-01`（`logistics-companion-0.8.8rc12.jar`，`16,721,955` 字节，SHA-256 `EDE8F14454C5AB75871DF04789FED5D2AF7F76D74C2DFC5A96EB6F19783A207B`）
- 贡献记录：
  - Gemini：基于 `0.8.8rc11` 实机 `QA-2` 在脚手架顶部原地上下跳动及饥饿掉血现场（`(87.5, -58.08 <-> -57.92, 119.7)`）定位 5 项组合根因并完成修复、JUnit 5 单测补充与离线校验。
- 核心改动：
  1. **脚手架顶部禁用悬崖潜行下沉循环（`MovementPolicy`, `Navigator`, `MovementAndPowerSaverTest`）**：新增 `MovementPolicy.shouldLedgeSneak`；处于脚手架支撑上时禁用 `highLedge` 自动潜行（保持非冲刺与转角保护），彻底消除原版潜行穿落脚手架到 `-58.08` 触发 `climbExitJump` 拉回 `-57.92` 的往复死循环。
  2. **非脚手架限定攀爬防滑潜行（`ClimbRules`, `Navigator`, `ClimbRulesTest`）**：扩展 `ClimbRules.shouldSneakToStabilizeClimb(onClimbable, onGround, isScaffolding)`（保留 2 参重载兼容）并更新 `Navigator` 全部 4 处调用；仅梯子/活板门在停稳/搜索/重规划时潜行防滑，脚手架不再触发潜行下穿。
  3. **横向步进拒把垂直上下抖动当有效位移（`MovementPolicy`, `Navigator`, `MovementAndPowerSaverTest`）**：新增 `MovementPolicy.madeHorizontalWalkProgress`；非垂直爬梯而需跨越 X/Z 格边界时仅计水平位移 `>= 0.015`，防止 0.16 格上下抖动每 tick 将 `stuck` 归零、拖满 120s 超时并耗尽饱食度。
  4. **交互与拾取状态不误拒脚手架悬梯到站（`Navigator`）**：`safelyStanding` 仅对非交互/非拾取的普通寻路启用 `ClimbRules.canAcceptClimbableArrival` 实心落地限制，`PlaceJob`/`MiningJob`/`CollectionJob` 允许直接站在脚手架上完成放置或挖掘。
  5. **蓝图施工生存低生命/饥饿扣血自动断点暂停（`SurvivalPolicy`, `BlueprintBuildJob`, `MovementAndPowerSaverTest`）**：新增 `SurvivalPolicy.shouldPauseBuildWork`；在生存模式生命跌至危急线或饱食度 `<= 0` 且处于掉血态时自动中止子任务并保存 `paused` 断点等待补给。
- 自动化验证：`.\build-local.ps1 -Tasks @('test','build','--offline','--console=plain')` 全绿（**158 个测试套件 / 784 项测试**，0 失败 / 0 错误 / 0 跳过）；`test-harness-lease-client.py` **28 项全部通过**（`0.048s`）；`coremods/companion_input.js` SHA-256 保持 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4` 不变。

## 0.8.8rc11 · 2026-11-26

- 构建标识：`rc11-0.8.8-20261126-01`（`logistics-companion-0.8.8rc11.jar`，`16,720,641` 字节，SHA-256 `F01197C510EEC60A9FD3EA3CEDE8C4106D0914C10FD6A54D43976380B8DB77D8`）
- 贡献记录：
  - Gemini：基于 `0.8.8rc10` 全套实机 `QA-3a / P0-1 / P0-3` 实测反馈与用户关于“拆架掉落物未主动回收”的发现，完成梯井非精确到达防早停滑落、与蓝图/独立脚手架拆架后自动掉落物清理，及对应 JUnit 5 单测与离线构建校验。
- 核心改动：
  1. **非精确 `go_to` 悬梯到达防早停滑落（`ClimbRules`, `Navigator`, `ClimbRulesTest`）**：新增 `ClimbRules.canAcceptClimbableArrival` 并在 `Navigator` 到达判断接入；当目标格本身非可攀爬实块时，处于梯子/活板门上且未真正踏实地面（`!onGround`）不允许提前判定到达松手，确保顺畅走上实心楼板后再结束导航。
  2. **蓝图与独立脚手架拆架后自动收集掉落物（`CollectionJob`, `TeardownReachPlanner`, `BlueprintBuildJob`, `ScaffoldJob`, `TeardownReachPlannerTest`）**：为 `CollectionJob` 新增可配置 `maxTicks` 预算控制；`BlueprintBuildJob` 与 `ScaffoldJob` 在生存/冒险模式完成 LIFO 拆架且存在已拆材料类型时，通过 `TeardownReachPlanner.shouldCollectTeardownDrops` 扫描周边未拾取的已拆方块 `ItemEntity`，以托管后台 `CollectionJob`（不闪烁背包、超时安全跳过、计入 `scaffolds_collected`）自动巡回收集散落的竹脚手架/圆石。
- 自动化验证：`.\build-local.ps1 -Tasks @('test','build','--offline','--console=plain')` 全绿（**158 个测试套件 / 783 项测试**，0 失败 / 0 错误 / 0 跳过）；`test-harness-lease-client.py` **28 项全部通过**（`0.044s`）；`coremods/companion_input.js` SHA-256 保持 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4` 不变。已完成 `rc10` 备份至 `mods_backup\` 并部署 `logistics-companion-0.8.8rc11.jar` 至实机 `mods\` 目录。

## 0.8.8rc10 · 2026-11-26

- 构建标识：`rc10-0.8.8-20261126-01`（`logistics-companion-0.8.8rc10.jar`，`16,716,978` 字节，SHA-256 `026230C36180C7D5C488790CC15DAC8F50F296FCECE4F9AF212BFFF3BCF51BB1`）
- 贡献记录：
  - Astra（`gpt-6.2-astra`，Codex CLI 只读评审）+ Claude Opus（`claude -p --model opus` 只读评审）+ Gemini：完成 `rc9/rc10` 改动审计与四大域预设场景风险复核，并核验确认 4 项 P0 硬边界问题。
  - Gemini：完成 `QA-3a` 梯井横向居中与滞回修复、4 项方案 A P0 边界修补及对应 JUnit 5 单测、离线全量验证与 `rc9 -> rc10` 备份部署。
- 核心改动：
  1. **`QA-3a` 梯井横向居中与滞回防抖（`ClimbRules`, `Navigator`, `ClimbRulesTest`）**：在 `0.8.8rc9` 实机确认活板门打开（`open=true`）后角色在 `z=60.26` 挂肩卡于邻格 `y=-55` 天花板底面；`guideClimb` 在 `onClimbable && goingUp` 引入 `ClimbHysteresis`（上爬偏心 `> 0.13` 进入、`<= 0.08` 退出），合成为贴墙 + 居中斜向航向角，下降居中滞回 `> 0.20 / <= 0.14`；`Navigator` 持有并在 `begin/stop/replan/非垂直爬梯` 时重置滞回状态，同时统一要求 `steering.forward()` 转向对准后再推进入梯方向。
  2. **P0-1 · 直线跳点不得跳过跑酷落点（`MovementPolicy`, `Navigator`, `MovementAndPowerSaverTest`）**：新增 `MovementPolicy.canSkipCollinearWaypoint`，`Navigator` 平滑跳点循环检查 `pathActions[index]` 与 `pathActions[index + 1]`，遇跑酷边（`action == 3`）或其他非普通平走边立即停跳，避免短跳落点后紧接直道时直接吞掉起跳。
  3. **P0-2 · 高空拆架步离硬排除架体与到站二次核验（`ScaffoldJob`, `TeardownReachPlanner`, `BlueprintBuildJob`, `TeardownReachPlannerTest`）**：`ScaffoldJob.findSolidStepOffCell` 直接跳过可攀爬格、流体、无有限承托面、脚手架及账本支撑格（不再仅加 `+1000` 惩罚分）；`BlueprintBuildJob` 步离导航完成准备开挖前通过 `TeardownReachPlanner.stepOffVerified` 复核已着地、不在脚手架、未踩在待拆支撑柱且脱离邻接限制，否则保栈停拆。
  4. **P0-3 · 显式 `mention_id` 失效/已结案闭环拒绝（`MentionPolicy`, `HarnessController`, `MentionPolicyAndInboxTest`）**：新增 `MentionPolicy.evaluateActionMention`；`HarnessController` 收到非空 `mention_id` 时若会话已过期、被挤出、非当前作用域或已无工作任务地结案，直接返回 `[MENTION_EXPIRED]` 拒绝回执，绝不静默降级为普通租约动作或丢失访客只读沙盒。
- 自动化验证：`.\build-local.ps1 -Tasks @('test','build','--offline','--console=plain')` 全绿（**158 个测试套件 / 783 项测试**，0 失败 / 0 错误 / 0 跳过）；`test-harness-lease-client.py` **28 项全部通过**（`0.040s`）；`coremods/companion_input.js` SHA-256 保持 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4` 不变。已完成 `rc9` 备份至 `mods_backup\` 并部署 `logistics-companion-0.8.8rc10.jar` 至实机 `mods\` 目录。

## 0.8.8rc9 · 2026-11-26

- 构建标识：`rc9-0.8.8-20261126-01`（`logistics-companion-0.8.8rc9.jar`，`16,712,440` 字节，SHA-256 `69BC2BFE4124A1433914F8F0FF21A406622712940FC76DB437478F4647338E37`）
- 贡献记录：
  - Gemini：基于 `0.8.8rc8` 实机 QA 与打转现场定位修复 `Navigator` 跑酷 180° 助跑死循环、脚手架顶部原版 `CollisionContext` 误阻断 3 格孤岛及悬空脚手架 LIFO 拆架卡死（含 `ScaffoldJob.findSolidStepOffCell` 高度域与脚手架本体排斥加固）、以及 `QA-3a` 梯井闭门木活板门稳定/寻路边界。
- 核心改动：
  1. **跑酷 180° 打转根治（`MovementPolicy`, `Navigator`）**：新增 `parkourExecutionAllowed` 与 `parkourTimedOut`，非规划跑酷边遇横向偏离直接重规划而不再误入跑酷状态机；修正 `parkourPhase == 1` 先前向转角再后向转角导致的同 tick 180° 对扯，`ALIGN <-> SPRINT` 循环限 2 次 / 60 tick 超时即封边重规划；在 `createSearch` 与 `addParkourSteps` 中禁止从脚手架上起跳或落到脚手架/梯子格，且 `replan()` 支持直接拉黑多格跑酷失败边。
  2. **脚手架 3 格孤岛与悬空拆架阻塞修复（`ClimbRules`, `NavigationPassage`, `BlueprintBuildJob`, `ScaffoldJob`）**：`ClimbRules.isScaffoldingCollisionPassable` 接入 `NavigationPassage.clear`，在 `allowClimb` 开启时跳过 `ScaffoldingBlock` 碰撞判定，消除站在脚手架顶部 `CollisionContext.of(player)` 将下方脚手架误判为实心堵路（仅展开 `3/20000`）导致的建筑末尾 `NOT_REACHABLE` 与零拆架；`BlueprintBuildJob.tickTeardown` 在 `climbExit` 返回 `BLOCKED` 时自动调用 `ScaffoldJob.findSolidStepOffCell` 先步离至安全实体落脚格后再继续 LIFO 拆架；同时将 `findSolidStepOffCell` 垂直搜索域扩展至覆盖 `groundY`（`[-8..-2, 1]`）并排除脚手架所在格与脚手架顶面。
  3. **`QA-3a` 闭门木活板门与梯井防坠收敛（`Navigator`）**：在 `search != null` 与 `replan()` 期间若处于梯子上未落地则保持潜行稳住位置（不再 `release()` 跌回底层）；上升穿越 `verticalClimb` 门廊时将 `climbCorridor` 向上扩展 `0.25` 格提前触发 `passage.prepare` 开门；水平切换格采用 `stand(next, !strictWalk)` 与 `passage.prepare` 保持规划态一致。
- 自动化验证：`.\build-local.ps1 -Tasks @('test','build','--offline','--console=plain')` 全绿（**158 个测试套件 / 783 项测试**，0 失败 / 0 错误 / 0 跳过）；`test-harness-lease-client.py` **28 项全部通过**（`0.044s`）；`coremods/companion_input.js` SHA-256 保持 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4` 不变。已完成 `rc8` 备份至 `mods_backup\` 并部署 `logistics-companion-0.8.8rc9.jar` 至实机 `mods\` 目录。

## 0.8.8rc8 · 2026-11-26

- 构建标识：`rc8-0.8.8-20261126-01`（`logistics-companion-0.8.8rc8.jar`，`16,711,333` 字节 / 约 `15.94 MiB`，SHA-256 `B18EEE0CD2F999A69EA91331130A11D78A2D4DC079C5BD88FDF54AFD6B80A3A2`）
- 贡献记录：
  - Gemini：基于 `0.8.8rc7` 实机 QA 报告定位并修复 `QA-3a`（闭门活板门自动开门攀爬）、`QA-2`（潜行压低视线误判 `PLACE_CHANGED`、屋檐站位眼高不足、固体坡道栈顶阻断 LIFO 拆架）全部 4 项问题并补充 JUnit 5 单测。
- 核心改动：
  1. **`QA-3a` 梯井闭门木活板门自动开门上/下行（`ClimbRules`, `Navigator`, `NavigationPassage`）**：`Navigator.begin()` 与 `collectFallbackGoals()` 在非严格行走时采用规划态站位与高度判定；等待自动开门时不释放移动改按上跳顶门（不潜行），到站悬梯防滑按潜行；禁止关闭活板门向正上方空气攀爬以引导水平走上二楼，且防止梯井单次滑脱封死唯一竖井边；`NavigationPassage.prepare` 补充 `Reach.mineFrom` 仰视仰面命中兜底。
  2. **`QA-2` `[PLACE_CHANGED]` 潜行/重接近收敛（`PlaceJob`）**：仅在放置需要潜行的方块或依赖可交互支撑面时按住潜行，避免普通墙体/楼梯施工无谓潜行压低 0.35 格视线遮挡；并在 `phase 15` 支撑面丢失时先尝试站直恢复及至多 2 次重寻位。
  3. **`QA-2` 屋檐高差搭架站位校正（`BlueprintScaffoldPlanner`, `BlueprintBuildJob`）**：`planCluster` 与 `planReachesStep` 要求屋檐斜向支撑施工站位眼高严格高于目标底部，消除 1 格偏低脚手架导致屋檐角不可达的问题。
  4. **`QA-2` 固体坡道 LIFO 自动离位拆架（`TeardownReachPlanner`, `BlueprintBuildJob`, `ScaffoldJob`）**：拆除账本栈顶固体脚手架前若人物站在或重叠该格，自动先步离至邻接安全落地格再继续 LIFO 拆除。
- 自动化验证：`.\build-local.ps1 -Tasks @('test','build','--offline','--console=plain')` 全绿（**158 个测试套件 / 783 项测试**，0 失败 / 0 错误 / 0 跳过）；`test-harness-lease-client.py` **28 项全部通过**（`0.047s`）；`coremods/companion_input.js` SHA-256 保持 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4` 不变。

## 0.8.8rc7 · 2026-11-26

- 构建标识：`rc7-0.8.8-20261126-01`（`logistics-companion-0.8.8rc7.jar`，`16,707,645` 字节 / 约 `15.93 MiB`，SHA-256 `F5CF37216180AA0A3F58B21FC7EFFAF85564780945E1C50EC40EDC6F66CF3185`）
- 贡献记录：
  - Claude Opus（`claude --model opus`）+ Astra（`gpt-6.2-astra`，Codex CLI）+ Gemini：完成多人 `@` 触发触发机制、异步长轮询、双模式安全公屏回复与 `GUEST` 只读硬沙箱方案的只读联合架构评审并对齐落地方案。
  - Gemini：实现两阶段 `@` 收件箱、`wait_events` 非阻塞长轮询、HARNESS 任务结案接口、API/HARNESS 双模式自然应答、独立 `GUEST_IO` 隔离通道及 `HarnessController.currentScope()` 作用域统一，补充 2 套离线单测、完成构建及 `rc6` 备份 / `rc7` 部署。
- 核心改动：
  1. **符号 `@` 识别、UUID 身份分级与 `GUEST` 硬沙箱**：新增 [MentionPolicy.java](file:///D:/Companion%20mod/companion-mod/src/main/java/dev/mibao/companion/MentionPolicy.java) 与 [PlayerMentionInbox.java](file:///D:/Companion%20mod/companion-mod/src/main/java/dev/mibao/companion/PlayerMentionInbox.java)，支持 `@配置名` / `@游戏账号ID` 与全角 `＠`，按真实 UUID 严格区分 `OWNER / ADMIN / MEMBER / GUEST`（防同名冒充与撤销成员）；授权成员句首硬指令（含新增 `duty` 值守口令）秒级直行，其余请求走两阶段 `player_mention` 对话收件箱，避免空闲聊天占死 `CommunityBoard`。新增默认关闭配置 `allowGuestChat`（`Store` / `PermissionView` / `WorkbenchSettingsViews` / `SettingsCatalog`），开启后未授权访客仅走只读安全聊天，`GUEST_READONLY_TOOLS` 严禁一切世界写入/持久化/执行命令副作用。
  2. **非阻塞 `wait_events` 长轮询与 HARNESS 队列闭环**：新增 [HarnessEventHub.java](file:///D:/Companion%20mod/companion-mod/src/main/java/dev/mibao/companion/HarnessEventHub.java)，升级 [HarnessHttp.java](file:///D:/Companion%20mod/companion-mod/src/main/java/dev/mibao/companion/HarnessHttp.java) 异步写回通道，使 `wait_events`（支持 `kinds=["player_mention"]`、最长 20 秒、上限 4 路并发）挂起时不占用主请求线程池与游戏主线程；新增 `mentions`、`reply_to_mention`、`accept_mention_work`、`complete_mention_task` MCP 工具，彻底解决 HARNESS 模式下 `CommunityBoard` `ACTIVE` 永不结案的卡死问题；统一 `HarnessController.currentScope()`（`world|dimension`）保证收件箱/事件/工作接收作用域严格一致。
  3. **双模式自然应答与公屏防注入**：升级 `ChatOutbox.offerSafe` 与 `Messages.mentionReply`（硬拦截 `/` 开头指令、`BRIEF` 模式允许每单 1 次 `ACK` 与 `FINAL`、`publicReply` 关闭时立即停发），修复原 `reply` 工具误泄公屏问题；`Agent` 在 API 模式下支持调用工具前先发自然开场应答（`ACK`）、跳过多人入口的 `local()` 快捷口令，并为 `GUEST` 提供隔离 `GUEST_IO` 零工具闲聊通道（零历史/记忆/录制持久化）。
  4. **新增离线 JUnit 5 测试**：`MentionPolicyAndInboxTest`（4 用例）与 `HarnessEventHubAndHttpTest`（2 用例，含真实本地回环 `HarnessHttp` 挂满 4 路 `wait_events` 时 `status`/`heartbeat` 无阻塞并发实测）。
- 自动化验证：`.\build-local.ps1 -Tasks @('build','--offline','--console=plain')` 全绿（**158 个测试套件 / 783 项测试**，0 失败 / 0 错误 / 0 跳过）；`test-harness-lease-client.py` **28 项全部通过**（`0.042s`）；`coremods/companion_input.js` SHA-256 保持 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4` 不变。已完成 `rc6` 备份至 `mods_backup\` 并部署 `logistics-companion-0.8.8rc7.jar` 至实机 `mods\` 目录。

## 0.8.8rc6 · 2026-11-26

- 构建标识：`rc6-0.8.8-20261126-01`（`logistics-companion-0.8.8rc6.jar`，`16,671,511` 字节 / 约 `15.90 MiB`，SHA-256 `AA44E349D1AA5A1B2F0EEEA9E5622E95192ADFD6F6B46348870C0B0C65C23048`）
- 贡献记录：
  - Claude Opus 5.5（`claude-opus-5-5-high`，Antigravity CLI `agy`）+ Astra（`gpt-6.2-astra`，Codex CLI）+ Gemini：在动工前完成两轮三方只读架构/根因/边界研讨，定位并对齐 `0.8.8rc5` 两轮实机 QA 暴露的 P0–P2 全量修复方案。
  - Gemini：落地全部 6 个模块修复、4 项收尾边界修补与 5 个离线 JUnit 5 测试套件，完成离线构建验证、`rc5` 备份及 `rc6` 实机部署。
- 核心改动：
  1. **P0 · `mine_expedition` 干燥矿点批量漏采修复**：`GeneratorPolicy.FluidVerdict` 与 `verdict(...)` 将 `PLAYER_UNSAFE -> ABOVE_HAZARD -> HORIZONTAL_FIRE -> !hasHorizontalFluid -> !targeted(BULK_FLUID)` 分层判定；`MiningJob` 显式拆分横向火焰 `hasHorizontalFire`、计数 `rejectedSafety`/`denials`/`terminalRejects`，并区分 `issued`/`PLAYER_UNSAFE` 停机与局部拒绝 `skip()`；`MiningExpedition` 仅拉黑终局拒绝点，避免连坐未尝试矿块；同步更新 `AutoActionJob`。
  2. **P0/P1 · 梯子/活板门通行与防漂移（`ClimbRules`, `Navigator`, `ExplorationMemory`）**：`Navigator.isClimbable(pos, planning)` 接入 Forge `IForgeBlock#makesOpenTrapdoorAboveClimbable` 并扩展 `foot(cell, planning)` / `traversable`；禁止直落进入关闭活板门（`ClimbRules.canDescendVerticallyInto`）；使用 `ClimbRules.guideClimb` 与定量 `madeProgress` 杜绝离梯侧漂和跳跳卡死；修复 `Navigator.createSearch` 中 `[LADDER_TOP_GAP]` 不可达悬空判断，仅在图搜索无解时提示梯顶纯空气断档。
  3. **P1 · 脚手架退出、超距拆解与半径越界修复**：`TeardownReachPlanner` 分级规划 `<=18` / `18..96`（`APPROACH_THEN_MINE`）/ `>96`（`TOO_FAR`）与 `evaluateClimbExit`/`descentBudgetTicks`；`ScaffoldJob` 与 `BlueprintBuildJob` 修正站在 `ScaffoldingBlock` 顶面时的退出判定并接入 `Navigator().interactionDy(-6, 3).allowScaffoldingColumnMine(true).maxRadius(5)` + 完整 `teardownApproachNav` 生命周期与 `stopChild()` 清理；`Reach.mine` 支持多面体描边射线，允许 `scaffoldTeardown(true)` 拆除头顶 `ScaffoldingBlock`。
  4. **P1/P2 · 蓝图朝向扇区放置与脚手架扇区校验**：`FaceHitMath` / `AimGate` / `PlacementIntent` 补充角度收敛、20 tick 超时与命中点朝向扇区校验；`PlaceJob` 将 `phase 1 -> 15` 停稳、`>0.3` 漂移重寻路、`nav.placementIntent` 与 `resolvePlacementHit()` 命中扇区约束、预测放置与即时 `[PLACED_WRONG_STATE]` 收敛到位；`BlueprintScaffoldPlanner.planCluster` 保留 `primaryTarget` 并执行朝向扇区过滤，`BlueprintBuildJob` 延后 `elevationPlannedBands` 锁定并排除 `[PLACED_WRONG_STATE]` 瞬时重试。
  5. **P2 · `task_cancel` 路由与 `release` 保留 F8 接管**：新增 `TaskCancelPolicy` 与 `TaskGateway.exists`，`HarnessSession` 新增前台运行态 `inflight` 绑定，`Agent` 按 `GATEWAY_CANCEL > CANCEL_INFLIGHT_REQUEST > 类型别名 > active > NOT_FOUND` 路由；`HarnessController.release` 改走 `Agent.harnessDisconnected` 保留 F8 接管开关。
  6. **新增离线 JUnit 5 测试套件**：`GeneratorPolicyVerdictTest`（3 用例）、`TeardownReachPlannerTest`（2 用例）、`TaskCancelPolicyTest`（2 用例）、`ClimbRulesTest`（2 用例）、`PlacementAndBlueprintSectorTest`（2 用例）。
- 自动化验证：`.\build-local.ps1 -Tasks @('build','--offline','--console=plain')` 全绿（**156 个测试套件 / 777 项测试**，0 失败 / 0 错误 / 0 跳过）；`test-harness-lease-client.py` **28 项全部通过**（`0.042s`）；`coremods/companion_input.js` SHA-256 保持 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4` 不变。已完成 `rc5` 备份至 `mods_backup\` 并部署 `logistics-companion-0.8.8rc6.jar` 至实机 `mods\` 目录。

## 0.8.8rc5 · 2026-11-26

- 构建标识：`rc5-0.8.8-20261126-01`（`logistics-companion-0.8.8rc5.jar`，`16,645,159` 字节 / 约 `15.87 MiB`，SHA-256 `B538C2C67CC8F6D39CAB26990C67B8CB828D02259FE40AF3195956390D7ECC7C`）
- 贡献记录：
  - DeepSeek + Neon：完成 `0.8.8rc4` 全套实机 QA 验收并锁定 B2–B7（脚手架多格叠加失效、屋顶悬空漏层、拆架死锁、abort 清理、`task_cancel` 失效、护箱回执）等阻塞项。
  - Astra（`gpt-6.2-astra`）：提供 3D 立体寻路/探索图/脚手架 0-1 BFS 与弓箭/合成/采矿优化方案设计（`ASTRA-OPT-A-NAV-3D.md` / `ASTRA-OPT-B-CRAFT-MINE-BOW.md`），并在 RC5 发布前完成全量只读走查，拦截并协助闭环 `P0-1`（8 槽多木板组合爆炸）及 `P1-1..P1-11` 多项实战与接线隐患。
  - Gemini：完成 B2–B7 闭环修复、全部 6 大算法核心升级及业务任务全量接线（Wave 1 + Wave 2）、联合审阅 P0/P1/P2 修复回归、全量构建、备份部署与文档更新。
- 核心改动：
  1. **修复 `0.8.8rc4` B2–B7 实机阻塞项**：修正 `MiningJob` 非托管守卫、`HarnessController`/`Agent` Busy 下的 `scaffold_build(abort)` 与 `task_cancel` 优先级、`PlaceJob` 脚手架多格爬升垫脚与潜行冲突释放、`ScaffoldJob` 与 `BlueprintBuildJob` 严格 LIFO 逆序拆架、内伸栈道 `planHollowPerimeterRamp` 及断点恢复一致性。
  2. **算法 Wave 1（弹道 / 合成 / 采矿与平台）**：`BowAim` 按 Forge 1.20.1 `0.99F` 阻力与 `0.05F` 重力精确求解高低双弧及移动提前量，接入 `RangedHuntJob` 并分离安全取消蓄力（切槽撤弓绝不盲射）；`CraftAllocation` 引入最大流可行性剪枝；`RecipePlanner` 新增逆向闭包前向状态 Dijkstra、`Use` 余料跟踪、多木板槽位同构多重集分组预算防卡死与 MRV + 失败记忆，并缓存至 `ProductionTask`；新增 `MiningSearch` 3D 聚类/有向 2-opt/无支配约束重连并接入 `MiningJob`；`PlatformPlan` 与 `PlatformJob` 切换单次 BFS 支撑优先调度。
  3. **算法 Wave 2（零 GC 寻路 / 立体探索图 / 脚手架 0-1 BFS）**：`GridSearch` 新增开放寻址代际哈希 + 索引最小堆 `weightedPacked`（含重开与工作区独占隔离），接入 `Navigator` 并消费动作字节修复跑酷高悬崖契约冲突，新增弱引用 `TickProbeCache`、5 点 `surfaceY` 探测、梯子中段 `foot(goal).y` 精确到达与 `verticalClimb` 前置 `passage.prepare` 开门；`ExplorationGraph`/`Store`/`Memory`/`RoamPolicy`/`RoamExplorationJob`/`ExplorationJob` 全链路支持梯子/脚手架竖井、攀爬逐格采样、前沿不丢弃、竖井压段、`(cell, incomingMode)` 成本、schema 1/2 与坏节点/坏边隔离；`BlueprintScaffoldPlanner` 与 `BlueprintBuildJob` 落地 Forge 1.20.1 脚手架 0-1 BFS 校验、`planCluster` 内部地面生根中继柱与共享平台规划，并在每步搭架前实时核对背包余料绝不挪用主体蓝图材料。
- 自动化验证：`.\build-local.ps1 -Tasks @('build','--offline','--console=plain')` 全绿（**151 个测试套件 / 766 项测试**，0 失败 / 0 错误 / 0 跳过）；`test-harness-lease-client.py` **28 项全部通过**；`coremods/companion_input.js` SHA-256 保持 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4` 不变。已完成 `rc4` 备份与 `rc5` 实机目录部署。

## 0.8.8rc4 · 2026-11-25

- 构建标识：`rc4-0.8.8-20261125-01`（`logistics-companion-0.8.8rc4.jar`，16,527,153 字节 / 约 15.76 MiB，SHA-256 `CCFF6F73FAB38C3C2C9FFA0934E91D31A55979693568D0AF0A6CC3A39097C778`）
- 贡献记录：
  - 项目维护者：在屋顶层实机卡点后拍板「我全都要」产品方案（自动 3D 脚手架 + 独立 `scaffold_build` 原语工具箱 + 自动 LIFO 拆架与手动留存开关）。
  - Carbon（High）：代码走查定位 `Navigator` `interaction` 扫描 `dy = -2..3` 无法够到 3 格高屋顶、`SoundReflex` 高空受击撤退缺后向安全角、`BlueprintBuildJob` 实心阶梯外挂环路与 LIFO 退步拆架核心架构。
  - Neon（Extra High）：细化并落地高空窄道潜行防坠（`exposedLedge`/`highLedge`）、脚手架方块垂直升降、每上升 1 格配 2 格踏面的外圈空心阶梯（`planHollowPerimeterRamp`）、45° 站位防偏转（`PlacementIntent.stanceInDirectionSector`）、上/下半砖精确点击（`Reach.placementFrom` + 三波次 `BlueprintPlanner`）、独立 `scaffold_build` 状态机与账本持久化、测试与部署。
- 核心改动：
  1. 解决屋顶高层 `Y=3` 卡点与半砖/楼梯朝向翻转：`Navigator` 交互扫描扩至 `dy = -4..3`，`Reach.placementFrom` 区分 `top`/`bottom` 点击上下半区，`PlacementIntent.stanceInDirectionSector` 保持 `0.2` 对角余量，`PlaceJob` 在 `aim.ready` 前稳定 `sneakHold` 眼高；`BlueprintPlanner` 升级为底面支撑 -> 侧面/上半砖/悬挑 -> 附属装饰 3 波次。
  2. 多模式 3D 脚手架与高空防坠（`Navigator`, `SoundReflex`, `BlueprintScaffoldPlanner`, `BlueprintBuildJob`）：支持 `auto`/`scaffolding`/`solid_ramp`/`pillar`/`none`；无足够真脚手架时优先用非蓝图盈余实体方块搭建外圈空心安全阶梯或临时侧支点；支持断点存档 `scaffold_stack`、LIFO 逆序拆架（绝不拆蓝图实体方块）、`keep_scaffold` 与 `blueprint_status(action="cleanup_scaffold")` 及工作台清理按钮；高空窄道禁用跑酷与疾跑并全程潜行锁边，`SoundReflex` 支持 `±145°`/`180°` 沿窄道后撤或悬崖原地潜行保命。
  3. 独立 `scaffold_build` PRO 原语工具（`ScaffoldJob`, `Store`, `ToolMenu`, `Agent`, `HarnessController`）：仅挂载于 `construction` 档案，支持 `status`/`pillar_up`（服务端确认跳垫）/ `ramp`/`scaffolding_tower`/`temp_support`/`teardown`。
- 自动化验证：`.\build-local.ps1 -Tasks @('build','--offline','--console=plain')` 全绿（148 个测试套件 / 741 个用例，0 失败 / 0 错误 / 0 跳过）；`test-harness-lease-client.py` 28 项全通过；`companion_input.js` SHA-256 保持 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4` 不变；待 Codex 实机验收（含 `sample_porch_5x4x5`、`Y=3` 屋顶、3D 脚手架与 LIFO 拆架）。

## 0.8.8rc3 · 2026-11-25

**候选更新版（围绕 Codex 首轮 `0.8.8rc2` 实机验收交接修复两条 P1 主线、P2 拒绝码与工作台 UI 文字裁剪）：根治 `StorageDrawers` 5 盒内凹形状命中边框导致左键取不出及右键变邻格放置、接通 `CountUpdateMessage` 客户端计数同步检测并彻底分离目标格与邻格证据（误放置返还 `[PLACED_ADJACENT_NOT_INSERTED]`）、托管 `BlueprintBuildJob` 子 `PlaceJob` 彻底打通施工中查询/暂停/中止与 `BUSY` 精细门禁、补齐结构化失败坐标/支撑优先拓扑/属性状态核验/SHA-256 断点防篡改与 `max_blocks` 分批施工，并修复 `AutoActionJob` 护箱拒绝前缀与左下亮/暗按钮单字裁剪问题。** 构建版本为 `0.8.8rc3` / `rc3-0.8.8-20261125-01`，产物为 `logistics-companion-0.8.8rc3.jar`（`16,489,190 B`，SHA-256 `DE672D2D33885D551EAC1BF8651426789A51CCD3F2952E986CB78653BD6E9229`）。

| 贡献 | 完成方 | 变更 |
| --- | --- | --- |
| `0.8.8rc2` 全链路实机 QA 与根因级交接报告 | Codex (Computer Use + Harness) | 在独立 1.20.1 QA 存档完成 `T0`–`T8` 实机核验（包含 `mcschematic.top` 6,543 格 `.litematic` 导入预演与小塔续建 `29/29`），精准捕获抽屉取不出/右键放墙前、施工中 `pause`/`status` 被 `BUSY` 拦截、`auto_action` 护箱回执前缀为「成功」及左下主题按钮空白等 4 项问题并产出 `NEON-HANDOFF-0.8.8rc2-20261125.md`。 |
| P1-1 单方块精细交互：多盒内凹命中、`CountUpdateMessage` 感知与目标/邻格防混淆 | Gemini | 通过反编译 `StorageDrawers 12.15.1` 锁定 5 盒内凹正面边框（`0.0625`）与 `CountUpdateMessage` 客户端同步根因：重写 `FaceHitMath.resolveFaceTarget`/`projectToUV`/`matchesUV` 与 `Reach.faceAt`/`faceFrom` 投射至内凹面板并校验实际命中 UV（拒绝边框误触）；修改 `TargetedInteractionJob`/`ItemOnBlockOperation` 在瞄准前稳住潜行眼高、水平速度与服务端转角同步；升级 `MachineObservation.changed` 支持抽屉自定义同步包；严格分离 `InteractionOutcome`/`InteractionReceipt` 的目标格与邻格变更，非潜行向容器/子槽放块失败退化为邻格放置时报错 `[PLACED_ADJACENT_NOT_INSERTED]`。 |
| P1-2 蓝图复刻：施工中控制、失败定位、状态核验与断点隔离 | Gemini | 改造 `Actions.Job.managed` 由 `BlueprintBuildJob` 托管子 `PlaceJob`（不再覆盖 `Actions.job`），新增 `pauseActive()`/`abortActive()`、`current_step` 实时进度、层末 1 次有界延迟重试、最多 16 条结构化 `failures` 坐标与原因码、独立 `unloaded` 计数与 `max_blocks` 分批施工；修改 `HarnessController` 对只读查询及 `blueprint_status(pause/abort)` 按子动作放行并保留建造互斥；升级 `BlueprintPlanner` 支撑优先拓扑与 `stateMatches`/`classifyBlock` 状态属性核验；扩展 `BlueprintLibrary` 输出 `file_name` 与 `blueprint_sha256` 并在 `resumeFromCheckpoint` 强制核对维度与哈希防篡改（`[BLUEPRINT_HASH_MISMATCH]`）。 |
| P2 连挖护箱原因码、左下亮/暗按钮文字修复与样板扩充 | Gemini | 修正 `AutoActionJob` 遇方实体或不可破坏方块时改走 `失败：[PROTECTED_BLOCK_ENTITY]` / `[UNBREAKABLE_BLOCK]` 并更新 `ReasonCodes`；将 `WorkbenchScreen` 左下亮/暗切换按钮扩宽为 `28px` 并切为 `SEGMENT` 居中样式（同时强化 `DeskButton` 窄按钮居中留白）解决 9px 汉字被裁空白问题；新增带 `oak_stairs` 朝向/半砖/原木轴向/屋顶悬空的 `sample_porch_5x4x5.litematic` 样板蓝图并扩充 `FaceHitMathTest`、`InteractionReceiptTest`、`BlueprintPlannerTest`。 |

离线全量构建及 **147 个测试套件 / 736 项测试全部通过**（0 失败 / 0 错误 / 0 跳过），Harness 租约测试 **28 项全部通过**；`coremods/companion_input.js` 未修改（SHA-256 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4`）。已备份 `0.8.8rc2` 至 `mods_backup\` 并部署 `logistics-companion-0.8.8rc3.jar` 至 `D:\123\.minecraft\versions\1.20.1-Forge_47.4.29\mods`，同时输出回交文档 `NEON-REPLY-0.8.8rc3-20261125.md` 待 Codex 复测。

## 0.8.8rc2 · 2026-11-25

**候选更新版（方案 A 工作台前端全面重构与 `0.8.8` 新能力可视化闭环）：移除浅色模式下突兀的硬编码暗底 Hero 像素横幅并替换为自适应主题的「实时态势遥测」条、将全局紧急控制（紧急停止/AI 接管、暂停/继续、省电）上收至顶栏、重构左侧 7 项精简账本导航（保持 `DeskRoute` 全部页码索引零回归）与左下角一键亮/暗主题切换、补齐「蓝图与建造」与「破坏回滚日志」独立模块化工作台页面、在「当前任务」与 HUD 接入 `GoalBoard` 清单与感知预警，并在「设置 → 输入与感知」落地声音/0ms 避险/昼夜监视开关及 5 类 `policy_whitelist` 白名单可视化查看与编辑。** 构建版本为 `0.8.8rc2` / `rc2-0.8.8-20261125-01`，产物为 `logistics-companion-0.8.8rc2.jar`（`16,470,285 B`，SHA-256 `2EF81719EDBF51D79D525D2097EB25955BB1391C0B9E5F6115239521CA9867DB`）。

| 贡献 | 完成方 | 变更 |
| --- | --- | --- |
| 亮色主题实机视觉审视与方案 A 彻底重构决策 | 项目维护者 | 指出 `0.8.8rc1` 工作台浅色主题下顶部暗色横幅割裂、左栏标识拥挤及 0.8.8 新能力缺少可视化入口等问题，拍板采用方案 A 彻底优化重构。 |
| 工作台顶栏/左栏/首页重构与 `GoalBoard` + HUD 集成 | Gemini | 重构 `WorkbenchScreen`（以自适应主题「实时态势遥测」`badgeGrid` 替代旧 `ART` 硬编码暗底横幅；顶栏右上角常驻全局急停/暂停/省电控制；左栏精简为 `v0.8.8rc2` 与动态紧凑间距并在底部增加 `UiTheme.toggledLightDarkId()` 一键亮/暗切换；7 项 `LEDGER_NAV` 合并「技能与知识」、新增「蓝图与建造」与「历史与回滚」并保持 `DeskRoute` 页码兼容；首页状态卡接入 `GoalBoard` 清单 `[✓]/[▶]/[ ]/[!]/[-]` 与检查点并移除重复按钮；扩展 `liveFingerprint()` 自动刷新）；升级 `Hud` 展示 `[steps_done/steps_total]` 计划进度、`SoundReflex` 危险警报及 `EnvironmentWatch` 昼夜/天气/幻翼提示。 |
| 「蓝图与建造」与「破坏回滚日志」独立视图落地 | Gemini | 新增 `WorkbenchBlueprintView`（在建进度与检查点暂停/继续/中止、`.nbt`/`.litematic` 蓝图发现与点选、BOM 对比随身背包与 `WorldMemory` 缺口、0/90/180/270 旋转与镜像、锚点自动填充、Dry Run 预览及一键开工）与 `WorkbenchRollbackView`（逆序变更账本与容器/方实体警告、`allow_substitute` 开关、单条及 1/5/10 条物理恢复、两步确认清空）。 |
| 感知设置、5 类白名单可视化管理与检索目录补全 | Gemini | 在 `WorkbenchSettingsViews`（`设置 → 输入与感知`）新增「声音事件感知、0ms 避险与昼夜监视」卡片（3 项开关 + `EnvironmentWatch` 与 `SoundSense.recentJson()` 实时读数）及「模组兼容白名单」5 类（`left_tap` / `generator_blocks` / `sound_warn` / `sound_info` / `sound_ignore`）可视化增删恢复面板；在 `SettingsCatalog` 补充 `goal_board`、`sound_sense`、`environment_watch`、`policy_whitelist`、`blueprint_and_rollback` 检索词条，并扩充 `UiDesignSystemTest` 覆盖亮暗极性翻转与新检索词。 |

离线全量构建及 **147 个测试套件 / 733 项测试全部通过**（0 失败 / 0 错误 / 0 跳过，含扩充后的 `UiDesignSystemTest`），Harness 租约测试 **28 项全部通过**；`coremods/companion_input.js` 未修改（SHA-256 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4`）。已部署至本地 1.20.1 测试实例 `D:\123\.minecraft\versions\1.20.1-Forge_47.4.29\mods\logistics-companion-0.8.8rc2.jar`（旧版 `0.8.8rc1` 已归档至 `mods_backup\`），待实机联调验收。

## 0.8.8rc1 · 2026-11-25

**候选更新版（`0.8.8` 核心底层能力大更新）：上线声音事件感知与 0ms 客户端紧急避险、昼夜/天气/幻翼主动监视、纯 Java `.litematic`/`.nbt` 蓝图解析与持久化建造引擎、兼容 API-Key 与 Harness 双模式的持久化长程目标板 `GoalBoard`、纯客户端诚实版破坏回滚日志 `RollbackJournal` / `RollbackJob`、Agent 可自主维护的 5 类 `policy_whitelist` 运行时白名单，以及 Storage Drawers 非破坏左键白名单与面内子区域精确命中（`hit_u`/`hit_v`/`sub_slot`），同时根治 `AutoActionJob` 流体邻接硬编码绕过缺陷（H-B2）。** 构建版本为 `0.8.8rc1` / `rc1-0.8.8-20261125-01`，产物为 `logistics-companion-0.8.8rc1.jar`（`16,446,757 B`，SHA-256 `866BA32522D303A4EFCA2760D1AA9DE7CBEA8BCA40717902C05E50390BB83EAA`）。

| 贡献 | 完成方 | 变更 |
| --- | --- | --- |
| 版本规划、双接入定位、自维护白名单构想与测试环境统筹 | 项目维护者 | 统筹 `0.8.8` 大更新规划，明确长程目标板服务于 API-Key 接入并与 Harness 固有任务清单互补的产品定位，提出由 Agent 通过工具自主扩展/维护模组兼容白名单的底层适配机制，并安排 1.20.1 Forge 测试实例联调环境。 |
| 声音事件感知设计稿与后续演进蓝图 | Muse | 产出《Mod 跟进空间总览》与《声音事件感知设计稿》，提出 `SoundEventListener` 静音/无字幕兼容、白名单分层（`CRITICAL` / `WARN` / `INFO` / `IGNORE`）、0ms 危险反射与听声辨位核心架构。 |
| 多格方块/压缩抽屉细操实机反馈 | Hydrogen | 基于实机体验反馈 `interact_block(button="left")` 需放行 Storage Drawers（如 `storagedrawers:compacting_drawers_2`），并提出面内局部命中点 / 槽位参数需求以解决双格/四格/压缩抽屉上下半格与四象限精准取放问题。 |
| 声音感知、0ms 避险、环境监视与 Agent 可编辑白名单 | Gemini | 基于 Forge 1.20.1 字节码核验落地 `SoundPolicy`、`SoundSense`（在主音量静音前拦截 `SoundEventListener`）、`SoundReflex`（苦力怕嘶嘶/TNT 点火等 0ms 客户端急救反射）、`EnvironmentWatchPolicy` 与 `EnvironmentWatch`（昼夜、雷雨、幻翼监视）；新增 `WhitelistPolicy` 与 `policy_whitelist` 工具支持 5 类白名单持久化读写；修复 `AutoActionJob` H-B2 `explicitAllowFluidNeighbor=true` 硬编码绕过并补齐 `BlockEntity` 保护。 |
| Storage Drawers 白名单与面内精确命中（`hit_u`/`hit_v`/`sub_slot`） | Gemini | 新增 `InteractionPolicy`（放行 Functional Storage + Storage Drawers + `WhitelistPolicy` 动态左键抽屉项）与 `FaceHitMath`（支持 `hit_u`/`hit_v` 与 2 格/4 格/压缩抽屉 `sub_slot` 坐标换算并严格按面构造 `BlockHitResult`），接入 `Reach`、`TargetedInteractionJob`、`ItemOnBlockOperation`、`ItemOnBlockJob`、`Actions`、`Agent` 与 `ToolSchemas`。 |
| 纯 Java 蓝图引擎、长程目标板 `GoalBoard` 与破坏回滚日志 | Gemini | 新增零 MC 依赖的 `BlueprintFormat`（同时解析 `.nbt` 与 `.litematic` 64-bit 跨 word 位解包）、`BlueprintPlanner`（旋转/镜像、BOM 计算、分层拓扑）、`BlueprintLibrary` 与 `BlueprintBuildJob`（检查点续航、缺料停靠、dry-run 预览）；扩展既有 `report_plan` 接入持久化 `GoalBoard`（保持 `ToolMenu.COMMON` 为 26 项不突破 `farming <= 36` 契约）；落地纯客户端诚实版 `RollbackJournal`（接入挖掘/建造/放置路径自动记账，标记方块实体不可恢复容器内容）与 `RollbackJob`（物理放回/挖除回滚与替换方块选项）。 |
| 自动化测试扩充与 1.20.1 Forge 测试实例装配 | Gemini | 新增 9 个纯 Java JUnit 5 测试套件（`WhitelistPolicyTest`、`InteractionPolicyTest`、`FaceHitMathTest`、`SoundPolicyTest`、`EnvironmentWatchPolicyTest`、`GoalBoardTest`、`RollbackJournalTest`、`BlueprintFormatTest`、`BlueprintPlannerTest`）并同步 `ToolMenuTest`；为 1.20.1 Forge 测试实例校验安装 `StorageDrawers 12.15.1`、`Create 6.0.8`、`Forgematica 0.1.13` 与 `MaFgLib 0.1.14`，并生成且经编译后解析器往返验证的 `sample_shelter_5x5.nbt` 与 `sample_tower_3x3.litematic` 测试蓝图。 |

离线全量构建及 **147 个测试套件 / 733 项测试全部通过**（0 失败 / 0 错误 / 0 跳过），Harness 租约测试 **28 项全部通过**；`coremods/companion_input.js` 未修改（SHA-256 `0D70D197D7179F380139A9310A652C9D62A8918A204AC359B11E33C7D0F29CD4`）。随后在 `0.8.8rc2` 完成前端重构并替代该候选包进入实机联调。

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
