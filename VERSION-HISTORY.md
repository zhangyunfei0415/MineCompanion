# MineCompanion 版本与贡献记录

这里记录公开发行和影响下一版的重要开发节点。贡献按可核对的工程、测试和产品决策分工记录。自动化测试和实机验收在各版本内分别说明。 [各版详细发布说明](docs/releases/README.md)

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
