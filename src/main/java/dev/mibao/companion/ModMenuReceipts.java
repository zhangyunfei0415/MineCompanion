package dev.mibao.companion;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** Passive post-handler hook for source-reviewed Sophisticated Core S2C messages. */
public final class ModMenuReceipts {
  public static boolean supported() {
    var mc = Minecraft.getInstance();
    if (mc.player == null) return false;
    if (mc.player
        .containerMenu
        .getClass()
        .getName()
        .equals("com.robertx22.mine_and_slash.capability.player.container.BackpackMenu"))
      return true;
    for (Class<?> c = mc.player.containerMenu.getClass(); c != null; c = c.getSuperclass())
      if (c.getName().equals("net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase"))
        return true;
    return false;
  }

  private static Object field(Object message, String name) throws ReflectiveOperationException {
    var f = message.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(message);
  }

  @SuppressWarnings("unchecked")
  public static void received(Object message) {
    try {
      var mc = Minecraft.getInstance();
      if (mc.player == null) return;
      String type = message.getClass().getName();
      if (type.startsWith("com.robertx22.mine_and_slash.vanilla_mc.packets.backpack.SetBackpack")) {
        int id = (Integer) field(message, "containerId"),
            state = (Integer) field(message, "stateId");
        if (mc.player.containerMenu.containerId != id) return;
        if (type.endsWith("ContentPacket")) {
          var values =
              ((List<ItemStack>) field(message, "items")).stream().map(ItemStack::copy).toList();
          if (values.size() != mc.player.containerMenu.slots.size()) return;
          var cursor = ((ItemStack) field(message, "carriedItem")).copy();
          MenuContext.content(id, state, values, cursor);
          PlayerEvidence.content(id, values);
          PlayerEvidence.cursor(cursor);
        } else {
          int slot = (Integer) field(message, "slot");
          var value = ((ItemStack) field(message, "itemStack")).copy();
          MenuContext.slot(id, state, slot, value);
          PlayerEvidence.slot(id, slot, value);
        }
        return;
      }
      int id = (Integer) field(message, "windowId"), state = (Integer) field(message, "stateId");
      var menu = mc.player.containerMenu;
      if (id != menu.containerId) return;
      if (type.equals("net.p3pp3rf1y.sophisticatedcore.network.SyncContainerStacksMessage")) {
        var values =
            ((List<ItemStack>) field(message, "itemStacks")).stream().map(ItemStack::copy).toList();
        var cursor = ((ItemStack) field(message, "carriedStack")).copy();
        if (values.size() < menu.slots.size() || values.size() > 1024) return;
        var visible = values.subList(0, menu.slots.size());
        MenuContext.content(id, state, visible, cursor);
        PlayerEvidence.content(id, visible);
        PlayerEvidence.cursor(cursor);
      } else if (type.equals("net.p3pp3rf1y.sophisticatedcore.network.SyncSlotStackMessage")) {
        int slot = (Integer) field(message, "slotNumber");
        var stack = ((ItemStack) field(message, "stack")).copy();
        MenuContext.slot(id, state, slot, stack);
        PlayerEvidence.slot(id, slot, stack);
      }
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
      /* Unsupported versions remain unconfirmed. */
    }
  }
}
