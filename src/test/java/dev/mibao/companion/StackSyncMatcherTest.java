package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.*;
import org.junit.jupiter.api.Test;

class StackSyncMatcherTest {
  @Test
  void thermalEnergyCellAndMekanismVolatileEnergyNormalizeEqualWhileStaticVariantsStayDistinct() {
    var thermalZero =
        JsonParser.parseString(
            "{\"BlockEntityTag\":{\"Energy\":0,\"EnergyMax\":1000000,\"EnergyRecv\":1000,\"EnergySend\":1000}}");
    var thermalCharged =
        JsonParser.parseString(
            "{\"BlockEntityTag\":{\"Energy\":48210,\"EnergyMax\":1000000,\"EnergyRecv\":1000,\"EnergySend\":1000},\"ForgeCaps\":{\"Parent\":{\"Energy\":48210}}}");
    assertTrue(VolatileTagPolicy.jsonMatchesSyncStable(thermalZero, thermalCharged));

    // Mekanism creative cube: OUTPUT vs INPUT must stay DISTINCT because componentConfig differs,
    // even when EnergyContainers ticks or ForgeCaps differs.
    var mekOutputA =
        JsonParser.parseString(
            "{\"mekData\":{\"EnergyContainers\":[{\"Container\":0,\"stored\":\"18446744073709551615.9999\"}],\"componentConfig\":{\"config0\":{\"side0\":4}}}}");
    var mekOutputB =
        JsonParser.parseString(
            "{\"mekData\":{\"EnergyContainers\":[{\"Container\":0,\"stored\":\"999.0\"}],\"componentConfig\":{\"config0\":{\"side0\":4}}},\"ForgeCaps\":{}}");
    var mekInput =
        JsonParser.parseString(
            "{\"mekData\":{\"componentConfig\":{\"config0\":{\"side0\":1}}}}");
    assertTrue(VolatileTagPolicy.jsonMatchesSyncStable(mekOutputA, mekOutputB));
    assertFalse(VolatileTagPolicy.jsonMatchesSyncStable(mekOutputA, mekInput));

    // Enchantments / custom names must NEVER be ignored.
    var swordA = JsonParser.parseString("{\"Enchantments\":[{\"id\":\"minecraft:sharpness\",\"lvl\":1}]}");
    var swordB = JsonParser.parseString("{\"Enchantments\":[{\"id\":\"minecraft:sharpness\",\"lvl\":5}]}");
    assertFalse(VolatileTagPolicy.jsonMatchesSyncStable(swordA, swordB));

    // Create Air (backtank) and Botania mana volatile top-level tags normalize equal.
    var backtankA = JsonParser.parseString("{\"Air\":450,\"Unbreakable\":1}");
    var backtankB = JsonParser.parseString("{\"Air\":899,\"Unbreakable\":1}");
    assertTrue(VolatileTagPolicy.jsonMatchesSyncStable(backtankA, backtankB));

    var manaRingA = JsonParser.parseString("{\"mana\":12500,\"tag\":\"ring\"}");
    var manaRingB = JsonParser.parseString("{\"mana\":48000,\"tag\":\"ring\"}");
    var manaRingOther = JsonParser.parseString("{\"mana\":48000,\"tag\":\"band\"}");
    assertTrue(VolatileTagPolicy.jsonMatchesSyncStable(manaRingA, manaRingB));
    assertFalse(VolatileTagPolicy.jsonMatchesSyncStable(manaRingA, manaRingOther));
  }

  @Test
  void menuSyncDiagnosticIncludesItemNamesAndVolatilePredicateWorksWithInventoryProof() {
    record SimSlot(String id, String jsonNbt, int count) {}

    var evidence =
        new MenuSyncEvidence<SimSlot>(
            (a, b) ->
                Objects.equals(a.id(), b.id())
                    && a.count() == b.count()
                    && VolatileTagPolicy.jsonMatchesSyncStable(
                        JsonParser.parseString(a.jsonNbt()), JsonParser.parseString(b.jsonNbt())));
    Object menuOwner = new Object();
    evidence.bind(menuOwner, 5);
    var srvCell =
        new SimSlot(
            "thermal:energy_cell",
            "{\"BlockEntityTag\":{\"Energy\":0,\"EnergyMax\":1000000}}",
            1);
    var cliCell =
        new SimSlot(
            "thermal:energy_cell",
            "{\"BlockEntityTag\":{\"Energy\":999,\"EnergyMax\":1000000},\"ForgeCaps\":{}}",
            1);
    var empty = new SimSlot("minecraft:air", "{}", 0);
    evidence.content(menuOwner, 5, 1, List.of(srvCell, empty), empty, 100L);

    assertTrue(evidence.ready(menuOwner, 5, List.of(cliCell, empty), empty, 105L, 0L));

    var changedIron = new SimSlot("minecraft:iron_ingot", "{}", 3);
    String mismatch =
        evidence.diagnoseMismatch(
            menuOwner,
            5,
            List.of(cliCell, changedIron),
            empty,
            (s, c) -> s.id() + "->" + c.id());
    assertTrue(mismatch.contains("slot:1(minecraft:air->minecraft:iron_ingot)"));

    assertTrue(
        InventoryProof.confirms(
            1,
            2,
            List.of(cliCell),
            List.of(srvCell),
            List.of(cliCell),
            empty,
            empty,
            empty,
            (a, b) ->
                Objects.equals(a.id(), b.id())
                    && VolatileTagPolicy.jsonMatchesSyncStable(
                        JsonParser.parseString(a.jsonNbt()), JsonParser.parseString(b.jsonNbt()))));
  }

  @Test
  void volatileDomainClassifiersIdentifyLootrWildChestsAndTerrainMobsCorrectly() {
    assertTrue(VolatileTagPolicy.isTerrainDestroyingMob("minecraft:creeper"));
    assertTrue(VolatileTagPolicy.isTerrainDestroyingMob("minecraft:ghast"));
    assertFalse(VolatileTagPolicy.isTerrainDestroyingMob("minecraft:zombie"));

    assertTrue(VolatileTagPolicy.isLootrContainer("lootr:lootr_chest"));
    assertTrue(VolatileTagPolicy.isLootrContainer("lootr:lootr_barrel"));
    assertFalse(VolatileTagPolicy.isLootrContainer("minecraft:chest"));

    assertTrue(VolatileTagPolicy.isWildLootableContainer("lootr:lootr_chest"));
    assertTrue(VolatileTagPolicy.isWildLootableContainer("minecraft:chest"));
    assertTrue(VolatileTagPolicy.isWildLootableContainer("minecraft:barrel"));
    assertTrue(VolatileTagPolicy.isWildLootableContainer("sophisticatedstorage:chest"));
    assertFalse(VolatileTagPolicy.isWildLootableContainer("minecraft:ender_chest"));
    assertFalse(VolatileTagPolicy.isWildLootableContainer("minecraft:furnace"));
  }
}
