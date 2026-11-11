# 0.3.8 client-workflows-20261110-038

## execution-20261111-01 集成更新

本构建通过 Forge Jar-in-Jar 元数据内嵌官方 Baritone 1.10.1 API Forge 发行包，目标 Minecraft 1.20.1。二进制保持原样，配套上游源码归档、LGPL 许可证、GPL 全文和构建说明位于 `vendor/baritone`，完整源码备份包含这些文件。伙伴适配独立位于 `BaritoneNavigation.java`。下文历史记录中的“未捆绑 Baritone”适用于其对应旧构建。

本轮的 CommandProfile/CommandJob、CommandSkills、ModelContext、TaskGateway、AE/JEI 客户端操作桥及核对策略为独立实现，未复制或捆绑 AE2/JEI 实现代码或二进制。AE2 使用可选的公共客户端菜单调用，不读取服务器 IGrid、库存服务或集成服务器规则。具体 API 版本不同会拒绝并返回兼容性原因，不通过私有字段修改服务器状态。

参考官方 AE2 `forge/1.20.1` 源码中的 AEBaseMenu/SlotSemantics、PatternEncodingTermMenu、CraftAmountMenu/CraftConfirmMenu/CraftingPlanSummary，以及 API 的 IPartHost、PatternDetailsHelper/IPatternDetails。API 文件为 MIT；菜单实现文件标记 LGPL-3.0-or-later。此处仅查阅接口及正常客户端动作语义，无实现代码改编，AE2 仍由用户单独安装。来源：https://github.com/AppliedEnergistics/Applied-Energistics-2/tree/forge/1.20.1/src/main/java/appeng 。

JEI 15.56.0.205 使用已有 compileOnly API，转移入口经公开 IRecipeTransferManager/IRecipeTransferHandler 和配方布局；不把转移成功当成编码或生产完成。来源：https://github.com/mezz/JustEnoughItems/tree/1.20.1 ，现有许可证记录继续保留。实际运行时以安装版本的处理器是否支持当前菜单/类别为准。

# 0.7.5 研究补充

Meteor Client 固定提交 e3a2e77967629d216e127b5ceecf71fb15f9d2e5（GPL-3.0）仅作源码行为研究；未复制其代码或资源到本Mod，未添加Meteor/Baritone二进制依赖。参考文件与哈希见 REFERENCES-0.7.5.json。用户旧McAfkAuto源码只读对照；已有女仆/Maid Intelligence记录和署名继续保留。

worksession-20261027-06 re-reads the pinned MIT maid task/session sources documented in MAID-WORKSESSION-20261027-06.md. WorkSession, SessionPlan, SessionProgress and SupplyWait plus job recovery changes are independent normal-player client implementations. No additional upstream code, assets or runtime dependencies are copied. Existing WorkPlan attribution remains.

# Reference and dependencies

0.7.3 rechecks the same pinned official MIT TaskAttack and MaidPickupEntitiesTask for target lifecycle and reachability boundaries. TargetRecovery, InventoryTolerance, WorkLedger and the panel/layout changes are independent client-player implementations; no additional upstream code or assets are copied. Existing MIT notices and the WorkPlan attribution below remain. This is the first bounded recovery stage, not a port of the maid entity Brain or server navigation.

0.5.0 references Touhou Little Maid's official `1.20` branch for Minecraft 1.20.1 at commit `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6`: https://github.com/TartaricAcid/TouhouLittleMaid/tree/8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6 . Code is MIT (Copyright 2019–2025 tartaric_acid); assets are CC BY-NC-SA 4.0. The MIT reference license is included in `src/main/resources/licenses/touhou-little-maid-MIT.txt`. Task decomposition, target validation, pacing and inventory remainder checks informed independent client-player implementations. No upstream maid entity/server implementation, resources or runtime dependency is included. See TOUHOU-STUDY.md for exact reviewed files and boundaries.

Earlier versions contain original code informed by the design review in ../ai-companion-research. Version 0.6.0 adds the explicitly attributed MIT adaptation below; no upstream art is included. Forge/Minecraft supply Gson, logging, rendering and client APIs at runtime. No API keys or remote services are bundled.

Baritone is optional and accessed through its public runtime API by reflection when installed. It is not bundled in this version. Baritone is LGPL-3.0, https://github.com/cabaletta/baritone ; retain its license when installing or distributing it separately.

The built-in navigation is conservative walking/jumping without terrain modification. No third party script runtime, Python or Node service is required.

0.3.1 adds original client receipt evidence and shared-skill storage code. Netty is supplied by Minecraft/Forge; the test harness resolves the existing 4.1.82.Final transport library for EmbeddedChannel checks, without bundling it in the mod. No Mixin, packet library, upstream bot implementation or external runtime service is added.

The local AltoClef ContainerCache (MIT) and Baritone WorldProvider (LGPL-3.0, cached 1.21.4 tree) were reviewed for limitations and data organization only. Neither implementation was copied. Existing build-only compatibility sources/helpers stay outside the runtime JAR.

0.6.0 directly adapts `src/main/java/com/maidintelligence/engine/plan/TaskPlan.java` from RhineIris/Maid Intelligence, commit `a2fea9aa6f7079afa37ee608eddb32d1a99a35c3`, into `WorkPlan.java`. Code license: MIT, Copyright (c) 2026 RhineIris. Full MIT notice is bundled as `licenses/maidintelligence-MIT.txt`. Source: https://github.com/RhineIris/touhou-little-maid-maidintelligence/tree/a2fea9aa6f7079afa37ee608eddb32d1a99a35c3 . The adaptation adds immutable bounded steps, unique verified receipts and conditional advancement. MiningJob/HuntJob use it in actual client actions. Other upstream scanning/pathfinding/server interactions were reviewed for design and pitfalls only, not copied. No maid runtime dependency or CC BY-NC-SA assets are bundled. Exact review/provenance is in MAIDINTELLIGENCE-STUDY.md and the delivery research snapshot.


0.7.1 follows the source-reviewed lifecycle/adapter/ledger ideas in the second Touhou pass (../ai-companion-research/TOUHOU-SECOND-PASS-20261026.md), independently implemented for normal client-player actions. No additional upstream Java or assets are copied. License-conflicted/missing-license extensions are not copied. The existing WorkPlan MIT adaptation remains attributed above. Static javap analysis of the user's Limitless/Sukuna binaries only established polling, Ctrl and focus requirements; neither binaries nor decompiled implementations are bundled.

The runtime input bridge is original code using Forge's existing coremod/ASM facilities; it adds no external keyboard program, native library, Python/Node or server mod. Test-only Nashorn 15.4 (GPL-2.0 with Classpath Exception) and ASM 9.9.1 (BSD-3-Clause) execute/validate the shipped JS in the isolated validation harness; they are not included in the runtime or source JAR. Forge already supplies its own script/ASM runtime.


0.7.2 independently adapts lifecycle ideas from TaskAttack, IAttackTask, MaidMeleeAttack and MaidUseShieldTask in the same pinned official MIT tree; no additional upstream implementation or art is copied. Details and reviewed hashes are in COMBAT-STUDY-0.7.2.md. Existing WorkPlan attribution remains unchanged.

The viewer atlas is newly generated with the built-in image_gen tool from user-provided character references, with the ChatGPT dragon design revised on user instruction. Reference screenshots and logos are not bundled. The generated PNG, provenance, dimensions and UV metadata are in assets/logistics_companion/textures/gui; full final prompt is in IMAGEGEN-PROMPTS.md. No new runtime dependency is added for the optional harness; Java's local HTTP server and a standard-library-only optional Python controller are used. The game does not require Python for API mode or normal play.


0.7.3 executor-20261027-01 independently implements pickup stances, bounded recovery, work-slot selection and whole-task inventory accounting after targeted source reviews. New snapshots: LittleMaidSomeInstructions (MIT, commit 3eb06297bad8b9643a538dec4c7c6edd75bd5c34), TLM Sable Compat (MIT declared in metadata; standalone license notice absent from pinned 5ff35a0156fbd02754df14b515eb4d010cde0c68 tree), and JsMacros (MPL-2.0, b5019c34539c6aa0de2d98fb1520c64fdd692769). No implementation or assets from these three are copied or bundled. Official maid, AltoClef and Baritone remain design references only for this pass. See EXECUTOR-STUDY-20261027.md and research sources.lock.json for reviewed sections, exact hashes, version differences and server/client boundaries. Existing Maid Intelligence WorkPlan attribution and full notice remain unchanged.


executor-20261027-02 adds independently written client execution, collision-surface planning, task preflight, preparation/cargo accounting, environment-scoped experience and recording delta/replay code. No additional upstream code or assets are copied and no runtime dependencies are added. The research and license boundaries in EXECUTOR-STUDY-20261027.md remain applicable. The existing Maid Intelligence WorkPlan MIT adaptation and bundled attribution remain intact. ClientRecipes reads recipes already synchronized by Minecraft; no Minecraft recipe assets or implementation classes are bundled. The local offline build helper disables only ForgeGradle 6.0.54's optional online certificate-connectivity preflight for --offline runs; it does not change HTTPS trust validation or operating-system settings.

## 0.7.7 references

JEI 15.56.0.205 public API (MIT), compile-only, not bundled. Pinned source/URL/hash recorded in delivery and setup-jei-api.py. https://github.com/mezz/JustEnoughItems

Numen e32d981eff9da39356704417308dcafa949425ec: LGPL-3.0 code and separately reserved artwork. PersonaLibrary/ConvoState/ConvoLog and architecture are design references; no implementation or art copied. Our persona/history modules are independently implemented. https://github.com/Dwinovo/minecraft-numen

Farmer's Delight 1.20 CookingPotRecipe (MIT) and Kaleidoscope Cookery 1.6.0 published source JAR were read to name public recipe accessors. No upstream implementation/assets bundled. Optional adapter calls only reviewed accessors, fails explicitly on absent fields. Runtime ingredients/recipes come from the client, not bundled recipe assets.

Original workshop-077.png generated with built-in image_gen on 2026-10-29; prompt/provenance in assets/logistics_companion/art-provenance-077.json. No Numen artwork used.

Native protocol references: https://developers.openai.com/api/docs/guides/migrate-to-responses ; https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls ; https://ai.google.dev/api/generate-content . Native implementations independently written, no vendor SDK bundled.

## 0.7.8 client adapters and original art (2026-10-29)

2026-10-30 machine adapters: Refined Storage v1.12.4 client GridScreen/IGridView/ItemGridStack and normal GridItemPullMessage were reviewed for item-view identity, quantity and ordinary single extraction flags. Independent optional reflection bridge only; no upstream classes, resources or implementation bundled. RS search-filtered client views are explicitly scoped. https://github.com/refinedmods/refinedstorage/tree/v1.12.4

Mekanism 1.20.x client container and Create 1.20.1 millstone/motor public APIs were reviewed to distinguish menu operations from world processing, FE from rotation, and client-only observations from unknown server state. No upstream source or assets bundled. https://github.com/mekanism/Mekanism/tree/1.20.x ; https://github.com/Creators-of-Create/Create/tree/mc1.20.1/dev

2026-10-30: independently implemented passive Sophisticated Core S2C menu-receipt hooks after reviewing SyncContainerStacksMessage, SyncSlotStackMessage and StorageContainerMenuBase on the upstream 1.20.x branch. Upstream source is not bundled or copied; the published projects reserve rights. The adapter reads only messages already handled by the client, never server storage or a new network command. Unsupported message layouts remain unconfirmed. https://github.com/P3pp3rF1y/SophisticatedCore/tree/1.20.x

AE2 forge/v15.4.11 public client repository/menu interfaces were reviewed against the installed 15.4.11 JAR. Upstream LICENSE is LGPL-3.0. Optional bridge and transfer state machine are independently implemented; no AE2 implementation, classes or assets are bundled. Calls use the normal client menu interface, not server grid/capabilities. https://github.com/AppliedEnergistics/Applied-Energistics-2/tree/forge/v15.4.11

Farmer's Delight 1.20 source and installed 1.3.4 menu signatures were reviewed for cooking slots, inherited no-op refresh contract and synced cutting-board item accessor. Independent adapters only; no upstream implementation copied. https://github.com/vectorwing/FarmersDelight/tree/1.20

The user-supplied bailong-desktop-pet reference has no general source license and ASSETS.md does not grant asset redistribution. No upstream source or artwork is bundled. Original dragon_states_078.png generated with built-in image_gen; provenance and hash in dragon_states_078.json. It is a new state atlas, not the upstream 35-animation WebM player. https://github.com/jingh12312-rgb/bailong-desktop-pet/blob/main/ASSETS.md

## 0.7.8 universal-20261031-01

Portable skill packages, semantic GUI helpers, capability/result descriptors, execution-style policy, desk UI and checkpoint test driver are original client-player implementations. No additional upstream code, assets, runtime libraries, server mod or native input program is bundled. Existing pinned licenses and optional bridge boundaries above remain.

`logistics-desk-20261031.png` is newly generated original bitmap art using built-in image_gen, without upstream images or character references. Prompt, dimensions and SHA256 are recorded alongside it in `logistics-desk-20261031.json`; foreground text and interactive controls are rendered by code. No Numen or bailong upstream artwork is redistributed.


## 0.7.8.1 Mouse Tweaks参考

上游 https://github.com/YaLTeR/MouseTweaks ，minecraft-1.20.1分支提交4d7829795a1694e1abe62da5b9bd8a16edc8b8f3，BSD-3-Clause。参考按槽位右拖分配的行为，不复制源码，不捆绑其Mod，不调用其私有鼠标事件。伙伴使用原版QUICK_CRAFT包和正常槽位点击。公开源码与许可证保存在项目研究目录。


## 0.7.8.2 streaming implementation references (2026-11-03)

SseReader / ProviderStream / StreamMailbox / ProcessHud are independently authored Java code; no upstream source or assets copied or bundled, no React/Node dependency. Reviewed event organization and UI ideas from Vercel AI SDK (Apache-2.0, https://github.com/vercel/ai), assistant-ui (MIT, https://github.com/assistant-ui/assistant-ui), and AG-UI (MIT, https://github.com/ag-ui-protocol/ag-ui). This is not a claim of AG-UI protocol conformance or SDK integration. Any future direct code reuse must pin revision and include its notices.

Wire references: https://developers.openai.com/api/docs/guides/streaming-responses ; https://platform.claude.com/docs/en/build-with-claude/streaming ; https://ai.google.dev/api/generate-content#method:-models.streamgeneratecontent . Only public output text is displayed; native private reasoning/signatures remain opaque history metadata.
# 0.8.1 照明与钓鱼研究补充

本轮阅读了 More Overlays Updated 的 1.20.1 Forge 源码（MIT）、XPlus Autofish 的对应 Forge 分支（源码声明 GPLv3 或之后版本）及 North-West-Wind AutoFish 多版本源码（GPLv3）。固定提交、归档校验值与文件分析见 `docs/RESEARCH-LIGHTING-FISHING-20261112.md`。

新照明与钓鱼任务使用项目独立实现，基于正常客户端区域数据、自己浮漂运动、物品使用及库存同步。上游源码保存在工作区研究目录，相关运行代码采用本项目许可。
