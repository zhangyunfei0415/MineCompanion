package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/** Authenticated by HTTP, but deliberately independent from the controller's write lease. */
public final class HarnessEventPage {
  public static JsonObject read(Deque<JsonObject> source, String scope, long after, long sequence) {
    var out = new JsonObject();
    var list = new JsonArray();
    for (var e : source)
      if (e.get("sequence").getAsLong() > Math.max(0, after)
          && e.has("scope")
          && e.get("scope").getAsString().equals(scope)) list.add(e.deepCopy());
    out.add("events", list);
    out.addProperty("next", sequence);
    out.addProperty("scope", scope);
    out.addProperty("read_only", true);
    out.addProperty(
        "truncated",
        !source.isEmpty() && after < source.peekFirst().get("sequence").getAsLong() - 1);
    return out;
  }

  private HarnessEventPage() {}
}
