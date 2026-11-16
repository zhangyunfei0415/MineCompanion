package dev.mibao.companion;

import java.util.Comparator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** The three vanilla furnaces share inventory execution, but keep distinct recipe types. */
record CookingStation(
    String id, Block block, Class<?> menu, RecipeType<? extends AbstractCookingRecipe> recipes) {
  static CookingStation named(String id) {
    return switch (id) {
      case "furnace", "minecraft:furnace" ->
          new CookingStation(
              "minecraft:furnace", Blocks.FURNACE, FurnaceMenu.class, RecipeType.SMELTING);
      case "blast_furnace", "minecraft:blast_furnace" ->
          new CookingStation(
              "minecraft:blast_furnace",
              Blocks.BLAST_FURNACE,
              BlastFurnaceMenu.class,
              RecipeType.BLASTING);
      case "smoker", "minecraft:smoker" ->
          new CookingStation(
              "minecraft:smoker", Blocks.SMOKER, SmokerMenu.class, RecipeType.SMOKING);
      default ->
          throw new IllegalArgumentException(
              "[INVALID_ARGUMENT] station为furnace/blast_furnace/smoker");
    };
  }

  static CookingStation current() {
    var menu = Minecraft.getInstance().player.containerMenu;
    if (menu.getClass() == FurnaceMenu.class) return named("furnace");
    if (menu.getClass() == BlastFurnaceMenu.class) return named("blast_furnace");
    if (menu.getClass() == SmokerMenu.class) return named("smoker");
    throw new IllegalArgumentException("[UNSUPPORTED_MENU] 请打开原版熔炉、高炉或烟熏炉");
  }

  AbstractCookingRecipe recipe(String input) {
    return Minecraft.getInstance().level.getRecipeManager().getAllRecipesFor(recipes).stream()
        .filter(
            r ->
                r.getId().getNamespace().equals("minecraft")
                    && java.util.Arrays.stream(r.getIngredients().get(0).getItems())
                        .anyMatch(
                            s ->
                                !s.hasTag()
                                    && BuiltInRegistries.ITEM
                                        .getKey(s.getItem())
                                        .toString()
                                        .equals(input)))
        .sorted(Comparator.comparing(r -> r.getId().toString()))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("[RECIPE_UNAVAILABLE] " + id + "没有该输入的普通原版加工配方"));
  }
}
