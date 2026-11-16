package dev.mibao.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;

final class MiningTools {
  static boolean matchesPolicy(ItemStack stack, String policy) {
    return switch (policy) {
      case "silk" -> EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SILK_TOUCH, stack) > 0;
      case "fortune" ->
          EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_FORTUNE, stack) > 0;
      case "auto" -> EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SILK_TOUCH, stack) == 0;
      default -> true;
    };
  }

  static int choose(BlockState state, String policy, int reserve) {
    var inventory = Minecraft.getInstance().player.getInventory();
    int selected = -1;
    float speed = -1;
    for (int i = 0; i < 36; i++) {
      var stack = inventory.getItem(i);
      if (stack.isEmpty()
          || !matchesPolicy(stack, policy)
          || stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= reserve
          || state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) continue;
      if (stack.getDestroySpeed(state) > speed) {
        speed = stack.getDestroySpeed(state);
        selected = i;
      }
    }
    return selected;
  }

  static String craft(BlockState state, int reserve) {
    for (var tool :
        new net.minecraft.world.item.Item[] {
          Items.STONE_PICKAXE, Items.WOODEN_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE
        }) {
      var stack = tool.getDefaultInstance();
      if (stack.isDamageableItem() && stack.getMaxDamage() <= reserve) continue;
      if (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state))
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(tool).toString();
    }
    return "";
  }

  private MiningTools() {}
}
