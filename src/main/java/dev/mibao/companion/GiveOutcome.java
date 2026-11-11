package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;

/**
 * Parses one {@code /give} command for the local player and grades the inventory/dropped evidence
 * of the next ~20 ticks. Pure helper: no Minecraft types, so offline tests cover the wording.
 */
final class GiveOutcome {
  private GiveOutcome() {}

  /** The item and count a verifiable self-targeted /give expects to add. */
  record Expected(String itemId, int count) {}

  /**
   * @return the expectation, or null when the command is not a self-targeted /give this client can
   *     check (other targets, malformed counts, entities, etc.).
   */
  static Expected parse(String command, String playerName) {
    if (command == null) return null;
    String s = command.strip();
    if (s.startsWith("/")) s = s.substring(1);
    String[] p = s.split("\\s+");
    if (p.length < 3 || !p[0].equals("give")) return null;
    if (!targetsSelf(p[1], playerName)) return null;
    String item = p[2];
    int bracket = item.indexOf('[');
    if (bracket >= 0) item = item.substring(0, bracket);
    if (item.isBlank()) return null;
    int count = 1;
    if (p.length > 3) {
      try {
        count = Integer.parseInt(p[3]);
      } catch (NumberFormatException e) {
        return null;
      }
      if (count < 1 || count > 64) return null;
    }
    return new Expected(normalize(item), count);
  }

  /** Grade token plus reason, e.g. {@code GIVE_OK(+8) 已确认：...}. */
  static String receipt(Expected expected, JsonObject evidence, int freeSlotsBefore) {
    if (expected == null) return "";
    long gained = inventoryGain(evidence, expected.itemId());
    long dropped = droppedCount(evidence, expected.itemId());
    if (gained >= expected.count())
      return "GIVE_OK(+"
          + gained
          + ") 已确认：/give 生效，背包 +"
          + gained
          + " "
          + expected.itemId()
          + "（客户端背包差量核对，服务器已下发；不证明物品来源）";
    if (dropped >= expected.count())
      return "GIVE_DROPPED_ON_GROUND 已确认：掉落 "
          + expected.itemId()
          + "×"
          + dropped
          + " 在地上（背包满或未入包；不算作已入包，落地10tick后才可拾取；不自动重发）";
    if (gained > 0)
      return "GIVE_OK(+"
          + gained
          + ") 已确认：/give 部分生效，背包 +"
          + gained
          + "/"
          + expected.count()
          + " "
          + expected.itemId()
          + "；差额可能落地或被服务器拒绝，先观察不重发";
    if (freeSlotsBefore <= 0)
      return "GIVE_FAILED_NO_SPACE 未确认：发送前主背包无空位，20tick内未见入包或落地；可能被服务器拒绝，不自动重发";
    return "GIVE_UNCONFIRMED 未观察到背包增量或落地掉落；不自动重发";
  }

  static long inventoryGain(JsonObject evidence, String itemId) {
    long total = 0;
    for (JsonElement element : array(evidence, "inventory")) {
      JsonObject row = object(element);
      if (!normalize(text(row, "id")).equals(itemId)) continue;
      long delta = number(row, "delta");
      if (delta > 0) total += delta;
    }
    return total;
  }

  static long droppedCount(JsonObject evidence, String itemId) {
    long total = 0;
    for (JsonElement element : array(evidence, "dropped")) {
      JsonObject row = object(element);
      if (!normalize(text(row, "id")).equals(itemId)) continue;
      total += Math.max(0, number(row, "count"));
    }
    return total;
  }

  private static boolean targetsSelf(String target, String playerName) {
    if (target.equals("@s") || target.equals("@p")) return true;
    return playerName != null && !playerName.isBlank() && target.equals(playerName);
  }

  private static String normalize(String id) {
    String s = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
    if (s.isEmpty()) return s;
    return s.indexOf(':') < 0 ? "minecraft:" + s : s;
  }

  private static JsonArray array(JsonObject o, String key) {
    if (o == null) return new JsonArray();
    JsonElement e = o.get(key);
    return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
  }

  private static JsonObject object(JsonElement e) {
    return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
  }

  private static long number(JsonObject o, String key) {
    if (o == null) return 0;
    JsonElement e = o.get(key);
    if (e == null || !e.isJsonPrimitive()) return 0;
    try {
      return e.getAsLong();
    } catch (RuntimeException ex) {
      return 0;
    }
  }

  private static String text(JsonObject o, String key) {
    if (o == null) return "";
    JsonElement e = o.get(key);
    return e == null || !e.isJsonPrimitive() ? "" : e.getAsString();
  }
}
