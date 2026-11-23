package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/** Optional client block-entity observations. No server world/capability access. */
public final class MachineObservation {
  private record Receipt(long revision, String state) {}

  private record Sample(long tick, long revision, String state) {}

  private static final Map<BlockPos, Receipt> receipts = new LinkedHashMap<>();
  private static final Map<BlockPos, Sample> samples = new HashMap<>();
  /** Block-entity classes whose client observation threw; skipped after repeated failures. */
  private static final Map<String, Integer> failures = new HashMap<>();
  private static long revision;

  public static void reset() {
    receipts.clear();
    samples.clear();
    failures.clear();
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
    // Serialize only after a newer block-entity receipt that differs from the baseline.
    if (before == null || r == null || r.revision <= since || before.equals(r.state)) return false;
    var now = sampled(pos, r.revision);
    return Objects.equals(r.state, now) && !before.equals(now);
  }

  /** {@link #state} cached per position, receipt revision and client game tick. */
  private static String sampled(BlockPos pos, long rev) {
    var mc = Minecraft.getInstance();
    long tick = mc.level == null ? -1 : mc.level.getGameTime();
    var cached = samples.get(pos);
    if (cached != null && cached.tick == tick && cached.revision == rev) return cached.state;
    var now = state(pos);
    if (samples.size() >= 64) samples.clear();
    samples.put(pos.immutable(), new Sample(tick, rev, now));
    return now;
  }

  public static JsonObject observe(BlockPos pos) {
    var mc = Minecraft.getInstance();
    if (mc.level == null || !mc.level.hasChunkAt(pos)) return null;
    var be = mc.level.getBlockEntity(pos);
    if (be == null) return null;
    String type = be.getClass().getName();
    if (failures.getOrDefault(type, 0) >= 3) return null;
    try {
      var out = new JsonObject();
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
      } else {
        var tag = be.saveWithoutMetadata();
        out.addProperty("kind", "generic_client_block_entity");
        out.addProperty("class_name", type);
        out.addProperty(
            "block_id",
            net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(mc.level.getBlockState(pos).getBlock())
                .toString());
        JsonArray keys = new JsonArray();
        JsonObject fields = new JsonObject();
        int totalChars = 0;
        for (String key : tag.getAllKeys()) {
          if (keys.size() >= 16) break;
          String lower = key.toLowerCase(Locale.ROOT);
          if (Set.of("x", "y", "z", "id", "forgecaps").contains(lower)
              || lower.contains("tick")
              || lower.contains("time")
              || lower.contains("anim")
              || lower.contains("uuid")
              || lower.contains("owner")
              || lower.contains("seed")) continue;
          String cleaned = Rules.clean(tag.get(key).toString(), 300);
          if (totalChars + cleaned.length() > 3000) break;
          keys.add(key);
          fields.addProperty(key, cleaned);
          totalChars += cleaned.length();
        }
        out.add("keys", keys);
        out.add("fields", fields);
        out.addProperty("inventory_authoritative", false);
        out.addProperty("availability", "generic_client_nbt_summary_only");
      }
      out.addProperty("source", "loaded_client_block_entity");
      return out;
    } catch (RuntimeException | ReflectiveOperationException | LinkageError e) {
      // Modded client block entities may throw on save/cast; report unknown, never fail the job.
      if (failures.size() >= 256) failures.clear();
      failures.merge(type, 1, Integer::sum);
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
