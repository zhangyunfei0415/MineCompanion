package dev.mibao.companion;

import com.google.gson.*;

/** World-file checkpoint. Informational only: never repeats uncertain work after restart. */
public final class RecoveryLedger {
  public static void put(
      String kind, String phase, boolean uncertain, JsonObject detail, String dimension) {
    JsonObject r = detail.deepCopy();
    r.addProperty("phase", phase);
    r.addProperty("uncertain", uncertain);
    r.addProperty("updated", java.time.Instant.now().toString());
    r.addProperty("dimension", dimension);
    r.addProperty("resume", "manual_reobserve_required");
    if (Store.data.checkpoints == null) Store.data.checkpoints = new java.util.LinkedHashMap<>();
    Store.data.checkpoints.put(dimension + "|" + kind, r);
    Store.save();
  }

  public static String view() {
    return Store.GSON.toJson(
        Store.data.checkpoints == null ? java.util.Map.of() : Store.data.checkpoints);
  }

  private static String phaseLabel(String phase) {
    return switch (phase) {
      case "waiting_materials" -> "制作前等待临时补给";
      case "prepared" -> "流程已准备";
      case "waiting_supply" -> "等待临时补给";
      case "child_started" -> "流程子步骤执行中";
      case "child_verified" -> "流程子步骤已核实";
      case "complete" -> "已完成";
      case "stopped" -> "已停止，请核实现场";
      case "cancelled" -> "已取消";
      case "unload_started" -> "已开始交付，结果待核实";
      case "unload_verified", "transfer_confirmed" -> "本次转移已核实";
      case "transfer_started" -> "转移进行中，结果待核实";
      default ->
          phase.endsWith("_returned")
              ? ActionSummary.step(phase.substring(0, phase.length() - 9)) + "已返回"
              : ActionSummary.step(phase);
    };
  }

  public static String human() {
    if (Store.data.checkpoints == null || Store.data.checkpoints.isEmpty())
      return "暂无本世界恢复记录。\n停止后的未知步骤不会自动重放。";
    var out = new StringBuilder("本世界作业检查点\n这些记录用于核实现状，不会自动重复取货、投料或点击。\n");
    int n = 0;
    for (var e : Store.data.checkpoints.entrySet()) {
      if (++n > 20) {
        out.append("\n还有更多记录，可用恢复记录工具查看完整数据。\n");
        break;
      }
      var r = e.getValue();
      out.append("\n作业：")
          .append(
              e.getKey().endsWith("|resource")
                  ? "采集"
                  : e.getKey().endsWith("|transport")
                      ? "搬运"
                      : e.getKey().endsWith("|production") ? "制作/熔炼" : e.getKey());
      out.append("\n维度：")
          .append(Agent.str(r, "dimension", "未知"))
          .append("\n阶段：")
          .append(phaseLabel(Agent.str(r, "phase", "未知")));
      out.append("\n待核实：")
          .append(
              r.has("uncertain")
                      && r.get("uncertain").isJsonPrimitive()
                      && r.get("uncertain").getAsBoolean()
                  ? "是，先观察现场"
                  : "当前步骤已有记录，继续前仍要观察");
      for (String key :
          new String[] {
            "requested",
            "confirmed_batches",
            "remaining_batches",
            "mined",
            "fetched",
            "delivered",
            "carrying_confirmed",
            "completed_steps",
            "total_steps"
          })
        if (r.has(key) && r.get(key).isJsonPrimitive())
          out.append("\n")
              .append(
                  switch (key) {
                    case "requested" -> "目标数量";
                    case "confirmed_batches" -> "已确认批次";
                    case "remaining_batches" -> "剩余批次";
                    case "mined" -> "已确认挖除";
                    case "fetched" -> "已确认取出";
                    case "completed_steps" -> "已完成步骤";
                    case "total_steps" -> "总步骤";
                    case "delivered" -> "已确认交付";
                    default -> "已确认携带";
                  })
              .append("：")
              .append(r.get(key).getAsString());
      if (r.has("delivered") && r.get("delivered").isJsonObject())
        out.append("\n已交付变体记录：")
            .append(r.getAsJsonObject("delivered").size())
            .append(" 种；完整数量在工具记录中");
      out.append('\n');
    }
    return out.toString();
  }
}
