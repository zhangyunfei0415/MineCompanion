package dev.mibao.companion;

import com.google.gson.*;

/** Additive result envelope. Original text and original Harness receipt remain authoritative. */
public final class ActionOutcome {
  public static JsonObject describe(String text) {
    text = text == null ? "" : text;
    var o = new JsonObject();
    String state, reason = "";
    if (text.startsWith("部分完成")) state = "partial";
    else if (text.startsWith("失败") || text.startsWith("错误") || text.startsWith("未转移"))
      state = "failed";
    else if (text.startsWith("成功：")) state = "verified";
    else if (text.startsWith("{") || text.startsWith("[")) state = "observation";
    else state = "reported_effect_unverified";
    if (text.startsWith("{"))
      try {
        var result = JsonParser.parseString(text).getAsJsonObject();
        if (result.has("status")) {
          String status = result.get("status").getAsString();
          if (status.equals("verified")) state = "verified";
          else if (status.equals("rejected")) state = "failed";
          else if (java.util.Set.of("unverified", "dispatched", "position_changed", "state_matches")
              .contains(status)) state = "partial";
        }
        if (!state.equals("failed")
            && result.has("business_success")
            && !result.get("business_success").getAsBoolean()) state = "reported_effect_unverified";
      } catch (RuntimeException ignored) {
      }
    if (text.contains("权限") || text.contains("授权") || text.contains("审批")) reason = "permission";
    else if (text.contains("界面") || text.contains("菜单") || text.contains("槽位"))
      reason = "interface";
    else if (text.contains("路线") || text.contains("站位") || text.contains("可见"))
      reason = "reachability";
    else if (text.contains("缺") || text.contains("不足") || text.contains("没有符合"))
      reason = "supplies";
    else if (text.contains("同步") || text.contains("快照")) reason = "synchronization";
    o.addProperty("schema_version", 1);
    o.addProperty("outcome", state);
    o.addProperty("reason", reason);
    o.addProperty("replay_safe", false);
    o.addProperty(
        "success_scope",
        "only what the original result explicitly verified, not the entire production chain");
    ReasonCodes.describe(text).entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
    return o;
  }
}
