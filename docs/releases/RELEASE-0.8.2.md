# 0.8.2 配置、交付与测试说明

构建标识为 `cloud-local-decision-20261114-01`，Forge 1.20.1，Java 17 字节码。主工程为 `D:\Companion mod`。本轮延续原工具链与 Baritone 内嵌依赖。

## 已实现

- 模型与连接页面新增局部判断层，位置可切换 OFF、CLOUD、LOCAL。两个位置分别保存 URL、模型及 API Key，切换不会把本地请求自动转给云端。
- 云端支持 Google Decisions、TypeSafe Jev、OpenAI Chat Completions 兼容接口。本地支持这三种协议，限回环地址，适用于已启动的 Ollama、vLLM 等兼容服务或 DJev 风格服务。仅提供连接能力，模型需自行部署。
- 新增 `decision_select`，提供 2–12 个现有连续任务候选，默认只返回选择。面板打开自动提交且调用参数 `submit=true` 时，通过 TaskGateway 和原权限执行一次，返回 task_id。候选由规划模型或 Harness 提供。
- 判断请求异步进行，返回后核对世界、维度代次、主目标、连接配置、暂停及中间写动作。失效结果不会执行。状态提供请求数、错误数、服务端报告输入 token、最近延迟和结果。
- 选择时附带当前生命、饥饿、背包、位置及近期同状态失败。自动提交任务按世界记录关联现场指纹。相同位置和库存下再次选择已失败任务会返回 `DECISION_REPEAT_BLOCKED`，供规划者换候选或复查现场。
- 导航权限统一由 NavigationPermissions 解析，FULL/完全游戏控制可开路；显式“导航仅绕行”优先禁止破坏与搭路。采矿的 `terrain=detour` 继续保持绕行。权限页集中提供开关，permission_status 展示生效值与配置值。
- 新增 pause_control、resume_control。前者暂停并保留授权，后者可恢复 Harness 自己的暂停。恢复人工暂停需要玩家预先打开相应权限。F9 撤权后需要重新在游戏内授权。status 返回暂停来源、恢复要求及提示。
- 新增 inspect_landing，检查已加载区域的玩家碰撞空间、支撑与危险。可选安全传送检查默认开启，对可解析的原版坐标 tp 预检；未加载目的地按未知拒绝，可在权限页关闭。
- fill/setblock 触及已加载区域的容器、刷怪笼和末地门框架等关键方块时，默认要求显式 confirm=true，可在权限页关闭。未知区域不宣称已检查。
- 可识别的自目标 item replace 命令对目标槽位 ID、数量和指定 NBT 进行服务器快照回读，附实际完整物品数据。无法识别或无法确认仍返回未核实。
- look_at 支持 entity_id 或完整 XYZ，缺参数返回明确错误，实体交互绑定 UUID 防止 ID 复用。
- 结构化 reason_code 优先于文本推断，采矿返回 completed_count、termination_reason，时间预算结束带 TIMEOUT 分类。查询返回当前扫描范围内的结果截断数量。

## 离线检查

使用现有 build-local.ps1 离线构建。最终构建及测试数量以交付目录 validation.json、JUnit XML 与 build-0.8.2.log 为准。本轮新增协议、候选 ID、错误选项、本地/云端配置隔离、地形权限优先级及结构化超时原因的回归测试。

## 配置判断模型

F8 → 设置 → 模型与连接，找到“局部判断层”，选择位置和协议，再填写完整请求 URL、模型 ID 与独立 API Key，点击保存。

| 服务 | 协议 | URL 示例 | 模型 |
|---|---|---|---|
| Google | GOOGLE | https://generativelanguage.googleapis.com/v1beta/decisions | gemini-decision-1 |
| TypeSafe | JEV | https://api.typesafe.ai/v1/systemone | jev-latest |
| Ollama 兼容 | OPENAI | http://127.0.0.1:11434/v1/chat/completions | 本机实际安装的模型 ID |
| vLLM 兼容 | OPENAI | http://127.0.0.1:8000/v1/chat/completions | 本机服务公开的模型 ID |
| 本机 DJev 类服务 | JEV | 本机实际提供的完整 systemone URL | 服务实际支持的模型 ID |

本地 OpenAI 模型需能按要求输出 `{"choice":"候选ID"}`。服务若公开的路径不同，按真实服务填写。程序不会启动模型服务、下载权重或代为开通账单。API Key 在本机配置中保存，字段显示为掩码，交付包使用仓库源码，无运行配置与凭据。

## Harness 操作示例

通过现有 harness-client.py 的 action 通道调用，外层 request_id、epoch、scope 与当前连接要求保持一致。先用 decision_status 查看位置。

decision_select 的 args 可使用下例。第一轮仅做建议，不开启自动提交。

```json
{
  "goal": "准备少量木制品",
  "submit": false,
  "candidates": [
    {
      "id": "make_sticks",
      "description": "已有木板，先制作一批木棍",
      "tool": "craft_item",
      "args": {"item": "minecraft:stick", "count": 4}
    },
    {
      "id": "make_table",
      "description": "已有木板，需要一个工作台作为后续生产设施",
      "tool": "craft_item",
      "args": {"item": "minecraft:crafting_table", "count": 1}
    }
  ]
}
```

返回 choice 证明模型选择已解析，实际游戏动作由提交后的 task_status/task_result 与现场变化验收。自动提交前确认原任务没有运行，所需权限、材料与设施已满足。

## 最短游戏测试

1. 关闭游戏后替换单个伙伴 JAR，保留旧包在 mods 外作为回滚。进入指定测试世界后开启 AI 与 Harness。
2. 权限测试使用现有矿区，准备石镐、食物、火把、交付箱及 4 块挡路石头。先 FULL + 允许修改地形测试 M03，再打开仅绕行确认开路被拒绝，terrain=detour 仍可工作。
3. 调用 pause_control，观察 status 的 paused_by 与恢复提示，调用 resume_control。再由玩家人工暂停，分别测试允许/禁止 Harness 恢复。F9 后确认动作与恢复均不再获准。
4. 用 inspect_landing 检查已知地面、墙内和悬空位置。先保持安全传送开启，确认后两项被拒绝且命令未发送。远处未知落点按界面提示处理。
5. 模型测试准备 16 木板和一个工作台即可，调用上方建议例子。分别切换云端、本地，验证请求实际进入所选服务。再打开自动提交，使用新 request_id 和 submit=true，核对 task_result 和实际产物。
6. 接口返回较慢时暂停或切换位置，确认旧返回标记 DECISION_STALE。配置错误或服务未启动时应显示错误，无自动转云端。使用新的 request_id 进行下一次独立选择。
7. 用已授权测试指令替换副手或某快捷栏物品，检查 verified 及 actual_stack。用少量物品测试，避免覆盖已有装备。
8. 查询密集方块，确认 truncated_cells 的含义为当前扫描范围内未列出的匹配结果，不包含未加载格数。

## 尚待验证与后续范围

- Google、Jev、本地真实服务的可用性、模型选择质量、网络延迟、费用以及自动执行闭环，尚未在本轮实机验证。
- 该版本完成候选选择与可选提交入口。全自动从世界生成候选、周期调度多个阶段、完整技能收益估算，以及多模型相同场景 A/B 报告仍在后续工程范围。
- 沿用已有背包交换、鼠标物品收好、AimGate 分帧转向与死亡恢复。它们在本轮没有全面重写，DSH 原报告中的每个复杂机器、移动实体、满包和死亡子场景仍需复测。
- 同状态失败识别采用位置、库存与任务指纹，未覆盖完整世界因果状态。遇到合理的新现场仍被阻止时，应重新观察并更换候选或阶段。
- 传送安全检查目前覆盖可解析的原版坐标目标，插件的 home/tpa 等仍需到达后观察。支撑检查针对当前玩家尺寸，水中落点按危险处理。
- 当前输入 token 统计依赖服务返回用量字段。完整费用账单、置信度校准和 p50/p95 汇总尚未加入。

本轮交付用于继续 Harness 实测，实际安装和游戏启动由用户安排。
