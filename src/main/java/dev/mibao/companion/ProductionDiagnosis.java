package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Structured shortage diagnosis shared by craft and smelt production failures. */
public final class ProductionDiagnosis {
  public static final String RESERVED = "RESERVED";
  public static final String NBT_EXCLUDED = "NBT_EXCLUDED";
  public static final String RECIPE_TOO_LARGE = "RECIPE_TOO_LARGE";
  public static final String NO_RECIPE = "NO_RECIPE";
  public static final String MATERIALS_MISSING = "MATERIALS_MISSING";

  public record Missing(String item, int need, int have, int reserved, int usable) {}

  public record Receipt(String reason, List<Missing> missing, String detail) {
    public JsonObject json() {
      JsonObject out = new JsonObject();
      out.addProperty("reason", reason);
      JsonArray array = new JsonArray();
      for (Missing m : missing) {
        JsonObject o = new JsonObject();
        o.addProperty("item", m.item());
        o.addProperty("need", m.need());
        o.addProperty("have", m.have());
        o.addProperty("reserved", m.reserved());
        o.addProperty("usable", m.usable());
        array.add(o);
      }
      out.add("missing", array);
      out.addProperty("detail", detail);
      return out;
    }

    public String text() {
      return "[CRAFT_MATERIALS] 缺料诊断 reason=" + reason + "；" + json();
    }
  }

  private ProductionDiagnosis() {}

  /**
   * Compares the recipe demand with three inventories: plain (no NBT), usable (after logistics
   * reservations) and tagged (NBT only).
   */
  public static Receipt diagnose(
      String reason,
      Map<String, Integer> needs,
      Map<String, Integer> plain,
      Map<String, Integer> usable,
      Map<String, Integer> tagged) {
    List<Missing> missing = new ArrayList<>();
    for (String item : new TreeSet<>(needs.keySet())) {
      int need = Math.max(0, needs.getOrDefault(item, 0));
      if (need == 0) continue;
      int have = Math.max(0, plain.getOrDefault(item, 0));
      int available = Math.max(0, Math.min(have, usable.getOrDefault(item, 0)));
      int reserved = Math.max(0, have - available);
      if (available >= need) continue;
      missing.add(new Missing(item, need, have, reserved, available));
    }
    String classified =
        reason == null || reason.isBlank() ? classify(missing, needs, plain, tagged) : reason;
    return new Receipt(classified, List.copyOf(missing), detail(missing, needs, tagged));
  }

  public static String classify(
      List<Missing> missing,
      Map<String, Integer> needs,
      Map<String, Integer> plain,
      Map<String, Integer> tagged) {
    for (Missing m : missing) if (m.reserved() > 0) return RESERVED;
    for (String item : needs.keySet()) {
      if (tagged.getOrDefault(item, 0) <= 0) continue;
      if (plain.getOrDefault(item, 0) < needs.getOrDefault(item, 0)) return NBT_EXCLUDED;
    }
    return MATERIALS_MISSING;
  }

  private static String detail(
      List<Missing> missing, Map<String, Integer> needs, Map<String, Integer> tagged) {
    if (missing.isEmpty()) return "没有可归纳的物品缺口";
    StringBuilder b = new StringBuilder("缺料：");
    int n = 0;
    for (Missing m : missing) {
      if (n == 4) break;
      if (n++ > 0) b.append("；");
      b.append(m.item())
          .append(" 需")
          .append(m.need())
          .append(" 现有")
          .append(m.have())
          .append(" 预留")
          .append(m.reserved())
          .append(" 可用")
          .append(m.usable());
    }
    if (missing.size() > 4) b.append("；另有 ").append(missing.size() - 4).append(" 项未列出");
    int taggedTotal = 0;
    for (String item : needs.keySet()) taggedTotal += Math.max(0, tagged.getOrDefault(item, 0));
    if (taggedTotal > 0) b.append("；另有带NBT同种物品 ").append(taggedTotal).append(" 个，不计作普通材料");
    return b.toString();
  }
}
