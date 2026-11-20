package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.Objects;

/** Apply local edits since the last save without replacing unrelated disk edits. */
final class SettingsChanges {
  private SettingsChanges() {}

  static JsonObject merge(JsonObject disk, JsonObject previous, JsonObject current) {
    var merged = disk.deepCopy();
    for (var entry : current.entrySet()) {
      String key = entry.getKey();
      if (!Objects.equals(previous.get(key), entry.getValue()))
        merged.add(key, entry.getValue().deepCopy());
    }
    for (String key : previous.keySet()) {
      if (!current.has(key)) merged.remove(key);
    }
    return merged;
  }
}
