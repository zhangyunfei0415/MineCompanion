# MineCompanion

**让 AI 成为你的 Minecraft 玩家伙伴。**

MineCompanion 是 Minecraft Java 1.20.1 的纯客户端 Forge 模组。它控制当前客户端的正常玩家角色，通过本地执行器完成移动、背包、容器、制作、农作和探索，并可连接模型 API 或外部 Harness。

A client-side Minecraft AI companion for Forge 1.20.1. Connect a model API or a local harness to operate a normal player, with local skills, configurable permissions and human cooperation.

## 下载与安装

在 [Releases](https://github.com/zhangyunfei0415/MineCompanion/releases) 下载 `logistics-companion-0.8.1.jar`，放入 Minecraft **1.20.1 Forge** 实例的 `mods` 文件夹。使用 Java 17，Forge 47.4.x。首次安装或升级时关闭游戏，并备份旧配置与记忆。

模组显示名和内部 ID 暂沿用 Logistics Companion / `logistics_companion`，公开项目名采用 MineCompanion。

## 功能

- 游戏内配置模型接口，支持 API 与本地 Harness 两种规划入口。
- 人工、AI 接管与协同操作，观看模式、本地对话和任务过程显示。
- 可配置权限、授权成员、审批和急停。
- 已加载世界认知、地点及容器库存记忆，通用技能与具体世界记录分别保存。
- 背包准备、箱子取放、搬运、基础合成、熔炼、农作与采集。
- 内嵌 Baritone 导航，挖路和垫脚可分别设置。
- 原版 GUI 槽位、按钮、文本与选择操作，以及可选 JEI、AE2 等客户端适配。
- 原有近战、自卫与弓箭执行功能。

### 0.8.1 新增

区域光照查询、暗处插普通火把、按方块 ID／标签搜索，以及岸边自动钓鱼初版。补充切石／织布选项、附魔条件、流体射线、到达结果与物品使用核验。

## Alpha 状态

当前版本为 **0.8.1 / field-skills-20261112-01**。Windows 开发环境编译通过，110 个 JUnit 测试类、585 项测试通过。

已有实机记录覆盖箱子和熔炉、交易、酿造、弓弩、Baritone 长距等场景。0.8.1 新照明、钓鱼及交互修改仍需实机验证。模组兼容性取决于版本、菜单同步方式和服务器规则。

钓鱼初版需要预先站稳岸边、面向水面并准备鱼竿。照明初版为普通火把的区域任务，后续继续扩展随行照明、自动补料和复杂地形恢复。

## 快速开始

1. 进入世界，使用游戏内按键设置查看伙伴当前绑定。默认 F8 打开面板，F9 急停，F6 观看，Home 本地对话。
2. 在面板配置模型接口，或启用本机 Harness。填写自己的密钥，选择允许的权限。
3. 明确开启 AI 接管，从普通箱子搬运和少量材料开始测试。
4. 请求与结果可在本地界面查看。物品数量和操作成功按实际客户端同步数据核对。

## 外部 Harness

外部助手通过 `harness-client.py` 连接模组。Python 3.10+，仅使用标准库。

```powershell
python -X utf8 harness-client.py --connection "你的游戏目录/config/logistics-companion/harness-connection.json" status
python -X utf8 harness-client.py --connection "你的游戏目录/config/logistics-companion/harness-connection.json" connect
python -X utf8 harness-client.py --connection "你的游戏目录/config/logistics-companion/harness-connection.json" capabilities
```

连接信息由模组在启用后生成，属于本机凭据。Agent 根据当前 `capabilities` 和工具参数执行动作，网络超时后查询原请求回执。

照明可调用 `light_survey`、`illuminate_area`，搜索用 `find_blocks`，钓鱼用 `fish`。连续任务支持 `task_submit/task_status/task_result/task_cancel`。

## 从源码构建

需要 JDK 17、Python 3.10+ 和联网下载构建依赖。

```powershell
python setup-jei-api.py --download
.\gradlew.bat build
```

Linux/macOS 可使用 `bash gradlew build`。产物位于 `build/libs`。开发者现有 Windows 离线工具链已通过构建，公开仓库的全新机器联网构建仍待独立验证。

## 反馈问题

请提供 Minecraft／Forge／模组版本、构建标识、实际操作、预期与实际结果，并附经过脱敏的相关日志。测试准备与正式生产结果分别说明。

## 许可证与来源

项目主体使用 [MIT](LICENSE)。内嵌 Baritone 使用 LGPL，配套源码与许可证保存在 `vendor/baritone`。部分适配来源与美术生成记录见 [第三方声明](THIRD_PARTY.md) 及资源中的 provenance 文件。第三方部分遵守其各自许可证。

模型接口和外部 Harness 的数据处理由对应服务决定。请根据服务器规则使用客户端自动化能力。
