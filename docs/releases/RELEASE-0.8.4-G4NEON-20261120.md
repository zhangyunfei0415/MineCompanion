# MineCompanion 0.8.4-G4Neon 源码审阅、核心优化与前端重构报告

**日期**：2026-11-20  
**构建版本**：`0.8.4` / `g4neon-20261120-01`  
**输出产物**：
- 主 JAR：`build/libs/logistics-companion-0.8.4-G4Neon.jar`  
  SHA-256：`fffa0461b3c3fffe71e417422f482162fb0e86d1137cc4cd6c9139fa43b7eb66`
- 源码 JAR：`build/libs/logistics-companion-0.8.4-G4Neon-sources.jar`  
  SHA-256：`52c2b8a9304859591ab97f48e1768d390dd47f1a938f1807c3531d899499674e`

> **验证边界说明**：本构建已完成全量离线编译（503 个 class，Java 17）、Forge 重混淆及 **122 个测试套件 / 651 项 JUnit 单元测试全部通过（0 失败 / 0 错误 / 0 跳过）**；按项目协作约定，未自动安装到测试实例，**游戏内实机界面与交互仍待实机验收**。

---

## 一、0.8.4 全库代码审阅与已落地的后端优化

### A. 本轮直接修复并优化的 10 项实质问题

1. **配置保存 I/O 风暴消减（`Store.java`）**
   - **原问题**：每次 UI 点击开关触发 `Store.save()` 时，不仅序列化全部 `world-*.json`（最多 2048 个 `WorldMemory` 对象及其容器槽位快照），还会无条件重写静止不变的 `presets.json` 和未变化的 `skills.json`。
   - **优化**：
     - 新增 `Store.saveSettingsOnly()`，在保留同一游戏目录多窗口 `controlLock` 所有权校验的前提下，仅执行 `settings.json` 的增量三方合并写入，UI 开关切换从重型全量落盘变为毫秒级轻量操作。
     - 为 `presets.json` 增加首次写入缓存标志 `presetsWritten`；为 `skills.json` 增加内容比对缓存 `lastSavedSkillsJson`，内容未变更时跳过磁盘写入与原子替换。
     - 修复 `recordCommandOutcome` 中直接链式调用 `Minecraft.getInstance().level.dimension()` 在断连临界点可能抛出的 `NullPointerException`。

2. **规则校验与文本清洗热路径消除双正则与 NPE（`Rules.java`）**
   - **原问题**：`Rules.clean(text, max)` 对每个日志、Trace 和状态文本连续执行两次 `text.replaceAll(...)` 全串正则替换；`limitedDenial` / `actionFailed` / `standardStorage` 在每次调用时重新构造 `Set.of(...)` 与正则对象；`actionFailed(null)` 会直接抛出 NPE。
   - **优化**：
     - `Rules.clean` 改为单遍扫描零正则快路径（无控制字符且未超长时零拷贝直接返回原串）；
     - `actionFailed(null)` 显式返回 `false`；
     - 将工具白名单集合与 IPv4 正则 `IPV4_HOST` 提升为 `private static final` 常量。

3. **主线程写盘阻塞移出至后台合并队列（`ActionTrace.java`）**
   - **原问题**：每次工具开始与返回时，主线程直接调用 `Files.writeString` + `Files.move` 落盘 `action-traces/*.json`，且每次运行都重新编译密钥脱敏正则。
   - **优化**：引入单线程守护队列 `Companion-Trace-IO` 与原子槽合并调度（`pending` + `scheduled`），主线程仅完成内存态序列化并立即返回；后台线程合并写盘，高频连续动作不会在主线程产生磁盘 I/O 卡顿。预编译 `SECRET_EXCERPT` 正则。

4. **Baritone 反射缓存与空指针保护（`BaritoneNavigation.java`）**
   - **原问题**：`active()` 和 `miningActive()` 每 tick 通过 `Class.forName(...)` 与 `getMethod(...)` 反射查询 Baritone 类和方法；当 `instance == null` 时 `active()` 通过触发异常并吞掉返回 `false`；`setting()` 多次修改同一项时会覆盖原始玩家设置。
   - **优化**：懒加载并缓存静态 `Api` 句柄表，消除运行期反射查找；`active()` 顶部显式判空；`setting()` 改用 `putIfAbsent` 保留最初玩家设定；删除 `startMining` 未使用变量。

5. **低血中断与会话超时状态清理不一致修复（`Agent.java`）**
   - **原问题**：`interruptWorkForRecovery()` 与 `expireSession()` 各自手写了一遍任务和审批字段清空，漏掉了 `StreamProgress.cancel(...)` 与 `approvedTask`/`approvedEpoch` 的复位，导致低血恢复后残留过期流程批准状态。
   - **优化**：统一复用 `cancelActiveJobs()`、`clearPendingApproval()` 与 `StreamProgress.cancel(...)`。

6. **世界监听断连空指针防护（`WorldSense.java`）**
   - **优化**：在 `blockChanged`、`tick` 与 `record` 入口补全 `mc().level == null` 防护。

7. **热路径零成本结构化克隆替代 JSON 往返反序列化（`BagPolicy.java` / `BagTask.java` / `WorkbenchScreen.java`）**
   - **原问题**：行装模板拷贝 4 处使用 `Store.GSON.fromJson(Store.GSON.toJson(bag), BagPolicy.class)`，并且 `BagPolicy.validate()` 每次编译物品指纹正则。
   - **优化**：新增 `BagPolicy.copy()` 深拷贝方法与预编译 `ITEM_PATTERN`。

8. **本地对话渲染每帧文本折行缓存（`LocalDialogue.java`）**
   - **原问题**：渲染时每帧对最多 100 条本地消息重复执行 `font.split(...)`。
   - **优化**：按 `sequence` 与容器宽度 `size` 缓存折行结果，仅在新消息到达或宽度变化时重新计算。

9. **距离平方计算消除 `Math.pow` 与 `trim()` 集合分配（`WorldMemory.java`）**
   - **优化**：`distanceSquared` 改为直接乘法，并将 `PROTECTED_CATEGORIES` 提升为常量。

10. **冗余死代码清理（`Companion.java` / `WatchAvatar.java`）**
    - 移除 `Companion.java` `!bucket` 分支中恒为 false 的桶判断；移除 `WatchAvatar.java` 中被立即覆盖的死赋值。

---

### B. 审阅记录中保留的深层架构观察（供后续大版本重构参考）
本轮严守不破坏已有行为与外部契约（MCP / Harness / 权限 / 动作执行器），以下架构债留存清晰清单，建议后续单独排期：
- **`Agent.java`（2537 行）上帝类拆分**：当前集成了会话历史、工具策略分发、流式回调、任务队列与本地口令解析；`Agent.busy` / `state` / `task` 与后台 HTTP 线程间仍依赖 `mc().execute(...)` 纪律同步，后续可拆为独立状态机与只读视图。
- **`WorkbenchPages.java`（1564 行）渐进迁移**：14 个领域子编辑器目前已通过统一卡片壳体托管，后续可继续用本轮 `WorkbenchScreen` 的 `card` / `segmentRow` / `toggleRow` DSL 逐页替换早期的手动坐标排版。
- **可选模组桥接反射（`AeTerminalBridge` / `AeWorkflowBridge`）**：目前已有 `accessors` 缓存，其余桥接类若频率升高可沿用 `BaritoneNavigation.Api` 模式统一缓存。

---

## 二、前端设置界面与视觉体验彻底重构（G4Neon 设计体系）

### 1. 独立设计系统（`UiTheme` + `UiDraw` + `SettingsCatalog` + `DeskButton`）
- **新增 [UiTheme.java](../../src/main/java/dev/mibao/companion/UiTheme.java)**（18 项语义色板 Token，无渲染依赖可离线单测）：
  - 全新默认主题 **`霓虹`（G4Neon Studio）**：深曜石玻璃基底 + 电光青（`#38F2E6`）与幻夜紫（`#A855F7`）渐变顶线、高对比度文字与独立语义状态色（成功翠绿 / 警示琥珀 / 高危珊瑚红）。
  - 同时全面重调 **`深海`**、**`纸白`（浅色高对比）**、**`终端`（荧光 CRT）**、**`原版`（工匠橡木金）** 四套原有主题，所有主题共享统一对比度规范。
- **新增 [UiDraw.java](../../src/main/java/dev/mibao/companion/UiDraw.java)**：
  - 1px 切角圆角卡片（带顶部微高光）、平滑水平渐变条、紧凑状态徽章（Pill Badge）、28×14px 滑块开关（Toggle Switch，支持危险警示红轨道）、立体键帽（Keycap）与比例滚动条。
- **升级 [DeskButton.java](../../src/main/java/dev/mibao/companion/DeskButton.java)**：
  - 支持 `DEFAULT` / `PRIMARY` / `DANGER` / `NAV` / `TOGGLE` / `SEGMENT` / `SWATCH`（实时双色预览卡片）/ `COLLAPSE` 8 种交互变体；
  - 新增 `clip(top, bottom)` 视口点击与悬停裁剪，彻底杜绝滚动窗口越界穿透点击。
- **新增 [SettingsCatalog.java](../../src/main/java/dev/mibao/companion/SettingsCatalog.java)**：
  - 建立覆盖全部 5 个设置页及 9 个子编辑器的中英文多词实时检索索引（26 个索引条目）。

### 2. 命令台外壳与交互升级（[WorkbenchScreen.java](../../src/main/java/dev/mibao/companion/WorkbenchScreen.java)）
- **双层滚动架构（彻底告别滚动失焦与卡顿）**：
  - 将左侧导航栏 / 顶部搜索框 / 紧急操作按钮归入 `chromeWidgets`，可滚动卡片内容归入 `contentWidgets`。
  - 滚轮翻阅与拖拽右侧滚动条时仅原地更新控件坐标，**不再整页重建 `rebuild()`**，输入框焦点与光标不丢失；同步修复设置搜索框的跨帧持久化（不打断中文输入法 IME 组字）。
- **准确未保存草稿感知（`dirtyKeys`）**：仅当用户真实修改了输入框内容且尚未保存时，卡片标题与页脚才点亮琥珀色 `未保存修改` 徽章。
- **指纹防抖 `tick()`**：首页与连接页仅在真实运行状态发生变化时刷新，避免空闲每秒重绘闪烁。

### 3. 五大设置页彻底重构（[WorkbenchSettingsViews.java](../../src/main/java/dev/mibao/companion/WorkbenchSettingsViews.java)）
1. **模型与连接**：
   - 分段切换 `API 模型` / `本地 Harness (MCP)`，实时展示租约倒计时或协议状态；
   - 独立展示 **模型推理强度（低/中/高）** 与 **执行风格（快速/严谨）** 分段选择器与预设组合；
   - 显式 **请求字符预算** 应用校验按钮；
   - **局部判断层（OFF / CLOUD / LOCAL）** 独立卡片：选择 `OFF` 时自动收起端点表单，`CLOUD/LOCAL` 分开保留配置与 `GOOGLE/JEV/OPENAI` 分段协议切换、草稿脏状态提示及重载状态查询。
2. **权限与安全**：
   - 条件置顶 **待审批操作警示卡片**；
   - 顶部 **授权模式（LIMITED / ASK / FULL）分段器 + 实时生效权限矩阵**（直观展示 14 项能力当前真实 `✓ / ✗` 状态）；
   - **外部通道与安全护栏独立分组**（公屏回复、Harness 截图、联网、聊天框打字/提交、关键方块与传送保护）；
   - **有限模式细分权限可折叠卡**：当处于 `FULL` 时自动折叠并标注 `已被 FULL 覆盖`，避免误操作；
   - **修复原版每敲一个字就全量写盘的指令策略表单**：改为 `cmd/` 草稿 + 显式边界校验与 `保存指令策略 / 取消草稿` 按钮。
3. **输入与感知**：输入隔离开关卡片 + 7 项快捷键**立体键帽总览卡片**。
4. **显示与陪伴**：**G4Neon 视觉主题工坊**（5 张带实时预览色条的 `SWATCH` 卡片，点选即时全局换肤）+ 前台流式展示 + 布局/立绘入口。
5. **数据与维护**：Harness 租约预设面板 + 共享技能库目录与备份维护卡片。

### 4. 全界面视觉统一（HUD + 本地交流 + 6 个辅助 Screen）
- 同步将 **[Hud.java](../../src/main/java/dev/mibao/companion/Hud.java)**、**[ProcessHud.java](../../src/main/java/dev/mibao/companion/ProcessHud.java)**（新增详细面板比例滚动条）、**[LocalDialogue.java](../../src/main/java/dev/mibao/companion/LocalDialogue.java)** 以及全部 6 个二级窗口（**[NoticeScreen](../../src/main/java/dev/mibao/companion/NoticeScreen.java)**、**[TaskTimelineScreen](../../src/main/java/dev/mibao/companion/TaskTimelineScreen.java)**、**[CompanionHistoryScreen](../../src/main/java/dev/mibao/companion/CompanionHistoryScreen.java)**、**[RecipeBrowserScreen](../../src/main/java/dev/mibao/companion/RecipeBrowserScreen.java)**、**[FollowTargetScreen](../../src/main/java/dev/mibao/companion/FollowTargetScreen.java)**、**[HudLayoutEditor](../../src/main/java/dev/mibao/companion/HudLayoutEditor.java)**）升级为统一的 `UiTheme` + `UiDraw` + `DeskButton` 视觉体系。

---

## 三、实机界面首测建议清单（供手动验收）
将 `logistics-companion-0.8.4-G4Neon.jar` 放入测试实例 `mods/` 后（请先备份旧 JAR），建议重点检查以下项目：
1. **默认主题与即时换肤**：F8 打开工作台，确认新安装默认展示 G4Neon 霓虹主题；前往 `设置 → 显示与陪伴`，依次点击 5 张主题卡片，确认工作台、侧栏与辅助界面即时同步换肤且关闭重开后保留。
2. **设置实时搜索与跳转**：在设置左侧栏搜索框输入中文（如 `判断`、`公屏`、`导航`）和英文（如 `harness`、`api`），确认中文输入不丢焦点，点击搜索卡片能直达对应设置页或子编辑器。
3. **平滑滚动与拖动滚动条**：在 `设置 → 权限与安全` 中分别使用鼠标滚轮与右侧滚动条拖拽上下翻阅，确认页面不闪烁、输入框可正常聚焦、卡片外控件点击不会穿透表头。
4. **权限矩阵与草稿保存**：在 `LIMITED` / `ASK` / `FULL` 间切换，观察生效权限矩阵 ✓/✗ 芯片与「有限模式细分权限」折叠卡片是否联动；编辑局部判断层或世界指令半径后确认未保存草稿徽章点亮，保存与取消功能符合预期。
5. **二级窗口与游戏内浮层**：检查 F7 侧栏简洁/详细切换与滚动条、Home 本地对话浮层、工作过程时间线（点击卡片展开）、配方工坊及观看布局编辑器在不同 GUI 缩放下的文字换行与排版。
