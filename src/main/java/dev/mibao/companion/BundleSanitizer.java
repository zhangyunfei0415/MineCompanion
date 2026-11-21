package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Validates portable bundles and local adaptations against leaked host paths, credentials/tokens,
 * or non-portable coordinate bindings before export or after import.
 */
public final class BundleSanitizer {
  private static final Pattern DRIVE_PATH = Pattern.compile("(^|[\\s\"'(\\[,])([A-Za-z]:[/\\\\])");
  private static final Pattern HOME_PATH =
      Pattern.compile("(?i)(^|[\\s\"'(\\[,])(file:///|/Users/|/home/)");
  private static final Pattern SECRET_VALUE =
      Pattern.compile(
          "(sk-[A-Za-z0-9_-]{12,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_-]{20,}|Bearer\\s+[A-Za-z0-9._-]{12,})");
  private static final Set<String> SECRET_KEYS =
      Set.of(
          "apikey",
          "api_key",
          "mcptoken",
          "mcp_token",
          "accesstoken",
          "access_token",
          "decisioncloudkey",
          "decisionlocalkey",
          "secret",
          "password",
          "authorization");

  private BundleSanitizer() {}

  public static List<String> scanViolations(JsonElement root) {
    List<String> violations = new ArrayList<>();
    scan("$", root, violations);
    return List.copyOf(violations);
  }

  public static void assertClean(JsonElement root) {
    List<String> violations = scanViolations(root);
    if (!violations.isEmpty()) {
      throw new IllegalArgumentException(
          "记忆包包含本机路径、凭据或不可分享字段：" + String.join("; ", violations.subList(0, Math.min(3, violations.size()))));
    }
  }

  private static void scan(String path, JsonElement node, List<String> violations) {
    if (node == null || node.isJsonNull() || violations.size() >= 16) return;
    if (node.isJsonArray()) {
      JsonArray arr = node.getAsJsonArray();
      for (int i = 0; i < arr.size(); i++) {
        scan(path + "[" + i + "]", arr.get(i), violations);
      }
      return;
    }
    if (node.isJsonObject()) {
      for (var entry : node.getAsJsonObject().entrySet()) {
        String key = entry.getKey();
        String lower = key.toLowerCase(Locale.ROOT);
        JsonElement val = entry.getValue();
        if (SECRET_KEYS.contains(lower)
            && val != null
            && val.isJsonPrimitive()
            && !val.getAsString().isBlank()
            && !isPlaceholder(val.getAsString())) {
          violations.add(path + "." + key + " 包含敏感凭据字段");
        }
        scan(path + "." + key, val, violations);
      }
      return;
    }
    if (node.isJsonPrimitive() && node.getAsJsonPrimitive().isString()) {
      String text = node.getAsString();
      if (text.isBlank()) return;
      if (DRIVE_PATH.matcher(text).find()) {
        violations.add(path + " 含本机盘符路径");
      }
      if (HOME_PATH.matcher(text).find()) {
        violations.add(path + " 含本机用户路径/file URI");
      }
      if (SECRET_VALUE.matcher(text).find()) {
        violations.add(path + " 含疑似密钥/Token");
      }
      if (text.contains("harness-connection.json")
          && (text.contains("\"token\"") || text.contains("\"mcpToken\""))) {
        violations.add(path + " 含 Harness 凭据快照");
      }
    }
  }

  private static boolean isPlaceholder(String value) {
    String v = value.trim();
    return v.startsWith("<") && v.endsWith(">") || v.startsWith("${") && v.endsWith("}");
  }
}
