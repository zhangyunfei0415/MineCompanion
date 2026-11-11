package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/** Testable image gating and text-only protocol fallback. Images never constitute action proof. */
public final class VisionPolicy {
  public static final Set<String> REASONS =
      Set.of("unknown_menu", "target_ambiguous", "execution_failed");

  public static String mode(String mode, boolean legacy) {
    return Set.of("OFF", "AUTO", "DEMO").contains(mode == null ? "" : mode)
        ? mode
        : legacy ? "AUTO" : "OFF";
  }

  public static boolean allowed(
      String mode, String reason, boolean known, int attempts, long now, long previous) {
    return mode.equals("AUTO")
        && REASONS.contains(reason)
        && !(reason.equals("unknown_menu") && known)
        && attempts < 2
        && (previous == 0 || now - previous >= 15000);
  }

  public static boolean unsupported(int status, String text) {
    String s = text == null ? "" : text.toLowerCase(Locale.ROOT);
    return Set.of(400, 415, 422).contains(status)
        && (s.contains("image") || s.contains("vision") || s.contains("multimodal"))
        && (s.contains("not support")
            || s.contains("unsupported")
            || s.contains("not allowed")
            || s.contains("invalid")
            || s.contains("text only")
            || s.contains("text-only"));
  }

  public static JsonObject textOnly(JsonObject request) {
    JsonObject body = request.deepCopy();
    for (var el : body.getAsJsonArray("messages")) {
      var m = el.getAsJsonObject();
      if (m.has("content") && m.get("content").isJsonArray()) {
        StringBuilder t = new StringBuilder();
        for (var part : m.getAsJsonArray("content"))
          if (part.isJsonObject()
              && "text".equals(part.getAsJsonObject().get("type").getAsString()))
            t.append(part.getAsJsonObject().get("text").getAsString()).append('\n');
        m.addProperty("content", t.toString());
      }
    }
    return body;
  }
}
