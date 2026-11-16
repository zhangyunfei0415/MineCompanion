# 0.8.2 判断能力补全交付

构建标识为 `decision-capabilities-20261115-01`，版本继续为 0.8.2。依据 `D:\Companion mod\docs\JAR工程需求书-20261115.md` 开发，纯客户端 Forge 1.20.1，Java 17 字节码。

## 接口与分工

status、observe_compact、decision_select、task_status/task_result 与 action 保留已有请求及返回结构，增加字段。status、observe_compact、decision_status 返回 `contract_version=1`。候选仍由外部生成器或主规划者提供，本轮扩展执行能力与诊断。

候选数量继续遵守原有 2–12 条限制。`submit=false` 只请求建议，`submit=true` 还要求面板已开启自动提交。实际动作继续通过 Agent.execute 权限与执行器，菜单仍使用当前 gui_view。

## 需求对应

| 需求 | 实现与核查 |
|---|---|
| R30 候选白名单 | TaskActions 统一任务入口和判断候选列表，保留原 13 项，增加存取、移动、进食、实体/方块交互、拆放、GUI 等动作；排除判断递归与任务递归。调用模型前校验，decision_status.supported_candidate_tools 和错误的 suggested_alternatives 提供完整合法项 |
| P88 死亡恢复 | 自动策略沿用 RESPAWN_IDLE/REASSESS，正常发送重生请求后待命或重评。PAUSE 改为保留授权与连接，等待人工重生和明确继续。增加死亡阶段及时间字段，F9/代次改变使原恢复失效 |
| R27 暂停诊断 | status 与 observe_compact 均返回 paused_by、pause_reason_code、resume_requirement、resume_hint、can_resume、harness_resume_human_pause。生命守卫返回 auto_after_guard。停止通道独立，task_cancel 在暂停/忙碌中可用 |
| R19/R25/R32 落点 | 保留原落点预检，碰撞盒向内收缩 0.0001 格，避免边界接触被算进地板；扫描整个玩家包围盒与支撑范围内的液体/火/门等危险。inspect_landing 不填坐标即检查精确当前位置，增加 AT_CURRENT_POSITION 上下文，未知区域保持未知 |
| R34 HTTP | 判断客户端明确使用 HTTP/1.1，回环套接字测试检查 4349 字节正文完整、无 h2c Upgrade |
| R35/R36 错误 | 新增 last_failure，记录时间、HTTP 状态、原因码、端点和最多 200 字符脱敏摘要。区分超时、网络和 HTTP 错误，后续成功仍保留上次失败 |
| R31 任务投影 | latest.task 按原 task_id 从当前世界 TaskGateway 实时投影。结束时保存自己的 current_phase，提交时初始化状态，避免继承上一个任务的文字。旧世界任务标明 other_world |
| R29 查询裁剪 | 保留已有 truncated_cells，补 count_exact 与 suggested_radius，区分列表截断和未加载范围 |
| R33 配置重载 | 新增 config_reload，只重载判断层连接、位置和自动提交配置，保留游戏授权、租约及其他设置；新增面板重载按钮。无效文件保留当前配置，未完成旧判断失效 |
| P74 满包拆除 | 源码原已允许 break_block 默认 allow_ground，本轮明确枚举校验，attack_block 同步支持 drop_policy；require_capacity 继续要求容量。满包拆除后报告可能有掉落留地，不把未知掉落当成已入包 |
| P85 自绘选项 | 新增 gui_action，与已有 vanilla_menu_select 共用语义 index。inspect_gui.self_drawn_controls 列出织布/附魔/交易/切石等可用选项和视图号；支持列表中的屏外选项，无需模拟鼠标拖动 |

另修正 MCP 的旧版本号 0.7.6，发现文件增加当前 build，便于外部判断是否载入正确构建。关键方块保护扫描改用归一化的两个端点，覆盖反向 fill 坐标。

## 测试与证据

离线构建、测试数量和提交号以交付目录 `validation.json` 为准。本轮新增白名单及原 13 项回归、完整合法清单、错误脱敏、容量策略、死亡恢复授权条件，以及本机 HTTP/1.1 正文传输测试。

这些属于源码与离线证据。Google、Mapika 本地服务和自动熔炼成功属于上一轮 DSH 的实测记录。本次新构建还需要 Harness 游戏验收，重点是自动重生、落点边界和织布产物。

## 给外部判断层的接入变化

1. 游戏加载新 JAR 后读取一次 status 和 decision_status。确认 build 为 `decision-capabilities-20261115-01`。
2. 从 supported_candidate_tools 缓存本次合法工具集合。GUI 候选参数来自最新 inspect_gui，世界动作参数来自当前观察。
3. 调用结构继续使用候选的 id、description、tool、args。新动作和原连续任务均经原 TaskGateway 返回 task_id。
4. decision_status.latest.task 现在是实时任务投影。仍可按 task_id 单独调用 task_status/task_result。
5. 模型失败时读取 last_failure，网络错误允许等待服务恢复后发起新的判断。动作结果不确定时继续查原任务回执。
6. 运行中手动编辑本机 settings.json 后调用 config_reload，可切换 CLOUD/LOCAL。reload 前避免其他会保存配置的操作覆盖文件修改。

以下例子只观察和汇报，可先用 submit=false 验证候选支持，再按需要测试自动提交。

```json
{
  "goal": "观察现场并选择下一步",
  "submit": false,
  "candidates": [
    {"id": "observe_here", "description": "刷新当前背包和现场摘要", "tool": "observe_compact", "args": {}},
    {"id": "report_here", "description": "汇报已准备好下一轮测试", "tool": "report_progress", "args": {"message": "已准备好下一轮测试"}}
  ]
}
```

## 最短验收场地与操作

- 复用已有安全平台、箱子、床和工作台。增加织布机、白旗帜、任意染料，以及一处三格高实心墙和一段悬空边缘。
- 原版单人有测试指令权限时，可用指令准备少量材料。准备动作与生产结果分别核验。
- 候选测试先观察/汇报，再使用真实交付箱参数测试 deposit，确认 calls 增加且原 TASK_UNSUPPORTED 阻塞解除。
- 死亡测试先在面板选择“正常重生后待命”或“正常重生后重评原目标”，设定床位，在可回滚存档内测试。核对死亡阶段、原任务失效、重生后授权与租约。随后测试 PAUSE，以及等待重生时 F9 急停。
- 暂停测试调用 pause_control → status → resume_control，再测玩家人工暂停且允许/不允许外部恢复的两种配置。
- 落点测试先无参数 inspect_landing，再分别检查墙内、虚空、门内与未加载坐标。坐标传送需 safe_teleport=true，实际阻止与否按 sent 字段确认。
- 织布测试打开织布机，放入旗帜与染料，读取 self_drawn_controls，用最新 gui_view 和图案 index 调用 gui_action，再刷新菜单核对结果槽和后续取出物品。
- 错误测试可使用本机服务人为返回 400、422、503，再停止服务测试连接错误。读取 last_failure，恢复服务成功后确认旧失败信息仍在。
- 满包测试显式 drop_policy=allow_ground 拆除一株草或一支火把，核对方块已消失；掉落与拾取另查。require_capacity 不应被误解成相同策略。

## 边界与待测

- PAUSE 需要人工重生；自动重生要由玩家先选择自动策略。F9 不自动恢复。
- 落点判定依据已加载客户端碰撞。当前位置仍在实心物内或确有危险时 safe 会为 false，不能用当前位置特例宣称安全。
- gui_action 覆盖现有原版菜单模型，未知模组自绘界面仍需对应适配。
- config_reload 有意限定判断层，避免在后台变更游戏权限与连接认证。
- 已有满包留地和关键方块保护在旧源码中存在，报告中的“未落地”需要通过新构建的状态与开关复核。本次文档逐项保留这一差别。
- 本轮没有调用真实付费模型、进入存档、安装或启动游戏。游戏侧验收由用户安排后继续。
