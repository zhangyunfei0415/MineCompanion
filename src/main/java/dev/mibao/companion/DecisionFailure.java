package dev.mibao.companion;

import com.google.gson.*;

/** Bounded provider diagnostics, kept independently of the last successful decision. */
final class DecisionFailure {
  static JsonObject describe(DecisionWire.Profile p, int status, String body, Throwable error) {
    String detail = body == null ? "" : body;
    try {
      var json = JsonParser.parseString(detail).getAsJsonObject();
      var value = json.has("detail") ? json.get("detail") : json.get("error");
      if (value != null) detail = value.isJsonPrimitive() ? value.getAsString() : value.toString();
    } catch (RuntimeException ignored) {
      // Plain-text error bodies remain usable after redaction and truncation.
    }
    String reason;
    if (error != null) {
      Throwable cause = error;
      while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
      reason =
          cause instanceof java.net.http.HttpTimeoutException
              ? "DECISION_TIMEOUT"
              : "DECISION_NETWORK";
      detail = cause.getClass().getSimpleName() + "：无法完成网络请求，可在服务恢复后重试";
    } else {
      reason = status == 200 ? "DECISION_RESULT_FAILED" : "DECISION_HTTP_" + status;
      if (detail.isBlank())
        detail =
            switch (status) {
              case 400, 422 -> "服务端拒绝请求格式，请检查协议和模型";
              case 401, 403 -> "服务端鉴权或访问权限失败";
              case 429 -> "服务端限流，请等待限额恢复";
              case 503 -> "模型未加载或服务暂不可用";
              default -> "服务端未提供详细错误";
            };
    }
    if (!p.key().isBlank()) detail = detail.replace(p.key(), "[REDACTED]");
    detail = detail.replaceAll("(?i)Bearer\\s+[^\\s\"']+", "Bearer [REDACTED]");
    var o = new JsonObject();
    o.addProperty("at", System.currentTimeMillis());
    o.addProperty("http_status", status);
    o.addProperty("reason_code", reason);
    o.addProperty("endpoint", p.endpoint());
    o.addProperty("detail_excerpt", detail.substring(0, Math.min(200, detail.length())));
    o.addProperty("retryable", error != null || status == 429 || status >= 500);
    return o;
  }
}
