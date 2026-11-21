package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class UiSignatureTest {
  @Test
  void portableSignatureMatchesAcrossModpacksWhenMenuBlockAndSlotsMatch() {
    var slotsPackA =
        List.of(
            new UiSignature.SlotSpec("net.minecraft.world.inventory.Slot", 56, 17, false),
            new UiSignature.SlotSpec("net.minecraft.world.inventory.FurnaceFuelSlot", 56, 53, false),
            new UiSignature.SlotSpec("net.minecraft.world.inventory.FurnaceResultSlot", 116, 35, false),
            new UiSignature.SlotSpec("net.minecraft.world.inventory.Slot", 8, 84, true));
    var slotsPackB =
        List.of(
            new UiSignature.SlotSpec("net.minecraft.world.inventory.Slot", 56, 17, false),
            new UiSignature.SlotSpec("net.minecraft.world.inventory.FurnaceFuelSlot", 56, 53, false),
            new UiSignature.SlotSpec("net.minecraft.world.inventory.FurnaceResultSlot", 116, 35, false),
            new UiSignature.SlotSpec("net.minecraft.world.inventory.Slot", 8, 84, true));

    String sigA =
        UiSignature.portableSignature(
            "net.minecraft.world.inventory.FurnaceMenu", "minecraft:furnace", slotsPackA);
    String sigB =
        UiSignature.portableSignature(
            "net.minecraft.world.inventory.FurnaceMenu", "minecraft:furnace", slotsPackB);

    assertTrue(sigA.matches("[0-9a-f]{64}"));
    assertEquals(sigA, sigB);
  }

  @Test
  void layoutSignatureFallsBackAcrossDifferentMachineBlocksSharingSameMenuLayout() {
    var slots =
        List.of(
            new UiSignature.SlotSpec("mod.InputSlot", 64, 17, false),
            new UiSignature.SlotSpec("mod.OutputSlot", 116, 35, false));

    String sigTier1 =
        UiSignature.portableSignature("mod.MachineMenu", "mod:basic_enricher", slots);
    String sigTier2 =
        UiSignature.portableSignature("mod.MachineMenu", "mod:advanced_enricher", slots);
    assertNotEquals(sigTier1, sigTier2);

    String layout1 = UiSignature.layoutSignature("mod.MachineMenu", slots);
    String layout2 = UiSignature.layoutSignature("mod.MachineMenu", slots);
    assertTrue(layout1.matches("[0-9a-f]{64}"));
    assertEquals(layout1, layout2);
  }

  @Test
  void signatureChangesWhenSlotCoordinatesOrOwnershipChange() {
    var base = List.of(new UiSignature.SlotSpec("Slot", 10, 20, false));
    var shifted = List.of(new UiSignature.SlotSpec("Slot", 11, 20, false));
    var playerOwned = List.of(new UiSignature.SlotSpec("Slot", 10, 20, true));

    String s0 = UiSignature.portableSignature("mod.Menu", "mod:block", base);
    String s1 = UiSignature.portableSignature("mod.Menu", "mod:block", shifted);
    String s2 = UiSignature.portableSignature("mod.Menu", "mod:block", playerOwned);

    assertNotEquals(s0, s1);
    assertNotEquals(s0, s2);
  }
}
