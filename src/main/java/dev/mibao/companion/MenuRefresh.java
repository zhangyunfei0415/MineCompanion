package dev.mibao.companion;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

/**
 * Only vanilla menus have a source-reviewed no-op resync contract; custom menus stay conservative.
 */
public final class MenuRefresh {
  public static boolean reviewedMachine() {
    var m = Minecraft.getInstance().player.containerMenu;
    String c = m.getClass().getName();
    return c.equals("com.refinedmods.refinedstorage.container.GridContainerMenu")
        || c.equals("com.refinedmods.refinedstorage.container.DiskDriveContainerMenu")
        || c.equals("mekanism.common.inventory.container.tile.MekanismTileContainer");
  }

  public static boolean vanillaWhitelisted() {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.player.containerMenu == null) return false;
    Class<?> c = mc.player.containerMenu.getClass();
    return reviewedMachine()
        || ModMenuReceipts.supported()
        || AeTerminalBridge.available()
        || Set.of(
                InventoryMenu.class,
                CraftingMenu.class,
                FurnaceMenu.class,
                BlastFurnaceMenu.class,
                SmokerMenu.class,
                AnvilMenu.class,
                EnchantmentMenu.class,
                MerchantMenu.class,
                BrewingStandMenu.class,
                BeaconMenu.class,
                LecternMenu.class,
                GrindstoneMenu.class,
                StonecutterMenu.class,
                LoomMenu.class,
                CartographyTableMenu.class,
                SmithingMenu.class,
                ChestMenu.class,
                HopperMenu.class,
                DispenserMenu.class,
                ShulkerBoxMenu.class)
            .contains(c)
        || c.getName()
            .equals("vectorwing.farmersdelight.common.block.entity.container.CookingPotMenu");
  }

  public static boolean supported() {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.player.containerMenu == null) return false;
    if (vanillaWhitelisted()) return true;
    // Standard Forge/Minecraft AbstractContainerMenu containers that already received an initial
    // ClientboundContainerSetContentPacket handle stateId=-1, slot=-1 as a harmless no-op that
    // triggers sendAllDataToRemote(). Exclude client-only Creative item picker tabs.
    if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen)
      return false;
    return MenuContext.receivedFullContent();
  }

  public static String mode() {
    return vanillaWhitelisted()
        ? "whitelist"
        : supported() ? "container_packet_sync" : "unsupported";
  }

  public static boolean request() {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.getConnection() == null || mc.player.containerMenu == null)
      return false;
    if (!supported()) return false;
    mc.getConnection()
        .send(
            new ServerboundContainerClickPacket(
                mc.player.containerMenu.containerId,
                -1,
                -1,
                0,
                ClickType.PICKUP,
                ItemStack.EMPTY,
                new Int2ObjectOpenHashMap<>()));
    return true;
  }

  public static String role(int index) {
    var m = Minecraft.getInstance().player.containerMenu;
    if (m instanceof InventoryMenu) return InventoryLayout.role(index);
    if (m instanceof AbstractFurnaceMenu)
      return index == 0
          ? "smelting_input"
          : index == 1 ? "fuel" : index == 2 ? "output" : "player_inventory";
    if (m instanceof CraftingMenu)
      return index == 0 ? "craft_result" : index <= 9 ? "craft_input" : "player_inventory";
    if (m instanceof MerchantMenu)
      return index < 2 ? "trade_input" : index == 2 ? "trade_result" : "player_inventory";
    if (m instanceof EnchantmentMenu)
      return index == 0 ? "enchant_item" : index == 1 ? "enchant_lapis" : "player_inventory";
    if (m instanceof AnvilMenu || m instanceof GrindstoneMenu)
      return index < 2 ? "combine_input" : index == 2 ? "combine_result" : "player_inventory";
    if (m instanceof BrewingStandMenu)
      return index < 3
          ? "potion"
          : index == 3 ? "brewing_ingredient" : index == 4 ? "brewing_fuel" : "player_inventory";
    if (m instanceof BeaconMenu) return index == 0 ? "beacon_payment" : "player_inventory";
    if (m instanceof LecternMenu) return index == 0 ? "lectern_book" : "unknown";
    if (m instanceof StonecutterMenu)
      return index == 0 ? "stonecut_input" : index == 1 ? "stonecut_result" : "player_inventory";
    if (m instanceof LoomMenu)
      return index == 0
          ? "banner"
          : index == 1
              ? "dye"
              : index == 2 ? "pattern_item" : index == 3 ? "banner_result" : "player_inventory";
    if (m instanceof SmithingMenu)
      return index == 0
          ? "smithing_template"
          : index == 1
              ? "smithing_base"
              : index == 2
                  ? "smithing_addition"
                  : index == 3 ? "smithing_result" : "player_inventory";
    if (m instanceof CartographyTableMenu)
      return index == 0
          ? "map_input"
          : index == 1 ? "map_material" : index == 2 ? "map_result" : "player_inventory";
    return MachineObservation.role(m.getClass().getName(), index);
  }
}
