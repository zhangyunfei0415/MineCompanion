package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;

/**
 * Client-synchronized definitions, distinct from JEI display layouts and real inventory evidence.
 */
public final class RecipeKnowledge {
  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public static boolean ordinary(CraftingRecipe r) {
    if (r.isSpecial()
        || r.getIngredients().isEmpty()
        || r.getIngredients().size() > 9
        || !CraftBatchJob.outputAllowed(r.getResultItem(mc().level.registryAccess()))) return false;
    for (var i : r.getIngredients())
      for (var s : i.getItems()) if (s.hasTag() || s.hasCraftingRemainingItem()) return false;
    return true;
  }

  public static JsonObject byId(String id) {
    if (mc().level == null || id.isBlank()) return new JsonObject();
    return mc().level
        .getRecipeManager()
        .byKey(new ResourceLocation(id))
        .map(RecipeKnowledge::describe)
        .orElseGet(JsonObject::new);
  }

  private static JsonObject item(ItemStack s) {
    var o = new JsonObject();
    o.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
    o.addProperty("count", s.getCount());
    o.addProperty("fingerprint", WorldSense.fingerprint(s));
    if (s.hasTag()) o.addProperty("nbt", s.getTag().toString());
    return o;
  }

  public static JsonObject describe(Recipe<?> r) {
    var o = new JsonObject();
    o.addProperty("recipe_id", r.getId().toString());
    o.addProperty("type", String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(r.getType())));
    o.addProperty("source", "client synchronized recipe definition");
    o.addProperty("class", r.getClass().getName());
    o.add("output", item(r.getResultItem(mc().level.registryAccess())));
    var groups = new JsonArray();
    for (var i : r.getIngredients()) {
      var a = new JsonArray();
      Arrays.stream(i.getItems()).limit(128).forEach(s -> a.add(item(s)));
      groups.add(a);
    }
    o.add("ingredient_alternatives", groups);
    boolean supported = r instanceof CraftingRecipe c && ordinary(c);
    o.addProperty(
        "execution_adapter",
        supported ? "craft_item / craft_batch" : "observe or dedicated machine skill required");
    if (r instanceof AbstractCookingRecipe c) {
      o.addProperty("cooking_ticks", c.getCookingTime());
      o.addProperty("experience", c.getExperience());
      o.addProperty("fuel_known", false);
    }
    o.add("kitchen_conditions", KitchenRecipeInfo.inspect(r));
    o.addProperty("custom_conditions_complete", supported || r instanceof AbstractCookingRecipe);
    return o;
  }

  public static JsonObject lookup(String item, String role, int offset, int limit) {
    if (mc().level == null) throw new IllegalArgumentException("请先进入世界");
    var id = new ResourceLocation(item);
    if (!BuiltInRegistries.ITEM.containsKey(id)) throw new IllegalArgumentException("未知物品ID");
    if (!Set.of("input", "output").contains(role))
      throw new IllegalArgumentException("role为input或output");
    offset = RecipeBridge.offset(offset);
    limit = RecipeBridge.limit(limit);
    var o =
        RecipeLookupPage.page(
            mc().level.getRecipeManager().getRecipes(),
            r ->
                role.equals("output")
                    ? BuiltInRegistries.ITEM
                        .getKey(r.getResultItem(mc().level.registryAccess()).getItem())
                        .equals(id)
                    : r.getIngredients().stream()
                        .anyMatch(
                            i ->
                                Arrays.stream(i.getItems())
                                    .anyMatch(
                                        v ->
                                            BuiltInRegistries.ITEM.getKey(v.getItem()).equals(id))),
            RecipeKnowledge::describe,
            offset,
            limit);
    o.addProperty(
        "source", "client recipe manager; some JEI-only recipes and custom conditions are absent");
    return o;
  }

  public static JsonObject plan(String target, int count) {
    if (mc().level == null || mc().player == null) throw new IllegalArgumentException("请先进入世界");
    if (count < 1 || count > 64) throw new IllegalArgumentException("count须1..64新增产物");
    var stock = new TreeMap<String, Integer>();
    for (var s : mc().player.getInventory().items)
      if (!s.isEmpty() && !s.hasTag())
        stock.merge(
            BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
    var o = new JsonObject();
    o.addProperty("target", target);
    o.addProperty("additional", count);
    o.addProperty("source", "current ordinary inventory + synchronized crafting recipes");
    o.addProperty("executed", false);
    o.add("ordinary_stock", Store.GSON.toJsonTree(stock));
    try {
      var library = ClientRecipes.crafting();
      var p = new RecipePlanner(library.plans()).plan(target, count, stock, false);
      o.addProperty("ready", true);
      o.add("batches", Store.GSON.toJsonTree(p.batches()));
      o.add("consumed_stock", Store.GSON.toJsonTree(p.consumedStock()));
      o.addProperty("needs_workbench", p.needsTable());
      o.addProperty(
          "next",
          "craft_item executes with permissions and verifies every batch; this plan reserves no"
              + " items");
    } catch (IllegalArgumentException e) {
      o.addProperty("ready", false);
      o.addProperty("reason", e.getMessage());
      o.add("candidates", lookup(target, "output", 0, 5));
      o.addProperty(
          "next",
          "JEI lookup_recipes + recipe_details for machines, heat, tools or missing input; no"
              + " generic automatic machine execution");
    }
    return o;
  }

  private RecipeKnowledge() {}
}
