package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.Locale;

/**
 * Exact inventory matching for {@code inventory_memory}: registry id, full NBT fingerprint, or
 * {@code id#sha256:...}. Pure helper; deliberately never uses substring matching.
 */
final class InventoryQuery {
  private InventoryQuery() {}

  /**
   * Parsed filter: empty {@code id} means "any id", empty {@code fingerprint} means "no NBT rule".
   */
  record Query(String id, String fingerprint) {}

  /**
   * @return parsed query, or null for a blank filter.
   */
  static Query parse(String filter) {
    if (filter == null) return null;
    String s = filter.strip();
    if (s.isEmpty()) return null;
    int hash = s.indexOf('#');
    if (hash > 0) return new Query(normalize(s.substring(0, hash)), s.substring(hash + 1));
    if (s.startsWith("sha256:") || s.contains("#sha256:"))
      return new Query("", s.startsWith("sha256:") ? s : s.substring(s.indexOf('#') + 1));
    return new Query(normalize(s), "");
  }

  /** Exact compare of a registry id and a {@code WorldSense} fingerprint (id#sha256:hex). */
  static boolean matches(Query query, String itemId, String fingerprint) {
    if (query == null) return false;
    String id = normalize(itemId);
    if (!query.id().isEmpty() && !query.id().equals(id)) return false;
    if (query.fingerprint().isEmpty()) return true;
    if (fingerprint == null) return false;
    return query.fingerprint().startsWith("sha256:")
        ? fingerprint.endsWith(query.fingerprint())
        : fingerprint.equals(query.fingerprint());
  }

  /** Result block returned by inventory_memory when a filter is given. */
  static JsonObject result(Query query, int matched, int minCount) {
    JsonObject o = new JsonObject();
    o.addProperty("has_item", query != null && matched >= Math.max(1, minCount));
    o.addProperty("matched_count", query == null ? 0 : matched);
    o.addProperty("min_count", Math.max(1, minCount));
    o.addProperty("match_rule", "注册名或NBT指纹精确匹配（quantity_fingerprint/完整指纹），绝不使用字符串包含");
    return o;
  }

  static String normalize(String id) {
    String s = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
    if (s.isEmpty()) return s;
    return s.indexOf(':') < 0 ? "minecraft:" + s : s;
  }
}
