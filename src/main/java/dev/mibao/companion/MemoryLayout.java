package dev.mibao.companion;

import com.google.gson.*;

/** Explicit file boundary: world evidence stays local; reusable procedures live separately. */
public final class MemoryLayout {
  private MemoryLayout() {}

  public static JsonObject world(JsonObject combined, String scope) {
    JsonObject local = combined.deepCopy();
    local.remove("skills");
    local.addProperty("schema", 2);
    local.addProperty("scope", scope);
    return local;
  }

  public static JsonObject library(JsonElement skills) {
    JsonObject library = new JsonObject();
    library.addProperty("schema", 1);
    library.addProperty("kind", "skill_library");
    library.add("skills", skills.deepCopy());
    return library;
  }
}
