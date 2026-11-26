# MineCompanion

**让 AI 在 Minecraft 里像另一位玩家一样，和你一起生存。**

MineCompanion 是面向 **Minecraft Java 1.20.1 · Forge 47.4.x · Java 17** 的客户端模组。AI 操作安装模组的客户端账号，可以陪你探索，也能接手采矿、熔炼、补给、基地驻守与挂机生产。双人陪玩时，让主号和伙伴账号进入同一个世界，并支持同目录双开零串台与伴侣状态/地图实时联动。

[下载 0.8.8 正式版](https://github.com/zhangyunfei0415/MineCompanion/releases/tag/v0.8.8) · [快速开始](#快速开始) · [Harness 接入](#接入外部-harness) · [版本与贡献](VERSION-HISTORY.md) · [发布档案](docs/releases/README.md) · [English](#english)

## 它能帮你做什么

| 玩法 | 示例 |
| --- | --- |
| **统一三维整地、筑造与灌溉农田** | 全新 `terraform_preview` / `terraform_run` / `terraform_control` 引擎支持扁平参数 `flatten`（落砂柱与方块实体防护）、`fill`（实心/空心/四壁/补空与活体礼让）及 `farm_grid`（9×9 分段水格、含水上半砖封盖、`pool_2x2` 自动打水与防踩踏退后开垦即播），兼容创造与 `/fly` 安全降落并支持 `pause`/`resume` 断点续建。 |
| **蓝图建造、3D 脚手架与破坏回滚** | 纯 Java `.litematic` / `.nbt` 蓝图解析、旋转镜像与 BOM 核算；自动搭建真脚手架或固体阶梯并严格 LIFO 退步拆架回收掉落物；内置 500 条诚实版破坏回滚日志 `RollbackJournal` 与独立 `RollbackJob` 物理撤销。 |
| **立体寻路、主动开图与团队交互** | 零 GC `GridSearch` 与竖井立体探索图（`explore_roam` / `explore_return(guide_player=true)`「带玩家回家」）；`@小勤` 两阶段提及收件箱、非阻塞 `wait_events` 与 `GUEST` 只读硬沙箱；四档女仆值守与内置挂机连点。 |
| **模组精细交互、声影避险与工作台** | 声音感知 0ms 避险与昼夜/幻翼监视、`policy_whitelist` 五类自维护白名单、Storage Drawers 局部 UV 精确命中（`hit_u`/`hit_v`/`sub_slot`）、`sophisticatedbackpacks` 与 AE2 自动找槽；F8 方案 A 自适应工作台（一键亮/暗切换）、F4 节能限帧与 F9 急停。 |

## 快速开始

1. 从发布页下载 **`MineCompanion-0.8.8.zip`**。关闭游戏，将其中的 `logistics-companion-0.8.8.jar` 放进对应实例的 `mods` 目录。升级时先在其他目录备份旧 JAR 和配置。
2. 进入世界，按 **F8** 打开工作台。配置模型 API，或启用本机 Harness。开启 AI 接管后，按 **Home** 打开本地对话。
3. 可直接在 F8 工作台「蓝图与建造」「历史与回滚」「跟随与陪同」启用蓝图施工、三档地形开垦预设、女仆值守或一键挂机，双人陪玩时主号发 `@小勤 跟着我` 即可同行。
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

## 0.8.8 正式版与贡献

**0.8.8 汇总 `rc1`–`rc17` 迭代成果，正式上线统一三维整地/筑造/灌溉开垦系统、纯 Java `.nbt`/`.litematic` 蓝图与 3D 脚手架 LIFO 拆架、诚实版破坏回滚日志、声音避险与自维护白名单、Storage Drawers 局部 UV 精操、多人 `@` 收件箱与工作台方案 A 重构。** 感谢项目维护者、Muse、Hydrogen、Codex/Carbon/Neon/DeepSeek、Astra (`gpt-6.2-astra`)、Claude Opus 与 Gemini 的分工协同与多轮实机验证。 [完整版本与贡献记录](VERSION-HISTORY.md) · [0.8.8 发布说明](docs/releases/RELEASE-0.8.8-20261127.md)

本版通过离线构建和 **160 个测试套件 / 799 项自动化测试**（0 失败 / 0 错误 / 0 跳过）。

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

MineCompanion is a client-side AI player companion for **Minecraft Java 1.20.1 on Forge**, running on Java 17. **0.8.8** is the official release introducing a unified 3D terraforming/fill/irrigated farm reclamation engine (`terraform_preview`/`terraform_run`/`terraform_control`, `/fly` safe landing, 9×9 segmented waterlogged-slab irrigation, `pool_2x2` auto-refill, and checkpoint resume), pure-Java `.nbt`/`.litematic` blueprint building with 3D scaffolding & LIFO drop-collecting teardown, 500-entry honest rollback journal, 0ms sound danger reflex & daytime/phantom watch, agent-maintained `policy_whitelist`, Storage Drawers sub-slot UV targeting, two-phase `@` mention inbox with `GUEST` sandbox, and Workbench Studio light/dark polish (160 test suites / 799 tests passing). [Download 0.8.8](https://github.com/zhangyunfei0415/MineCompanion/releases/tag/v0.8.8) · [Version history and credits](VERSION-HISTORY.md)
