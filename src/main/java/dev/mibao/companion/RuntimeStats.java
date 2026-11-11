package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.*;

/** Session counters, without images, credentials, prompts or presumed costs. */
public final class RuntimeStats {
  private static final Map<String, Long> counts = new TreeMap<>();

  public static synchronized void add(String key) {
    counts.merge(key, 1L, Long::sum);
  }

  public static synchronized JsonObject snapshot() {
    var o = new JsonObject();
    counts.forEach(o::addProperty);
    o.addProperty("scope", "current_game_process");
    o.addProperty(
        "rule",
        "screenshots_saved, images_attached, model_requests and vision_rejections are distinct;"
            + " attachment does not prove successful vision inference");
    return o;
  }
}
