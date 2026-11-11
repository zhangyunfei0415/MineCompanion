package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/** Read only explicitly reviewed public recipe accessors; optional kitchen mods are not linked. */
public final class KitchenRecipeInfo {
  public static JsonObject inspect(Object recipe) {
    var o = new JsonObject();
    String type = recipe.getClass().getName();
    var methods = new LinkedHashMap<String, String>();
    if (type.equals("vectorwing.farmersdelight.common.crafting.CookingPotRecipe")) {
      methods.put("getCookTime", "cooking_ticks");
      methods.put("getOutputContainer", "serving_container");
      o.addProperty("workstation", "farmersdelight:cooking_pot");
      o.addProperty("heat_state", "must observe actual heated pot; recipe alone does not prove it");
    } else if (type.startsWith("com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.")) {
      String simple = recipe.getClass().getSimpleName();
      if (Set.of(
              "PotRecipe", "FlexPotRecipe", "StockpotRecipe", "FlexStockpotRecipe", "TeapotRecipe")
          .contains(simple)) methods.put("time", "processing_time_raw");
      if (simple.equals("SteamerRecipe")) methods.put("getCookTick", "cooking_ticks");
      if (simple.equals("BambooTrayRecipe")) {
        methods.put("getDuration", "processing_duration");
        methods.put("getSubtype", "processing_kind");
      }
      if (simple.equals("MillstoneRecipe")) methods.put("results", "chance_outputs");
      if (simple.equals("TeapotRecipe")) {
        methods.put("teaFluid", "required_tea_fluid");
        methods.put("ingredientCount", "ingredient_count");
        try {
          o.addProperty("output_count", recipe.getClass().getField("OUTPUT_COUNT").getInt(null));
        } catch (ReflectiveOperationException e) {
          o.addProperty("output_count_unknown", true);
        }
      }
      if (Set.of("PotRecipe", "FlexPotRecipe").contains(simple))
        methods.put("stirFryCount", "stir_fry_count");
      if (Set.of("PotRecipe", "FlexPotRecipe", "StockpotRecipe", "FlexStockpotRecipe")
          .contains(simple)) methods.put("carrier", "serving_container_alternatives");
      if (Set.of("StockpotRecipe", "FlexStockpotRecipe").contains(simple))
        methods.put("soupBase", "required_soup_base");
      if (simple.equals("ChoppingBoardRecipe")) {
        methods.put("getCutCount", "cut_count");
        methods.put("getResults", "all_results");
      }
      o.addProperty(
          "time_units",
          "raw accessor value; unit/heat/tool semantics need category-specific verification");
    }
    for (var e : methods.entrySet())
      try {
        o.add(e.getValue(), value(recipe.getClass().getMethod(e.getKey()).invoke(recipe)));
      } catch (ReflectiveOperationException | RuntimeException error) {
        o.addProperty(e.getValue() + "_unknown", error.getClass().getSimpleName());
      }
    if (!methods.isEmpty()) {
      o.addProperty(
          "source", "reviewed public accessors on current client-synchronized recipe object");
      o.addProperty("execution_supported", false);
    }
    return o;
  }

  private static JsonElement value(Object v) {
    if (v instanceof ItemStack s) {
      var o = new JsonObject();
      o.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
      o.addProperty("count", s.getCount());
      o.addProperty("fingerprint", WorldSense.fingerprint(s));
      return o;
    }
    if (v instanceof Ingredient i) {
      var a = new JsonArray();
      for (var s : i.getItems()) a.add(value(s));
      return a;
    }
    if (v instanceof List<?> list) {
      var a = new JsonArray();
      list.stream().limit(64).forEach(x -> a.add(value(x)));
      return a;
    }
    if (v != null
        && v.getClass()
            .getName()
            .equals("com.github.ysbbbbbb.kaleidoscopecookery.crafting.output.RandomOutput"))
      try {
        var o = new JsonObject();
        o.add("stack", value(v.getClass().getMethod("stack").invoke(v)));
        o.add("chance", value(v.getClass().getMethod("chance").invoke(v)));
        return o;
      } catch (ReflectiveOperationException e) {
        return new JsonPrimitive("probability unknown");
      }
    if (v instanceof Number n) return new JsonPrimitive(n);
    return new JsonPrimitive(String.valueOf(v));
  }

  private KitchenRecipeInfo() {}
}
