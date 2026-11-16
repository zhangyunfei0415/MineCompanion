# R44–R47 方案、改动与测试

版本为 0.8.2，build 为 `smelt-recovery-20261117-01`。依据 `D:\Companion mod\docs\JAR修复需求-R44-R47-20261115.md` 开发，沿用纯客户端 Forge 1.20.1 / Java 17。

## 本轮方案

1. 先处理 R45/R46，把燃料与输入的缺口拆开，在任何投料前完成预检，生产流程向外保留子回执的材料证据。
2. R44 统一菜单观察和 GUI 槽位操作的上下文，使用原 MenuContext 校验同步、页内槽位、游标与视图失效。
3. R47 保留原始原因码，额外提供稳定失败分类、阻挡坐标和恢复建议。
4. 针对燃料数量与本批物品守恒增加离线回归，复用现有菜单校验测试，最后编译并保留源码。

需求书背景中的自动解析 Y、区域扫描性能、地点失效与缓存等长期项另行推进。

## R45 物品归属

熔炼终止回执增加 recovered_items、recovery_target、recovery_verified、recovery_action 和 evidence。证据包含输入的初始/当前总量、炉内输入、鼠标持物、背包输入、观察到的产物与无法解释的缺口。

当前采用保留现场策略，recovery_action 为 preserved_not_moved。材料留在实际输入槽、背包或鼠标，停止时不为了退料额外发送点击。取消 task 的回执和生产检查点也保留 recovery。菜单被关闭或同步不足时返回未知，recovery_verified=false。

本批剩余量扣除了任务前已有的同种材料，避免旧库存被当成退回数量。熔炼已经消耗并产生的产物按配方产量折算；发现缺口返回 ITEMS_UNACCOUNTED 和数量。客户端只有当前观察时，不能据缺口直接断言服务端已永久删除物品。

原报告中的“3 粗铁消失”尚未在本轮游戏中重现，本轮实现了投料前阻断和中止归属证据。是否完全消除该现场问题需要复测，尤其要检查鼠标持物和实际选中的炉子。

## R46 燃料

按目标炉型实际燃烧时长计算：炉内已有燃料、背包可用燃料、仍需添加量和实际缺口分别统计。炉内 12 木板足够时，背包无需再提供同类燃料。输入材料本身也可作燃料时，预留本批输入，不重复计入燃料。

回执包含 fuel_id、fuel_available、fuel_required、fuel_in_furnace、fuel_in_inventory、fuel_to_add、fuel_missing、fuel_ticks_per_item、accepted_fuels_observed，以及独立的 input_available/input_required。输入不足返回 MISSING_INPUT，燃料不足返回 MISSING_FUEL。

配置 source 或本世界生产补料来源时，缺燃料会实际打开普通补料箱，核对当前同步内容，选择可用燃料后有界取料一次，再重新打开原炉核算。历史箱内库存不直接算作当前可用。取料仍经正常权限和实际数量验证。

普通木板、原木、煤、木炭、煤炭块、烈焰棒等按 Forge 当前炉型规则计算。带 NBT、耐久或返回容器的燃料仍沿用已有排除策略。

## R44 菜单观察

inspect_menu 在物品界面里同时捕获 GuiContext 上下文，返回相同值的 view_id 和 gui_view。gui_slot 可直接使用它观察到的槽位，继续验证原菜单观察号、布局、同步、目标槽与鼠标状态。

分页观察只授权该页实际观察过的槽位；换菜单、槽位变化或旧视图仍会拒绝。inspect_gui 继续可用。

## R47 采矿恢复

记录实际触发 guard 的 BlockPos，失败回执增加 recovery.blocked_cell、blocked_by、suggestions、replay_same_action=false，并增加 failure_family=MINING_NO_PROGRESS。

建议包括检查阻挡格与液体、改用其他露头或安全站位、terrain=detour，以及在独立核对后补支撑。建议不会自动修改地形或重放原动作，原 reason_code 和 termination_reason 继续保留。

## 验收场地与步骤

复用普通熔炉、补料箱、工作台和安全矿区。准备 12 粗铁、16 木板、少量煤，保留至少四个空背包格。测试前记录炉子坐标、输入/燃料/输出三槽和鼠标物品。

1. 无燃料、无 source，调用 smelt_item 熔炼 3 粗铁。应在投料前返回 MISSING_FUEL，输入总数不变，回执含 recovered_items。随后检查背包、鼠标和所选炉子。
2. 炉内 12 木板，背包 16 木板和 3 粗铁，执行同一任务。核对 fuel_to_add=0、输入与燃料统计，并检查实际新增 3 铁锭。
3. 目标炉无燃料、背包无燃料，指定普通补料箱内放木板。确认实际打开取料后才重新投料；箱子记忆过期时明确失败，不盲投。
4. 投料期间和等待产物期间分别 task_cancel。读取 task_result.recovery，核对鼠标、原炉输入/输出与背包总量，先查现场再决定后续操作。
5. inspect_menu 后直接把 view_id 传入 gui_slot.gui_view，操作已观察的炉子槽位；再确认菜单变化或分页外槽位仍被拒绝。
6. 在采矿目标下方保留支撑或靠近液体触发安全阻挡。检查 recovery 的坐标、原因和建议，外部按失败分类换目标，避免同格反复重试。

## 检查状态

最终构建与 JUnit 数量见交付目录 validation.json 和 build-0.8.2-smelt.log。新增燃料合并、已有燃料足够、输入预留、燃料缺口和批次物品守恒回归测试。

本轮游戏内验收待安排。菜单共享编号、服务器投料与退回行为、异步玩家干预和真实缺口仍需 Harness 复测。
