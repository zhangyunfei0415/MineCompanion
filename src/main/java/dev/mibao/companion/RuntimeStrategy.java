package dev.mibao.companion;

import com.google.gson.JsonObject;

/** Temporary stricter verification is world/epoch scoped, never higher model spend or replay. */
public final class RuntimeStrategy {
  private static long until, epoch;
  private static String scope = "";

  public static int rounds() {
    return PlanningPolicy.rounds(Store.config.planningEffort, Store.config.maxTurns);
  }

  public static boolean boosted() {
    return Store.config.adaptiveVerification
        && System.currentTimeMillis() < until
        && epoch == Agent.epoch
        && scope.equals(Store.scope());
  }

  public static String executionStyle() {
    return boosted() ? "STRICT" : Store.config.executionStyle;
  }

  public static void result(String tool, String result) {
    if (!Store.config.adaptiveVerification
        || !CapabilityCatalog.mutates(tool)
        || result == null
        || !Rules.actionFailed(result)) return;
    if (result.contains("同步")
        || result.contains("快照")
        || result.contains("槽位")
        || result.contains("库存")
        || result.contains("菜单")) {
      until = System.currentTimeMillis() + 30_000;
      epoch = Agent.epoch;
      scope = Store.scope();
    }
  }

  public static String label() {
    return (Store.config.playerAutonomy ? "玩家式 · " : "")
        + "思考 "
        + PlanningPolicy.title(Store.config.planningEffort)
        + " · "
        + (ExecutionStyle.fluent(executionStyle()) ? "快速" : "严谨")
        + (boosted() ? "（临时加强）" : "");
  }

  public static JsonObject view() {
    var o = new JsonObject();
    o.addProperty("schema", 1);
    o.addProperty("player_autonomy", Store.config.playerAutonomy);
    o.addProperty("nearby_supply_pickup", Store.config.nearbySupplyPickup);
    o.addProperty("effort", PlanningPolicy.effort(Store.config.planningEffort));
    o.addProperty("planning_round_limit", rounds());
    o.addProperty("configured_execution_style", Store.config.executionStyle);
    o.addProperty("effective_execution_style", executionStyle());
    o.addProperty("adaptive_verification", Store.config.adaptiveVerification);
    o.addProperty("temporary_strict", boosted());
    o.addProperty(
        "temporary_strict_remaining_ms",
        boosted() ? Math.max(0, until - System.currentTimeMillis()) : 0);
    o.addProperty(
        "native_effort_requested",
        Store.config.nativeReasoning
            && PlanningPolicy.nativeSupportedProtocol(Store.config.apiProtocol));
    o.addProperty(
        "native_effort_status",
        Store.config.driver.equals("HARNESS")
            ? "external_planner_advisory_only"
            : Store.config.nativeReasoning
                    && PlanningPolicy.nativeSupportedProtocol(Store.config.apiProtocol)
                ? "requested_model_support_unverified"
                : "strategy_and_budget_only");
    o.addProperty(
        "harness_rule",
        "Planner advice; cannot change external model UI or force its compliance. Mod execution"
            + " style is enforced locally.");
    o.addProperty(
        "changes_apply",
        "effort at next planning request; verification at next action; no automatic model switch,"
            + " permission change or action replay");
    return o;
  }

  public static String instruction() {
    return (Store.config.playerAutonomy
            ? "玩家式执行：目标优先，自行查询已知物资、准备工具/工作槽、使用临时补给，按实际条件换站位或调整方案；普通小问题先用有区别的方法有界恢复，无法解决再求助。每阶段汇总，不逐点击汇报；取放/投料未知结果先观察，不重放。不擅用未指定的他人物资，未绑定交付处先查或澄清。"
            : "")
        + "当前思考强度="
        + PlanningPolicy.effort(Store.config.planningEffort)
        + "；"
        + PlanningPolicy.instruction(Store.config.planningEffort)
        + " 本任务规划轮数上限="
        + rounds()
        + "。执行风格="
        + executionStyle()
        + "；快速优先复用实际同步，严谨加强核对；两者不凭点击报成功。不要因当前设置擅自换模型、提高权限或重放未知结果。";
  }

  private RuntimeStrategy() {}
}
