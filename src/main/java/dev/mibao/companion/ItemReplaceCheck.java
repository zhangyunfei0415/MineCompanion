package dev.mibao.companion;

import java.util.regex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** Recognizes self-targeted vanilla item replacement and checks the synchronized destination. */
final class ItemReplaceCheck {
  private static final Pattern COMMAND =
      Pattern.compile(
          "^item replace entity (\\S+) (\\S+) with ([a-z0-9_.:-]+)(\\{.*\\})?(?: (\\d+))?$");

  record Expected(int slot, String item, int count, String tag) {}

  static Expected parse(String command, String player) {
    var m = COMMAND.matcher(command);
    if (!m.matches() || !(m.group(1).equals("@s") || m.group(1).equals(player))) return null;
    String destination = m.group(2);
    int slot =
        switch (destination) {
          case "weapon.mainhand" -> Minecraft.getInstance().player.getInventory().selected;
          case "weapon.offhand" -> 40;
          case "armor.feet" -> 36;
          case "armor.legs" -> 37;
          case "armor.chest" -> 38;
          case "armor.head" -> 39;
          default -> -1;
        };
    if (destination.matches("hotbar\\.[0-8]")) slot = Integer.parseInt(destination.substring(7));
    if (destination.matches("inventory\\.(?:[0-9]|1[0-9]|2[0-6])"))
      slot = 9 + Integer.parseInt(destination.substring(10));
    if (slot < 0) return null;
    return new Expected(
        slot,
        m.group(3).contains(":") ? m.group(3) : "minecraft:" + m.group(3),
        m.group(5) == null ? 1 : Integer.parseInt(m.group(5)),
        m.group(4));
  }

  static boolean confirmed(Expected expected) {
    var actual = Minecraft.getInstance().player.getInventory().getItem(expected.slot());
    if (!PlayerEvidence.slotConfirmed(expected.slot())
        || !ItemStack.matches(actual, PlayerEvidence.inventorySlot(expected.slot()))
        || !net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(actual.getItem())
            .toString()
            .equals(expected.item())
        || actual.getCount() != expected.count()) return false;
    try {
      return expected.tag() == null
          ? !actual.hasTag()
          : net.minecraft.nbt.NbtUtils.compareNbt(
              net.minecraft.nbt.TagParser.parseTag(expected.tag()), actual.getTag(), true);
    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
      return false;
    }
  }
}
