package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.*;

/** Reads only options already synchronized to the current client menu. */
public final class VanillaMenuModel {
  public static JsonObject observe() {
    var menu = Minecraft.getInstance().player.containerMenu;
    JsonObject result = new JsonObject();
    if (menu instanceof MerchantMenu merchant) {
      JsonArray offers = new JsonArray();
      for (int index = 0; index < merchant.getOffers().size(); index++) {
        var offer = merchant.getOffers().get(index);
        JsonObject row = new JsonObject();
        row.addProperty("index", index);
        row.add("cost_a", Actions.item(offer.getCostA()));
        row.add("cost_b", Actions.item(offer.getCostB()));
        row.add("result", Actions.item(offer.getResult()));
        row.addProperty("out_of_stock", offer.isOutOfStock());
        row.addProperty("uses", offer.getUses());
        offers.add(row);
      }
      result.add("offers", offers);
    }
    if (menu instanceof EnchantmentMenu enchant) {
      var input = enchant.getSlot(0).getItem();
      result.addProperty("input_enchantable", input.isEnchantable());
      result.addProperty("input_already_enchanted", input.isEnchanted());
      result.addProperty("player_level", Minecraft.getInstance().player.experienceLevel);
      result.addProperty("lapis_available", enchant.getSlot(1).getItem().getCount());
      JsonArray options = new JsonArray();
      for (int index = 0; index < 3; index++) {
        JsonObject option = new JsonObject();
        option.addProperty("index", index);
        option.addProperty("required_level", enchant.costs[index]);
        option.addProperty("lapis_cost", index + 1);
        option.addProperty("enchantment_hint_id", enchant.enchantClue[index]);
        option.addProperty("enchantment_hint_level", enchant.levelClue[index]);
        options.add(option);
      }
      result.add("enchant_options", options);
    }
    if (menu instanceof StonecutterMenu stonecutter) {
      JsonArray recipes = new JsonArray();
      for (int index = 0; index < stonecutter.getRecipes().size(); index++) {
        var recipe = stonecutter.getRecipes().get(index);
        var row = new JsonObject();
        row.addProperty("index", index);
        row.addProperty("recipe_id", recipe.getId().toString());
        row.add("result", Actions.item(recipe.getResultItem(Minecraft.getInstance().level.registryAccess())));
        recipes.add(row);
      }
      result.add("recipes", recipes);
    }
    if (menu instanceof LoomMenu loom) {
      JsonArray patterns = new JsonArray();
      for (int index = 0; index < loom.getSelectablePatterns().size(); index++) {
        var row = new JsonObject();
        row.addProperty("index", index);
        row.addProperty("pattern", loom.getSelectablePatterns().get(index).unwrapKey().map(k -> k.location().toString()).orElse("unknown"));
        patterns.add(row);
      }
      result.add("patterns", patterns);
    }
    if (menu instanceof AnvilMenu anvil) result.addProperty("level_cost", anvil.getCost());
    if (menu instanceof BrewingStandMenu brewing) {
      result.addProperty("brewing_ticks", brewing.getBrewingTicks());
      result.addProperty("fuel", brewing.getFuel());
    }
    result.addProperty("source", "current_client_menu");
    return result;
  }

  public static String select(JsonObject args) {
    GuiContext.validate(Agent.requiredView(args));
    var mc = Minecraft.getInstance();
    int index = Agent.number(args, "index", -1);
    var menu = mc.player.containerMenu;
    if (menu instanceof MerchantMenu merchant) {
      if (index < 0 || index >= merchant.getOffers().size())
        throw new IllegalArgumentException("[INVALID_ARGUMENT] 报价索引超出当前列表");
      if (merchant.getOffers().get(index).isOutOfStock())
        throw new IllegalArgumentException("[TRADE_OUT_OF_STOCK] 报价已缺货");
      merchant.setSelectionHint(index);
      merchant.tryMoveItems(index);
      mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSelectTradePacket(index));
    } else if (menu instanceof EnchantmentMenu enchant) {
      if (index < 0 || index >= 3 || enchant.costs[index] <= 0)
        throw new IllegalArgumentException("[ENCHANT_UNAVAILABLE] 检查无附魔可附魔物品、青金石、经验及当前同步档位");
      mc.gameMode.handleInventoryButtonClick(menu.containerId, index);
    } else if (menu instanceof StonecutterMenu stonecutter) {
      if (index < 0 || index >= stonecutter.getNumRecipes())
        throw new IllegalArgumentException("[INVALID_ARGUMENT] 切石配方索引超出范围");
      mc.gameMode.handleInventoryButtonClick(menu.containerId, index);
    } else if (menu instanceof LoomMenu loom) {
      if (index < 0 || index >= loom.getSelectablePatterns().size())
        throw new IllegalArgumentException("[INVALID_ARGUMENT] 图案索引超出当前列表");
      mc.gameMode.handleInventoryButtonClick(menu.containerId, index);
    } else throw new IllegalArgumentException("[UNSUPPORTED_MENU] 当前菜单没有已适配的选项");
    GuiContext.invalidate();
    return "选项已发送一次；请inspect_gui核对输入、产物和实际消耗";
  }

  private VanillaMenuModel() {}
}
