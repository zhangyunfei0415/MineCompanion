# MineCompanion 0.8.5-G4Neon（开放技能记忆生态）工程交付报告

**日期**：2026-11-21  
**构建标识**：`0.8.5` / `g4neon-open-memory-20261121-01`  
**输出产物**：
- 主 JAR：`build/libs/logistics-companion-0.8.5-G4Neon.jar`（`12,968,709` 字节）  
  SHA-256：`f19c6e4650c55dfa8d3aa537c9bafaf0cedb94a4718fb07505323fb2b178a0fb`
- 源码 JAR：`build/libs/logistics-companion-0.8.5-G4Neon-sources.jar`（`11,483,471` 字节）  
  SHA-256：`79844592cb92f2cf07c4f5502bd8f0aa6719b27bb7e19b20cef5f2e827e0ab27`

> **验证边界声明**：本交付已完成全量离线编译与重混淆（`clean build --offline`，`BUILD SUCCESSFUL in 40s`）、543 个 `.class` 文件与嵌套依赖核验、以及 **129 个测试套件 / 673 项 JUnit 单元测试全部通过（0 失败 / 0 错误 / 0 跳过）**；按协作边界，未修改 `companion_input.js`、未自动安装 JAR 到游戏实例、未启动游戏，**游戏内实机 UI、Tooltip 悬停捕获与跨整合包实机导入仍待手工实机验收**。

---

## 一、核心落地成果

### 1. 通用 GUI 感知与交互底层原语（零特定 Mod 依赖 / 零新 Coremod）
1. **跨包双级界面签名（[UiSignature.java](src/main/java/dev/mibao/companion/UiSignature.java) + [VisualAssist.java](src/main/java/dev/mibao/companion/VisualAssist.java) + [GuiContext.java](src/main/java/dev/mibao/companion/GuiContext.java)）**：
   - `portableSignature` 基于 `menuClass + blockId + Slots(slotClass, x, y, playerInventory)` 生成，剔除 `EnvironmentKey`、世界坐标、物品数量与窗口像素，解决跨整合包分享签名失配问题；
   - `layoutSignature` 固定 `block=*` 作为同布局多等级机器回退签名；
   - `GuiContext.observe()` 同时返回旧版 `semantic_signature`、`portable_signature`、`portable_layout_signature`、`geometry.image_width / image_height`、`container_data` 及自动命中的 `matched_ui_profile`。
2. **只读悬停 Tooltip 探测 `inspect_tooltip`（[TooltipCapture.java](src/main/java/dev/mibao/companion/TooltipCapture.java) + [GuiTooltipProbe.java](src/main/java/dev/mibao/companion/GuiTooltipProbe.java) + [GuiTooltipProbeJob.java](src/main/java/dev/mibao/companion/GuiTooltipProbeJob.java) + [PointerState.java](src/main/java/dev/mibao/companion/PointerState.java) / [AgentPointer.java](src/main/java/dev/mibao/companion/AgentPointer.java)）**：
   - 注册于 [Companion.java](src/main/java/dev/mibao/companion/Companion.java) 现有 Forge 事件总线，双通道捕获 `RenderTooltipEvent.GatherComponents` 与 `RenderTooltipEvent.Pre`（对 `ClientTextTooltip` 采用按 `FormattedCharSequence.class` 类型反射扫描，免疫 Mojmap/SRG 混淆差异）；
   - 通过 `AgentPointer.HoverProbe` 200ms 短租约只读移动虚拟指针，**无需 `fullGameControl`**、不改变物理鼠标、不伪造鼠标按键，支持单次批量探测 `1..8` 个槽位或相对偏移点。
3. **容器相对锚点点击 + 服务端 `ContainerData` 监听 + 通用方框摘要**：
   - `gui_relative_point` 在 [GuiProfileActions.java](src/main/java/dev/mibao/companion/GuiProfileActions.java) 实现容器 `(guiLeft+dx, guiTop+dy)` 单次点击与界面/数据增量回报，拒绝命中标准物品槽与伴侣管理界面；
   - [ClientEvidence.java](src/main/java/dev/mibao/companion/ClientEvidence.java) + [MenuDataState.java](src/main/java/dev/mibao/companion/MenuDataState.java) + [MenuContext.java](src/main/java/dev/mibao/companion/MenuContext.java) 零反射追踪 `ClientboundContainerSetDataPacket`；
   - [MachineObservation.java](src/main/java/dev/mibao/companion/MachineObservation.java) 为白名单外未知方块实体提供过滤敏感/计时字段后的有界只读摘要（`generic_client_block_entity`）。
4. **4 个接入 `SessionPlan` 的语义 GUI 技能原语（[GuiProfileActions.java](src/main/java/dev/mibao/companion/GuiProfileActions.java)）**：
   - `gui_put_role`、`gui_take_role`、`gui_trigger_control`、`gui_wait_output` 纳入 `SessionPlan.TOOLS`（同步放行有服务端核验的 `ae_fetch` / `ae_deposit`），严格在运行时按 `UiProfile` 实时解析坐标/槽位并与服务端库存同步核验；`gui_trigger_control` 支持点击前自动 Tooltip 关键词防误触校验。

---

### 2. 开放共享格式 `ModSkillBundle (schema = 2)`、嵌套双轨与本地适配
- **结构化界面模型 [UiProfile.java](src/main/java/dev/mibao/companion/UiProfile.java) 与 [UiProfileStore.java](src/main/java/dev/mibao/companion/UiProfileStore.java)**：支持精确 `(x,y)` 优先、2px 容差、槽位索引回退解析，支持 `portable_signature > layout_signature > block+menu` 多级匹配及 `ui_profile_draft` 草稿提交与人工确认。
- **四合一社区记忆包 [SkillBundle.java](src/main/java/dev/mibao/companion/SkillBundle.java) & [BundleLibrary.java](src/main/java/dev/mibao/companion/BundleLibrary.java)**：
  - 保留 `SkillPackage (schema=1)` 100% 向后兼容；
  - 将 `Playbook` 攻略、`UiProfile` 映射、`AdapterDescriptor`、`SkillPackage[]` 技能、`compatibility`（[VersionRange.java](src/main/java/dev/mibao/companion/VersionRange.java) SemVer 匹配）封装为 `*.mcskill.json`；
  - 由 [CanonicalJson.java](src/main/java/dev/mibao/companion/CanonicalJson.java) 计算防篡改 `content_sha256`，[BundleSanitizer.java](src/main/java/dev/mibao/companion/BundleSanitizer.java) 递归拦截本机盘符/用户路径、`file:///`、API Key/Token 与世界坐标；
  - **轨道 B 嵌套于轨道 A**：`track_a.track_b_promotion` 需 `track_a_verified == true`、依赖 Mod 就绪及前置条件满足后方可晋级为持久复用状态 `track_b_reuse`；当 `EnvironmentKey` 发生环境漂移时自动降级回退至 `track_a_reverify`；
  - **本地化适配叠加层 [BundleAdaptation.java](src/main/java/dev/mibao/companion/BundleAdaptation.java) + `bundle_adapt`**：上游原包不可变，所有本地界面偏移补丁、额外 `portable_signatures` 别名、攻略/避坑追加、技能参数默认值与覆盖、以及轨道 A/B 进度持久化在 `library/adaptations/*.adapt.json`，并可通过 `bundle_pack` 携带 `derived_from` 脱敏导出二次分享。

---

### 3. 全链路工具注册表、G4Neon 前端与内置示范
- **12 个新工具**（1:1 同步至 `Rules`、`ToolSchemas`、`CapabilityCatalog`、`PermissionPolicy`、`HarnessCapabilities`、`ToolMenu`、`SkillLibrary`、`MachineAdapters`、`Agent`）：
  `inspect_tooltip`、`gui_relative_point`、`gui_put_role`、`gui_take_role`、`gui_trigger_control`、`gui_wait_output`、`ui_profile_draft`、`bundle_list`、`bundle_inspect`、`bundle_adapt`、`bundle_pack`、`demo_summarize`。
- **G4Neon 开放技能记忆中心（[WorkbenchScreen.java](src/main/java/dev/mibao/companion/WorkbenchScreen.java)）**：升级「技能库」页，提供剪贴板导入 `.mcskill.json`、记忆包轨道 A/B 与环境漂移徽章、本地适配详情/一键重置、脱敏导出至文件与剪贴板，以及 `UiProfile` 草稿审阅卡片。
- **内置与示例记忆包**：
  - 内置零依赖参考包：[builtin-vanilla-furnace-demo.mcskill.json](src/main/resources/skill-bundles/builtin-vanilla-furnace-demo.mcskill.json)（`content_sha256`：`4af06873aaf16f16549993b00a2a38f68684a054ade5a55466679d6564abe9c5`）
  - 社区科技 Mod 模板：[mekanism-enrichment-sample.mcskill.json](examples/bundles/mekanism-enrichment-sample.mcskill.json)（`content_sha256`：`96edb164403d9cc0a1e299a82dddef76399f92183ff84d7df01a4ab28ce2314a`）

---

## 二、离线构建与测试验收清单

- **构建命令**：`clean build --offline` → `BUILD SUCCESSFUL in 40s`
- **单元测试结果**：共 **129 个套件 / 673 项测试全部通过**（`failures=0, errors=0, skipped=0`，包含既有 651 项零回归及新增 7 个套件 22 项测试方法）：
  - [UiSignatureTest.java](src/test/java/dev/mibao/companion/UiSignatureTest.java)（3/3）
  - [VersionRangeTest.java](src/test/java/dev/mibao/companion/VersionRangeTest.java)（3/3）
  - [UiProfileTest.java](src/test/java/dev/mibao/companion/UiProfileTest.java)（4/4）
  - [SkillBundleTest.java](src/test/java/dev/mibao/companion/SkillBundleTest.java)（4/4）
  - [GuiProfilePolicyTest.java](src/test/java/dev/mibao/companion/GuiProfilePolicyTest.java)（3/3）
  - [MenuDataAndTooltipTest.java](src/test/java/dev/mibao/companion/MenuDataAndTooltipTest.java)（3/3）
  - [BundleHubWorkflowTest.java](src/test/java/dev/mibao/companion/BundleHubWorkflowTest.java)（2/2）
- **产物与安全检查**：
  - 543 个 `.class` 全部为 Java 17（`major=61`）且严格位于 `dev/mibao/companion/`；
  - `META-INF/jarjar/baritone-api-forge-1.10.1.jar`、`META-INF/licenses/baritone/LICENSE`、`skill-bundles/builtin-vanilla-furnace-demo.mcskill.json`、`minecompanion-build.properties`（`version=0.8.5`, `build=g4neon-open-memory-20261121-01`）全部打包完整；
  - `coremods/companion_input.js` SHA-256 为 `0d70d197d7179f380139a9310a652c9d62a8918a204ac359b11e33c7d0f29cd4`，与源码及基线完全一致。

---

## 三、手工实机验收建议步骤

1. **原版熔炉零依赖端到端冒烟**：打开原版熔炉，运行 `inspect_gui`，核对 `portable_signature` 与 `matched_ui_profile`（`minecraft:furnace_ui`）自动命中，并从工作台「开放技能记忆中心」运行内置 `builtin:furnace-role-smelt`，验证 `gui_put_role -> gui_wait_output -> gui_take_role` 全程服务端真实回执与轨道 A 自动标记核验通过。
2. **陌生科技 Mod 自绘 GUI 与 Tooltip 探测**：在关闭 `fullGameControl` 下调用 `inspect_tooltip` 批量探测自绘按钮与流体/能量条；在开启 `gui` 权限后调用 `gui_relative_point` 与 `ui_profile_draft`，检查 G4Neon 工作台草稿列表与 `bundle_pack` 导出 `.mcskill.json`。
3. **跨整合包剪贴板导入与双轨晋级**：将导出的 `.mcskill.json` 复制到另一组 Mod 列表环境中，在工作台点击「从剪贴板导入 .mcskill.json」，确认 `portable_signature` 跨整合包直接命中，并验证 `bundle_adapt` 本地适配补丁、轨道 B（AE2）晋级及整合包变动后降级待复核状态展示。
