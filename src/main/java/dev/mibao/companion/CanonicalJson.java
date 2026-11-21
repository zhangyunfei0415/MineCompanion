package dev.mibao.companion;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Deterministic canonical JSON serialization and SHA-256 hashing for skill bundles. */
public final class CanonicalJson {
  private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

  private CanonicalJson() {}

  public static String serialize(JsonElement element) {
    return COMPACT.toJson(normalize(element));
  }

  public static JsonElement normalize(JsonElement element) {
    if (element == null || element.isJsonNull()) return JsonNull.INSTANCE;
    if (element.isJsonPrimitive()) return element.deepCopy();
    if (element.isJsonArray()) {
      JsonArray arr = new JsonArray();
      for (JsonElement child : element.getAsJsonArray()) {
        arr.add(normalize(child));
      }
      return arr;
    }
    JsonObject src = element.getAsJsonObject();
    TreeMap<String, JsonElement> sorted = new TreeMap<>();
    for (var e : src.entrySet()) {
      sorted.put(e.getKey(), normalize(e.getValue()));
    }
    JsonObject out = new JsonObject();
    for (var e : sorted.entrySet()) {
      out.add(e.getKey(), e.getValue());
    }
    return out;
  }

  public static String sha256Hex(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public static String bundleContentSha256(JsonObject bundleJson) {
    if (bundleJson == null) return sha256Hex("{}");
    JsonObject copy = bundleJson.deepCopy();
    copy.remove("integrity");
    return sha256Hex(serialize(copy));
  }
}
