package dev.mibao.companion;

import com.google.gson.JsonObject;

/** Planner effort is independent from execution verification and never grants permissions. */
public final class PlanningPolicy {
  public static String effort(String value) {
    return "LOW".equals(value) ? "LOW" : "HIGH".equals(value) ? "HIGH" : "MEDIUM";
  }

  public static int rounds(String effort, int base) {
    base = Rules.bounded(base, 1, 96);
    return switch (effort(effort)) {
      case "LOW" -> Math.max(1, (base + 2) / 3);
      case "HIGH" -> Math.min(96, base * 2);
      default -> base;
    };
  }

  public static String title(String effort) {
    return switch (effort(effort)) {
      case "LOW" -> "低";
      case "HIGH" -> "高";
      default -> "中";
    };
  }

  public static String next(String effort) {
    return switch (effort(effort)) {
      case "LOW" -> "MEDIUM";
      case "MEDIUM" -> "HIGH";
      default -> "LOW";
    };
  }

  public static String instruction(String effort) {
    return switch (effort(effort)) {
      case "LOW" -> "优先已验证技能和熟悉流程，少量必要观察后连续执行；复杂问题报告具体缺口，不反复空转。";
      case "HIGH" -> "复杂任务先查依赖、工具、材料、工作站与交付位置，考虑替代方案和恢复路径，分阶段执行并汇报简洁规划摘要。已知动作仍交给本地执行器。";
      default -> "为当前小任务安排完整步骤，检查相关材料与设施；条件变化后调整计划，已知流程交给本地执行器。";
    };
  }

  public static boolean nativeSupportedProtocol(String protocol) {
    return "CHAT".equals(protocol) || "RESPONSES".equals(protocol);
  }

  public static void applyNative(JsonObject wire, String protocol, String effort, boolean enabled) {
    if (!enabled) return;
    String value = effort(effort).toLowerCase(java.util.Locale.ROOT);
    if ("RESPONSES".equals(protocol)) {
      var reasoning = new JsonObject();
      reasoning.addProperty("effort", value);
      wire.add("reasoning", reasoning);
    } else if ("CHAT".equals(protocol)) {
      wire.addProperty("reasoning_effort", value);
      if (wire.has("max_tokens")) wire.add("max_completion_tokens", wire.remove("max_tokens"));
    }
  }

  private PlanningPolicy() {}
}
