package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/** JSON persistence boundary; slot movement and durability wear must not become produced items. */
public final class InventoryJournal {
  public static Map<String, Integer> totals(JsonObject snapshot) {
    var out = new TreeMap<String, Integer>();
    for (var e : snapshot.getAsJsonArray("slots")) add(out, e.getAsJsonObject());
    if (snapshot.has("cursor")) add(out, snapshot.getAsJsonObject("cursor"));
    return out;
  }

  private static void add(Map<String, Integer> out, JsonObject s) {
    if (!s.has("count") || s.get("count").getAsInt() == 0) return;
    String key =
        s.get(s.has("quantity_fingerprint") ? "quantity_fingerprint" : "fingerprint").getAsString();
    out.merge(key, s.get("count").getAsInt(), Integer::sum);
  }

  public static JsonArray changes(JsonObject old, JsonObject current, String session, long now) {
    var entries =
        old != null && old.has("changes") && old.get("changes").isJsonArray()
            ? old.getAsJsonArray("changes").deepCopy()
            : new JsonArray();
    if (old != null && old.has("session") && session.equals(old.get("session").getAsString())) {
      var delta = InventoryTolerance.delta(totals(old), totals(current));
      if (!delta.isEmpty()) {
        var e = new JsonObject();
        e.addProperty("at", now);
        e.addProperty("source", "observed_change_unattributed");
        var amounts = new JsonObject();
        delta.forEach(amounts::addProperty);
        e.add("delta", amounts);
        entries.add(e);
      }
    }
    while (entries.size() > 24) entries.remove(0);
    return entries;
  }

  private InventoryJournal() {}
}
