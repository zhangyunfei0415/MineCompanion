package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.regex.*;

/** Additive diagnostics: permission, capability, geometry and uncertain effects stay distinct. */
public final class ReasonCodes {
  private static final Pattern EXPLICIT = Pattern.compile("\\[([A-Z][A-Z0-9_]+)]");

  public static JsonObject describe(String text) {
    String raw = text == null ? "" : text, code = "NONE";
    var matcher = EXPLICIT.matcher(raw);
    boolean nominal =
        raw.startsWith("成功")
            || raw.startsWith("输入已发送")
            || raw.startsWith("输入已释放")
            || raw.startsWith("滚动已发送")
            || raw.startsWith("文本输入已发送")
            || raw.startsWith("返回键已发送")
            || raw.startsWith("{")
            || raw.startsWith("[") && !raw.matches("^\\[[A-Z][A-Z0-9_]*].*");
    if (nominal) code = "NONE";
    else if (matcher.find()) code = matcher.group(1);
    else if (raw.startsWith("输入已执行并释放：已打开")) code = "NONE";
    else if (raw.contains("PROTECTED_KEY")) code = "PROTECTED_KEY";
    else if (raw.contains("鼠标") && raw.contains("物品")) code = "CURSOR_OCCUPIED";
    else if (raw.contains("权限") || raw.contains("授权") || raw.contains("审批")) code = "POLICY_DENIED";
    else if (raw.contains("区块") && raw.contains("未加载")) code = "UNLOADED_TARGET";
    else if (raw.contains("满") || raw.contains("空位") || raw.contains("容纳")) code = "INVENTORY_FULL";
    else if (raw.contains("路线") || raw.contains("不可达") || raw.contains("站位"))
      code = "NOT_REACHABLE";
    else if (raw.contains("同步") || raw.contains("未确认") || raw.contains("不确定"))
      code = "RESULT_UNCONFIRMED";
    else if (raw.contains("界面") || raw.contains("菜单")) code = "SCREEN_BLOCKING";
    else if (raw.startsWith("失败") || raw.startsWith("错误")) code = "TOOL_FAILED";
    else if (raw.startsWith("部分完成")) code = "PARTIAL_RESULT";
    code =
        switch (code) {
          case "UNREACHABLE", "TRANSFER_NOT_STARTED" -> "NOT_REACHABLE";
          case "NO_PLACE_SITE" -> raw.contains("支撑面") ? "NO_SUPPORT_FACE" : "NO_PLACE_SITE";
          case "PERMISSION_REQUIRED" -> "POLICY_DENIED";
          default -> code;
        };
    var o = new JsonObject();
    if (raw.startsWith("{")) {
      try {
        var structured = JsonParser.parseString(raw).getAsJsonObject();
        if (structured.has("reason_code")) code = structured.get("reason_code").getAsString();
      } catch (RuntimeException ignored) {
        // Legacy display strings remain readable when they are not JSON.
      }
    }
    o.addProperty("reason_code", code);
    o.addProperty("reason_detail", Rules.clean(raw, 800));
    // This flag is deliberately not an instruction to replay writes.
    o.addProperty("retryable", false);
    o.addProperty(
        "requires_human",
        Set.of(
                "POLICY_DENIED",
                "TAKEOVER_DISABLED",
                "INTERFACE_DISABLED",
                "LEASE_INVALID",
                "APPROVAL_REQUIRED")
            .contains(code));
    var alternatives = new JsonArray();
    switch (code) {
      case "NOT_REACHABLE", "DOOR_BLOCKED", "NO_SUPPORT_FACE", "UNLOADED_TARGET" ->
          alternatives.add("inspect_client");
      case "CURSOR_OCCUPIED" -> alternatives.add("stow_cursor");
      case "CONTAINER_OBSERVATION_REQUIRED", "RESULT_UNCONFIRMED" ->
          alternatives.add("observe current target before any new write");
      case "PROTECTED_KEY" ->
          alternatives.add("use dedicated break_block / hunt_mobs / use_hand tools");
      default -> {}
    }
    o.add("suggested_alternatives", alternatives);
    return o;
  }

  public static JsonObject error(String text) {
    var o = describe(text);
    if (o.get("reason_code").getAsString().equals("NONE"))
      o.addProperty("reason_code", "TOOL_FAILED");
    return o;
  }

  public static JsonObject enrich(JsonObject receipt) {
    if (receipt.has("result") && receipt.get("result").isJsonPrimitive())
      describe(receipt.get("result").getAsString())
          .entrySet()
          .forEach(e -> receipt.add(e.getKey(), e.getValue()));
    return receipt;
  }

  private ReasonCodes() {}
}
