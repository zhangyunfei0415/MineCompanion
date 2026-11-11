package dev.mibao.companion;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** JSON object key ordering must not turn a repeated task into a different intent. */
public final class TaskIdentity {
  public static String signature(String tool, JsonObject args) {
    return UUID.nameUUIDFromBytes((tool + ":" + canonical(args)).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private static JsonElement canonical(JsonElement value) {
    if (value.isJsonObject()) {
      var result = new JsonObject();
      new TreeSet<>(value.getAsJsonObject().keySet())
          .forEach(k -> result.add(k, canonical(value.getAsJsonObject().get(k))));
      return result;
    }
    if (value.isJsonArray()) {
      var result = new JsonArray();
      value.getAsJsonArray().forEach(e -> result.add(canonical(e)));
      return result;
    }
    return value.deepCopy();
  }

  private TaskIdentity() {}
}
