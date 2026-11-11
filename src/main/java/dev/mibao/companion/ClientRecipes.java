package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.*;

/**
 * Shared read-only adapter for planning and preflight. Requests still go through normal recipe
 * tools.
 */
public final class ClientRecipes {
  public record Library(Map<String, CraftingRecipe> recipes, List<RecipePlanner.Recipe> plans) {}

  public static Library crafting() {
    var mc = Minecraft.getInstance();
    var recipes = new HashMap<String, CraftingRecipe>();
    var all = new ArrayList<RecipePlanner.Recipe>();
    for (var r : mc.level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
      if (!RecipeKnowledge.ordinary(r)) continue;
      var out = r.getResultItem(mc.level.registryAccess());
      if (!CraftBatchJob.outputAllowed(out)) continue;
      List<Set<String>> ingredients = new ArrayList<>();
      boolean safe = true;
      for (var ing : r.getIngredients())
        if (!ing.isEmpty()) {
          Set<String> ids = new TreeSet<>();
          for (var s : ing.getItems()) {
            if (s.hasTag() || s.hasCraftingRemainingItem()) {
              safe = false;
              break;
            }
            ids.add(BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
          }
          if (ids.isEmpty() || ids.size() > 128) safe = false;
          ingredients.add(ids);
        }
      if (!safe) continue;
      String id = r.getId().toString();
      recipes.put(id, r);
      all.add(
          new RecipePlanner.Recipe(
              id,
              BuiltInRegistries.ITEM.getKey(out.getItem()).toString(),
              out.getCount(),
              ingredients,
              !r.canCraftInDimensions(2, 2)));
    }
    return new Library(Map.copyOf(recipes), List.copyOf(all));
  }
}
