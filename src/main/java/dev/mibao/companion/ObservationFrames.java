package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/** Shallow-key deltas with complete checkpoints; the receiver must reject a missing base. */
public final class ObservationFrames {
  private JsonObject previous;
  private long sequence, lastFull;

  public JsonObject encode(JsonObject observation, long now, boolean force) {
    long next = ++sequence;
    boolean full =
        force
            || previous == null
            || now - lastFull >= 10000
            || changed("epoch", observation)
            || changed("server_scope", observation)
            || changed("dimension", observation)
            || changed("screen", observation)
            || changed("menu_id", observation);
    JsonObject row = new JsonObject();
    row.addProperty("kind", full ? "observation" : "observation_delta");
    row.addProperty("frame", next);
    if (full) {
      row.add("observation", observation.deepCopy());
      lastFull = now;
    } else {
      row.addProperty("base_frame", next - 1);
      JsonObject changes = new JsonObject();
      JsonArray removed = new JsonArray();
      for (var e : observation.entrySet())
        if (!Objects.equals(previous.get(e.getKey()), e.getValue()))
          changes.add(e.getKey(), e.getValue().deepCopy());
      for (String key : previous.keySet()) if (!observation.has(key)) removed.add(key);
      row.add("changes", changes);
      row.add("removed", removed);
    }
    previous = observation.deepCopy();
    return row;
  }

  private boolean changed(String key, JsonObject o) {
    return previous != null && !Objects.equals(previous.get(key), o.get(key));
  }

  public static JsonObject apply(JsonObject base, long baseFrame, JsonObject row) {
    if (row.get("kind").getAsString().equals("observation"))
      return row.getAsJsonObject("observation").deepCopy();
    if (base == null
        || row.get("base_frame").getAsLong() != baseFrame
        || row.get("frame").getAsLong() != baseFrame + 1)
      throw new IllegalArgumentException("观察差分缺少连续基线，需等待完整检查点");
    JsonObject out = base.deepCopy();
    for (var v : row.getAsJsonArray("removed")) out.remove(v.getAsString());
    for (var e : row.getAsJsonObject("changes").entrySet())
      out.add(e.getKey(), e.getValue().deepCopy());
    return out;
  }
}
