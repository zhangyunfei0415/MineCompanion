# MineCompanion

**让 AI 在 Minecraft 里像另一位玩家一样，和你一起生存。**

MineCompanion 是面向 **Minecraft Java 1.20.1 · Forge 47.4.x · Java 17** 的客户端模组。AI 操作安装模组的客户端账号，可以陪你探索，也能接手采矿、熔炼、补给和整理。双人陪玩时，让主号和伙伴账号进入同一个世界。

[下载 0.8.5rc1](https://github.com/zhangyunfei0415/MineCompanion/releases/tag/v0.8.5rc1) · [快速开始](#快速开始) · [Harness 接入](#接入外部-harness) · [版本与贡献](VERSION-HISTORY.md) · [发布档案](docs/releases/README.md) · [English](#english)

## 它能帮你做什么

| 玩法 | 示例 |
| --- | --- |
| **陪伴探索** | 授权后说 `@小勤 跟着我`。伙伴会跟住指定玩家，根据陪伴预设尝试过门，并在重新看见同一玩家时继续跟随。 |
| **原版生存与后勤** | 采矿、熔炼、合成、农作、钓鱼和箱子取放，也可以把采铁、归仓、制火把和返矿照明串成一条流程。 |
| **陌生模组与技能记忆** | 读取机器槽位、Tooltip 和进度，把跑通的操作整理成可分享的 `.mcskill.json`。换整合包后的界面适配保存在本机。 |
| **边玩边协同** | 可在玩家、AI 接管和协同操作间切换。查看任务进度，设置授权成员和操作范围，必要时按 **F9** 急停。 |

## 快速开始

1. 从发布页下载 **`MineCompanion-0.8.5rc1.zip`**。关闭游戏，将其中的 `logistics-companion-0.8.5rc1.jar` 放进对应实例的 `mods` 目录。升级时先在其他目录备份旧 JAR 和配置。
2. 进入世界，按 **F8** 打开工作台。配置模型 API，或启用本机 Harness。开启 AI 接管后，按 **Home** 打开本地对话。
3. 先试一次小任务，例如从指定箱子取 3 个粗铁并熔炼。如果是双人陪玩，在伙伴工作台授权主号，再用主号在游戏聊天发 `@小勤 跟着我`。可用 `@小勤 停止跟随` 结束这次同行。
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

## 0.8.5rc1 与贡献

**0.8.5rc1 沿用 0.8.5 G4Neon。** Gemini 完成 G4Neon 界面和开放技能记忆基础，OpenAI Codex 完成轨道校验、回归测试和本次发行。项目维护者确定玩法、验收方向和发布范围，DeepSeek Harness（DSH）参与前序实机验收与问题反馈。 [完整版本与贡献记录](VERSION-HISTORY.md) · [候选版用法与验收](docs/releases/RELEASE-0.8.5-RC1-G4NEON-20261121.md)

本版通过离线构建和 **673 项自动化测试**。新界面、原版熔炉技能及跨整合包复用仍待实机验收。

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

MineCompanion is a client-side AI player companion for **Minecraft Java 1.20.1 on Forge**, running on Java 17. Use a model API or a local harness to automate survival chores, follow an authorized player, or reuse portable skills for modded interfaces. **0.8.5rc1** builds on the Gemini G4Neon redesign and skill memory system, with runtime checks for skill verification and reuse added by OpenAI Codex. [Download the candidate](https://github.com/zhangyunfei0415/MineCompanion/releases/tag/v0.8.5rc1) · [Version history and credits](VERSION-HISTORY.md)
