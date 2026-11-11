package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/** Exact item fingerprints/quantities, independent of AE classes. Fluids are never guessed. */
public final class AePatternProof {
  public static boolean matches(JsonObject draft, JsonObject decoded) {
    if (!decoded.has("decodable") || !decoded.get("decodable").getAsBoolean()) return false;
    String mode = draft.get("mode").getAsString();
    Set<String> in =
        switch (mode) {
          case "CRAFTING" -> Set.of("CRAFTING_GRID");
          case "PROCESSING" -> Set.of("PROCESSING_INPUTS");
          case "STONECUTTING" -> Set.of("STONECUTTING_INPUT");
          case "SMITHING_TABLE" ->
              Set.of("SMITHING_TABLE_TEMPLATE", "SMITHING_TABLE_BASE", "SMITHING_TABLE_ADDITION");
          default -> Set.of();
        };
    Set<String> out =
        mode.equals("PROCESSING")
            ? Set.of("PROCESSING_OUTPUTS")
            : mode.equals("SMITHING_TABLE")
                ? Set.of("SMITHING_TABLE_RESULT")
                : Set.of("CRAFTING_RESULT");
    var expectedIn = draftItems(draft, in, !mode.equals("PROCESSING"));
    var expectedOut = draftItems(draft, out, false);
    var actualIn = decodedItems(decoded.getAsJsonArray("inputs"));
    var actualOut = decodedItems(decoded.getAsJsonArray("outputs"));
    return !expectedIn.isEmpty()
        && !expectedOut.isEmpty()
        && expectedIn.equals(actualIn)
        && expectedOut.equals(actualOut);
  }

  private static Map<String, Long> draftItems(JsonObject draft, Set<String> roles, boolean one) {
    var result = new TreeMap<String, Long>();
    for (var e : draft.getAsJsonArray("slots")) {
      var row = e.getAsJsonObject();
      if (!roles.contains(row.get("role").getAsString()) || !row.get("active").getAsBoolean())
        continue;
      var item = row.getAsJsonObject("item");
      long count = item.get("count").getAsLong();
      if (count > 0 && item.has("fingerprint"))
        result.merge(item.get("fingerprint").getAsString(), one ? 1L : count, Long::sum);
    }
    return result;
  }

  private static Map<String, Long> decodedItems(JsonArray values) {
    var result = new TreeMap<String, Long>();
    if (values == null) return result;
    for (var e : values) {
      var row = e.getAsJsonObject();
      if (!row.has("fingerprint") || !row.has("amount")) return Map.of();
      long amount = row.get("amount").getAsLong();
      if (amount <= 0) return Map.of();
      result.merge(row.get("fingerprint").getAsString(), amount, Long::sum);
    }
    return result;
  }

  private AePatternProof() {}
}
