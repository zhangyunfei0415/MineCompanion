package dev.mibao.companion;

import com.google.gson.*;

/**
 * Explicit bounded full farm procedure, executed through the same permission-checked child tools.
 */
public final class FarmPlan {
  private FarmPlan() {}

  public static JsonObject create(JsonObject input) {
    CropSpec crop = CropSpec.of(SkillCatalog.required(input, "crop"));
    int count = SkillCatalog.bounded(input, "count", 4, 1, 64),
        radius = SkillCatalog.bounded(input, "radius", 8, 2, 12);
    int uses = SkillCatalog.bounded(input, "max_uses", Math.min(256, count * 5), 1, 256);
    JsonObject plan = new JsonObject();
    plan.addProperty("name", "农作闭环 " + crop.block());
    plan.addProperty("seconds", 1200);
    plan.addProperty("wait_seconds", 60);
    JsonArray steps = new JsonArray();
    plan.add("steps", steps);
    boolean plant = flag(input, "plant", true),
        fertilize = flag(input, "fertilize", false),
        harvest = flag(input, "harvest", !plant || fertilize);
    var batch = new JsonObject();
    batch.addProperty("count", count);
    batch.addProperty("plant", plant);
    batch.addProperty("grow", fertilize);
    batch.addProperty("harvest", harvest);
    plan.add("farm_batch", batch);
    String source = input.has("source") ? input.get("source").getAsString().trim() : "";
    int seeds =
        count
            * (harvest ? 2 : 1); // Do not depend on future drops being picked up before replanting.
    if (!source.isBlank() && plant)
      add(steps, "fetch", args("place", source, "item", crop.seed(), "count", seeds));
    if (plant) {
      add(steps, "plant_crops", args("crop", crop.block(), "count", count, "radius", radius));
      var required = new JsonObject();
      required.addProperty(crop.seed(), seeds);
      steps.get(steps.size() - 1).getAsJsonObject().add("requires", required);
    }
    if (fertilize) {
      if (!source.isBlank())
        add(steps, "fetch", args("place", source, "item", "minecraft:bone_meal", "count", uses));
      add(
          steps,
          "fertilize_crops",
          args("crop", crop.block(), "count", count, "radius", radius, "max_uses", uses));
    }
    if (harvest)
      add(steps, "harvest_replant", args("crop", crop.block(), "count", count, "radius", radius));
    if (flag(input, "collect", true)) {
      add(steps, "collect_items", args("item", crop.product(), "mode", "sweep", "radius", radius));
      if (!crop.seed().equals(crop.product()))
        add(steps, "collect_items", args("item", crop.seed(), "mode", "sweep", "radius", radius));
    }
    if (steps.isEmpty()) throw new IllegalArgumentException("农作流程至少选择一个阶段");
    return plan;
  }

  private static boolean flag(JsonObject a, String k, boolean fallback) {
    if (!a.has(k)) return fallback;
    if (!a.get(k).isJsonPrimitive() || !a.getAsJsonPrimitive(k).isBoolean())
      throw new IllegalArgumentException(k + "必须是布尔值");
    return a.get(k).getAsBoolean();
  }

  private static void add(JsonArray steps, String tool, JsonObject args) {
    var s = new JsonObject();
    s.addProperty("tool", tool);
    s.add("args", args);
    steps.add(s);
  }

  private static JsonObject args(Object... values) {
    var o = new JsonObject();
    for (int i = 0; i < values.length; i += 2) {
      String k = (String) values[i];
      Object v = values[i + 1];
      if (v instanceof Number n) o.addProperty(k, n);
      else o.addProperty(k, (String) v);
    }
    return o;
  }
}
