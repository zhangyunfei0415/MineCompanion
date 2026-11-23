# MineCompanion

**让 AI 在 Minecraft 里像另一位玩家一样，和你一起生存。**

MineCompanion 是面向 **Minecraft Java 1.20.1 · Forge 47.4.x · Java 17** 的客户端模组。AI 操作安装模组的客户端账号，可以陪你探索，也能接手采矿、熔炼、补给、基地驻守与挂机生产。双人陪玩时，让主号和伙伴账号进入同一个世界，并支持同目录双开零串台与伴侣状态/地图实时联动。

[下载 0.8.7 正式版](https://github.com/zhangyunfei0415/MineCompanion/releases/tag/v0.8.7) · [快速开始](#快速开始) · [Harness 接入](#接入外部-harness) · [版本与贡献](VERSION-HISTORY.md) · [发布档案](docs/releases/README.md) · [English](#english)

## 它能帮你做什么

| 玩法 | 示例 |
| --- | --- |
| **主动开图、带路回家与三维记忆** | 新增 `explore_roam` 迷宫走廊优先主动开图与 5 秒脱困、**「带玩家回家」**（`explore_return(guide_player=true)` / `带我回家` 沿 3D 路线领路并等待重聚）；授权后说 `@小勤 跟着我`，支持涉水、爬梯、可控助跑跑酷与活板门/按钮铁门通行；自动构建按世界/维度隔离的 3D 探索拓扑图与可缩放分层地图。 |
| **女仆式值守、零阻塞与智能挂机** | `纯待命` / `驻守基地` / `跟随与守护`（可绑定队友目标） / `外出探索采集` 四档值守与自主切档；支持后台值守零 `[BUSY]` 抢占、`STANDBY` 饥饿阈值自动进食与 `auto_action` 内置挂机连点引擎（刷石机连挖换镐、刷怪塔清怪、曲柄长按与筛网/骨粉连点）。 |
| **原版、后室与陌生模组兼容** | 采矿、熔炼、合成、农作、钓鱼与箱子取放；适配 `sophisticatedbackpacks` 精妙背包潜行放收与标准 `ChestMenu`（如《精致家具》柜子），支持 AE2 `ae_deposit` 自动找槽、`move_inventory(select=true)`、FTB 任务书安全一键领奖与易变 NBT 剥离。 |
| **边玩边协同与星灵立绘** | **F8** 工作台、**Home** 对话、**F4** 限帧静音节能与 **F9** 急停；支持 **DeepSeek 鲸娘 / ChatGPT 龙娘 / Gemini 星灵** 三角色 × `MOE / MC` 双画风实时 8 态立绘。 |

## 快速开始

1. 从发布页下载 **`MineCompanion-0.8.7.zip`**。关闭游戏，将其中的 `logistics-companion-0.8.7.jar` 放进对应实例的 `mods` 目录。升级时先在其他目录备份旧 JAR 和配置。
2. 进入世界，按 **F8** 打开工作台。配置模型 API，或启用本机 Harness。开启 AI 接管后，按 **Home** 打开本地对话。
3. 可直接在 F8 工作台「跟随与陪同」启用女仆值守或一键挂机（刷石机/刷怪塔/曲柄/筛网），双人陪玩时主号发 `@小勤 跟着我` 即可同行。
4. 通过实际位置、菜单和库存变化确认结果。服务端权限与整合包变化会影响能够执行的操作。

## 接入外部 Harness

启用本机 Harness 后，使用 Python 3.10+ 运行仓库中的 `harness-client.py`。把路径换成伙伴的实际游戏实例，连接文件由模组生成，按本机凭据保管。

```powershell
$connection = '你的游戏目录/config/logistics-companion/harness-connection.json'
python -X utf8 harness-client.py --connection $connection status
python -X utf8 harness-client.py --connection $connection connect
python -X utf8 harness-client.py --connection $connection capabilities
```

根据当前 `capabilities` 和工具说明调用。网络超时后，先查询原请求回执。用 `list_library` 查可用技能，通过 `run_library_skill` 执行。共享包里的轨道 A 需要拿到生产成功回执，轨道 B 复用还会检查晋级状态及当前环境。

## 0.8.7 正式版与贡献

**0.8.7 整合 `rc1`–`rc3`、全仓 79 项 P0/P1/P2 安全稳健性加固及主动探图迷宫收口重构。** 项目维护者与 Hydrogen 完成多轮《阈限工业》1.20.1 实机定位与回归验收并主导版本规划；Muse 确立主动探图核心架构；awsquick 完成全仓 379 个 Java 类与资源深度审阅及 79 项 P0/P1/P2 修复；Gemini 完成主动开图与「带玩家回家」落地、`rc1`–`rc3` 兼容集成、迷宫走廊优先重构及正式构建。 [完整版本与贡献记录](VERSION-HISTORY.md) · [0.8.7 发布说明](docs/releases/RELEASE-0.8.7-20261124.md)

本版通过离线构建和 **139 个测试套件 / 718 项自动化测试**（0 失败 / 0 错误 / 0 跳过）。

## 开发与反馈

需要 JDK 17、Python 3.10+ 和可下载构建依赖的环境。

```powershell
python setup-jei-api.py --download
.\gradlew.bat build
```

Linux 或 macOS 可运行 `bash gradlew build`。产物在 `build/libs`。离线构建已在现有 Windows 工具链验证。

反馈问题时，请附游戏、Forge 和模组版本、实际操作、预期结果以及脱敏日志。 [提交或查看 Issue](https://github.com/zhangyunfei0415/MineCompanion/issues) · [开发更新日志](CHANGELOG.md)

项目主体采用 MIT，内嵌 Baritone 采用 LGPL。对应源码与许可在 `vendor/baritone`，其他素材及引用来源见第三方声明。 [项目许可证](LICENSE) · [第三方声明](THIRD_PARTY.md)

## English

MineCompanion is a client-side AI player companion for **Minecraft Java 1.20.1 on Forge**, running on Java 17. **0.8.7** is the official release introducing corridor-first active outbound exploration (`explore_roam`), "guide player home" (`explore_return(guide_player=true)`), indoor safe-cell fallback (`go_to`), Sophisticated Backpacks / generic `ChestMenu` / AE2 item-matched deposits, configurable `STANDBY` auto-eat, teammate `ESCORT_GUARD`, and 79 full-repo P0/P1/P2 safety and stability fixes (139 test suites / 718 tests passing). [Download 0.8.7](https://github.com/zhangyunfei0415/MineCompanion/releases/tag/v0.8.7) · [Version history and credits](VERSION-HISTORY.md)
