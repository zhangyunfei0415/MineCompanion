package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/** Optional client block-entity observations. No server world/capability access. */
public final class MachineObservation {
  private record Receipt(long revision, String state) {}

  private static final Map<BlockPos, Receipt> receipts = new LinkedHashMap<>();
  private static long revision;

  public static void reset() {
    receipts.clear();
    revision = 0;
  }

  public static void received(BlockPos pos) {
    var state = state(pos);
    if (state == null) return;
    if (receipts.size() >= 256) receipts.remove(receipts.keySet().iterator().next());
    receipts.put(pos.immutable(), new Receipt(++revision, state));
  }

  public static long revision(BlockPos pos) {
    var r = receipts.get(pos);
    return r == null ? 0 : r.revision;
  }

  public static String state(BlockPos pos) {
    var o = observe(pos);
    return o == null ? null : o.toString();
  }

  public static boolean changed(BlockPos pos, String before, long since) {
    var r = receipts.get(pos);
    var now = state(pos);
    return before != null
        && r != null
        && r.revision > since
        && Objects.equals(r.state, now)
        && !before.equals(now);
  }

  public static JsonObject observe(BlockPos pos) {
    var mc = Minecraft.getInstance();
    if (mc.level == null || !mc.level.hasChunkAt(pos)) return null;
    var be = mc.level.getBlockEntity(pos);
    if (be == null) return null;
    try {
      var out = new JsonObject();
      String type = be.getClass().getName();
      if (type.equals("vectorwing.farmersdelight.common.block.entity.CuttingBoardBlockEntity")) {
        var stack = ((ItemStack) be.getClass().getMethod("getStoredItem").invoke(be)).copy();
        var item = new JsonObject();
        item.addProperty(
            "item",
            net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem())
                .toString());
        item.addProperty("count", stack.getCount());
        item.addProperty("fingerprint", WorldSense.fingerprint(stack));
        out.add("board_item", item);
        out.addProperty("kind", "farmersdelight_cutting_board");
      } else if (type.equals(
          "vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity")) {
        out.addProperty("kind", "farmersdelight_cooking_pot");
        out.addProperty("heated", (Boolean) be.getClass().getMethod("isHeated").invoke(be));
        out.addProperty(
            "inventory_rule",
            "Open synchronized menu to inspect ingredients, serving containers and output");
      } else if (type.equals(
              "com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity")
          || type.equals(
              "com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity")) {
        // Serialize the already-synchronized client object only. Exclude animation seed and
        // ticking clocks: time passing alone must never prove that our action succeeded.
        var tag = be.saveWithoutMetadata();
        out.addProperty(
            "kind", type.endsWith(".PotBlockEntity") ? "kaleido_pot" : "kaleido_stockpot");
        for (String key :
            List.of(
                "Inputs",
                "Status",
                "StirFryCount",
                "Result",
                "SoupBaseId",
                "TakeoutCount",
                "LidItem")) if (tag.contains(key)) out.addProperty(key, tag.get(key).toString());
        out.addProperty(
            "inventory_rule",
            "Client-synchronized block entity; verify consumed input and actual product"
                + " separately");
      } else if (type.startsWith("appeng.")) {
        var parts = BlockOrientation.parts(pos);
        if (parts == null) return null;
        out.addProperty("kind", "ae2_client_part_host");
        out.add("parts", parts);
      } else if (type.startsWith("com.simibubi.create.")
          || type.startsWith("mekanism.common.tile.")
          || type.startsWith("com.buuz135.functionalstorage.")) {
        out.addProperty("kind", type);
        var tag = be.saveWithoutMetadata();
        // Only stable fields from the client object, never server capability access.
        for (String key :
            List.of(
                "Speed",
                "Source",
                "InputInventory",
                "OutputInventory",
                "Inventory",
                "Items",
                "storage",
                "Storage"))
          if (tag.contains(key)) out.addProperty(key, Rules.clean(tag.get(key).toString(), 6000));
        if (type.startsWith("com.simibubi.create."))
          try {
            out.addProperty(
                "rotation_speed_client",
                ((Number) be.getClass().getMethod("getSpeed").invoke(be)).doubleValue());
          } catch (NoSuchMethodException ignored) {
          }
        if (type.startsWith("mekanism.common.tile."))
          try {
            var energy = be.getClass().getMethod("getEnergyContainer").invoke(be);
            out.addProperty(
                "energy_client",
                energy.getClass().getMethod("getEnergy").invoke(energy).toString());
          } catch (NoSuchMethodException ignored) {
          }
        out.addProperty("inventory_authoritative", false);
        out.addProperty(
            "availability",
            "client fields only; absent fields unknown, synchronized GUI/inventory receipts needed"
                + " for quantities");
      } else return null;
      out.addProperty("source", "loaded_client_block_entity");
      return out;
    } catch (ReflectiveOperationException | LinkageError e) {
      return null;
    }
  }

  public static String role(String menu, int slot) {
    if (menu.equals("vectorwing.farmersdelight.common.block.entity.container.CookingPotMenu"))
      return slot < 6
          ? "cooking_input"
          : slot == 6
              ? "meal_display"
              : slot == 7 ? "serving_container" : slot == 8 ? "cooking_output" : "unclassified";
    return "unclassified";
  }
}
