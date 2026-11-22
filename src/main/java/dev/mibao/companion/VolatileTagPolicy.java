package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/**
 * Pure policy for identifying volatile NBT tags (energy, fluids, gas, progress, lazy Forge caps)
 * and domain entity/container classes without referencing {@code net.minecraft.*}.
 */
public final class VolatileTagPolicy {
  private static final Set<String> TOP_LEVEL_VOLATILE =
      Set.of(
          "ForgeCaps",
          "energy",
          "Energy",
          "EnergyStored",
          "StoredEnergy",
          "MaxEnergy",
          "EnergyRecv",
          "EnergySend",
          "charge",
          "Charge",
          "Fluid",
          "FluidName",
          "Amount",
          "Gas",
          "GasName",
          "Progress",
          "BurnTime",
          "CookTime",
          "CookTimeTotal",
          "TickCount",
          "LastTick",
          "LastUpdate",
          "Cooldown",
          "Air",
          "Mana",
          "mana",
          "Fuel",
          "fuel",
          "heat",
          "temperature",
          "Stored");

  private static final Set<String> BLOCK_ENTITY_VOLATILE =
      Set.of(
          "ForgeCaps",
          "Energy",
          "EnergyStored",
          "energy",
          "StoredEnergy",
          "CurEnergy",
          "RF",
          "FE",
          "Buffer",
          "Tank",
          "Tanks",
          "Fluid",
          "FluidName",
          "Amount",
          "Gas",
          "GasName",
          "Heat",
          "Progress",
          "BurnTime",
          "CookTime",
          "CookTimeTotal",
          "Active",
          "stored",
          "Stored",
          "EnergyContainers",
          "FluidTanks",
          "GasTanks",
          "InfusionTanks",
          "PigmentTanks",
          "SlurryTanks",
          "HeatCapacitors");

  private static final Set<String> MEK_DATA_VOLATILE =
      Set.of(
          "EnergyContainers",
          "FluidTanks",
          "GasTanks",
          "InfusionTanks",
          "PigmentTanks",
          "SlurryTanks",
          "HeatCapacitors",
          "energyStored",
          "stored");

  private static final Set<String> TERRAIN_DESTROYING_MOBS =
      Set.of(
          "minecraft:creeper",
          "minecraft:ghast",
          "minecraft:wither",
          "minecraft:ender_dragon");

  private VolatileTagPolicy() {}

  public static boolean isVolatileKey(String parentKey, String key) {
    if (key == null || key.isEmpty()) return false;
    if ("ForgeCaps".equals(key)) return true;
    if ("BlockEntityTag".equals(parentKey) || "EntityTag".equals(parentKey))
      return BLOCK_ENTITY_VOLATILE.contains(key);
    if ("mekData".equals(parentKey)) return MEK_DATA_VOLATILE.contains(key);
    return TOP_LEVEL_VOLATILE.contains(key);
  }

  /** Normalize JSON representations of item NBT for offline contract verification. */
  public static JsonElement stripVolatileJson(JsonElement element, String parentKey) {
    if (element == null || !element.isJsonObject()) return element == null ? JsonNull.INSTANCE : element.deepCopy();
    JsonObject src = element.getAsJsonObject();
    JsonObject out = new JsonObject();
    for (var entry : src.entrySet()) {
      String key = entry.getKey();
      if (isVolatileKey(parentKey, key)) continue;
      JsonElement val = entry.getValue();
      if (val != null && val.isJsonObject()) {
        JsonElement stripped = stripVolatileJson(val, key);
        if (stripped.isJsonObject() && stripped.getAsJsonObject().size() == 0
            && ("BlockEntityTag".equals(key) || "mekData".equals(key) || "EntityTag".equals(key)))
          continue;
        out.add(key, stripped);
      } else {
        out.add(key, val == null ? JsonNull.INSTANCE : val.deepCopy());
      }
    }
    return out;
  }

  public static boolean jsonMatchesSyncStable(JsonElement a, JsonElement b) {
    JsonElement na = stripVolatileJson(a, "");
    JsonElement nb = stripVolatileJson(b, "");
    return Objects.equals(na, nb);
  }

  public static boolean isTerrainDestroyingMob(String entityId) {
    return entityId != null && TERRAIN_DESTROYING_MOBS.contains(entityId);
  }

  public static boolean isLootrContainer(String blockId) {
    return blockId != null && blockId.startsWith("lootr:");
  }

  public static boolean isWildLootableContainer(String blockId) {
    if (blockId == null || blockId.isBlank()) return false;
    if (isLootrContainer(blockId)) return true;
    int colon = blockId.indexOf(':');
    String path = colon >= 0 ? blockId.substring(colon + 1) : blockId;
    if (path.equals("ender_chest")) return false;
    return path.equals("chest")
        || path.equals("trapped_chest")
        || path.equals("barrel")
        || path.endsWith("_chest")
        || path.endsWith("_barrel")
        || path.endsWith("_crate")
        || path.equals("shulker_box")
        || path.endsWith("_shulker_box");
  }
}
