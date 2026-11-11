package dev.mibao.companion;

import com.google.gson.*;
import java.util.function.*;

/** A malformed custom recipe must not abort lookup of unrelated vanilla recipes. */
public final class RecipeLookupPage {
  public static <T> JsonObject page(
      Iterable<T> source,
      Predicate<T> match,
      Function<T, JsonObject> describe,
      int offset,
      int limit) {
    var rows = new JsonArray();
    var errors = new JsonArray();
    int valid = 0, scanned = 0;
    boolean more = false, truncated = false;
    for (T recipe : source) {
      if (++scanned > 20000) {
        truncated = true;
        break;
      }
      try {
        if (!match.test(recipe)) continue;
        var row = describe.apply(recipe);
        if (valid++ < offset) continue;
        if (rows.size() >= limit) {
          more = true;
          break;
        }
        rows.add(row);
      } catch (RuntimeException | LinkageError e) {
        if (errors.size() < 8) errors.add(e.getClass().getSimpleName());
      }
    }
    var out = new JsonObject();
    out.add("recipes", rows);
    out.add("errors", errors);
    out.addProperty("incomplete_due_to_recipe_errors", !errors.isEmpty());
    out.addProperty("has_more", more);
    out.addProperty("truncated", truncated);
    out.addProperty("offset", offset);
    return out;
  }

  private RecipeLookupPage() {}
}
