package dev.mibao.companion;

import com.google.gson.*;

/** Informational hints only. Classification never triggers retries or grants permissions. */
public final class FailureAdvice {
  public record Hint(String code, String next, String tool) {}

  public static Hint forResult(String result) {
    String s = result == null ? "" : result;
    if (s.contains("RECOVERY_REQUIRED"))
      return new Hint("health", "停止危险作业；查看恢复状态、进食与生命变化，保留控制会话，不重放旧作业", "observe");
    if (s.contains("权限") || s.contains("审批") || s.contains("授权"))
      return new Hint(
          "permission",
          "在权限页或服务器规则中查看被拒动作；未批准前不重试",
          s.contains("按键") || s.contains("key.") ? "input_capabilities" : "observe");
    if (s.contains("菜单")
        && (s.contains("改变") || s.contains("变化") || s.contains("旧") || s.contains("切换")))
      return new Hint("stale_menu", "重新观察当前界面，不复用旧槽位编号", "inspect_gui");
    if (s.contains("同步") && (s.contains("超时") || s.contains("未核实") || s.contains("不一致")))
      return new Hint("unverified", "保留现场，重新观察；不要重复点击或投料", "observe");
    if (s.contains("缺料") || s.contains("材料不足") || s.contains("缺少可制作") || s.contains("无法完成整份计划"))
      return new Hint("materials", "先检查整份配方材料；有明确来源时再核实补料", "check_task");
    if (s.contains("NO_PLACE_SITE") || s.contains("PLACE_CHANGED") || s.contains("PLACE_CONDITION"))
      return new Hint("placement", "检查目标格、支撑、实体占位与射线站位；坐标是放入的目标格，不是支撑方块", "inspect_block");
    if (s.contains("空位") || s.contains("容纳") || s.contains("满包") || s.contains("满栏"))
      return new Hint(
          "capacity", "先观察可合并堆叠，organize_inventory整理主背包；仍不足再用明确交付箱，保留装备", "inventory_memory");
    if (s.contains("不符合完整自然树条件"))
      return new Hint(
          "natural_tree", "先查附近其他完整自然树或改查其他树种；保留可能属于建筑的原木，不把诊断中的不可达计数当成主因", "query_world");
    if (s.contains("路线")
        || s.contains("站位")
        || s.contains("高差")
        || s.contains("落地")
        || s.contains("不可达")) return new Hint("route", "检查台阶、上盖和通道，换站位；不自动拆障碍", "observe");
    if (s.contains("未加载") || s.contains("未知地点") || s.contains("消失"))
      return new Hint("unknown_target", "重新查询已加载对象或重新标记地点", "query_world");
    return new Hint("review", "查看具体原因并重新观察；未核实步骤不自动重放", "observe");
  }

  public static JsonObject json(String result) {
    var h = forResult(result);
    var o = new JsonObject();
    o.addProperty("code", h.code);
    o.addProperty("next_step", h.next);
    o.addProperty("suggested_tool", h.tool);
    o.addProperty("auto_retry", false);
    o.addProperty(
        "escalation",
        h.code.equals("permission")
            ? "player_decision_required"
            : h.code.equals("unverified")
                ? "reobserve_without_replaying_writes"
                : "try_bounded_self_recovery_before_asking");
    return o;
  }
}
