package dev.mibao.companion;

import com.google.gson.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

/**
 * Uses the native screen callback for custom/virtual slots; no guessed offsets or blind retries.
 */
final class GuiSlotTargetJob extends Actions.Job {
  private final long view;
  private final int slot, button;
  private Object screen, menu;
  private ItemStack before, cursor;
  private long revision;
  private boolean sent, handled;

  GuiSlotTargetJob(long view, int slot, int button, Consumer<String> done) {
    super(done);
    if (button < 0 || button > 1)
      throw new IllegalArgumentException("[INVALID_ARGUMENT] button=0/1");
    this.view = view;
    this.slot = slot;
    this.button = button;
  }

  @Override
  void tick() {
    var mc = Minecraft.getInstance();
    if (!sent) {
      GuiContext.validateSlot(view, slot);
      if (!(mc.screen instanceof AbstractContainerScreen<?> s))
        throw new IllegalArgumentException("[WRONG_CONTEXT] 需要容器界面");
      var target = mc.player.containerMenu.getSlot(slot);
      double x = s.getGuiLeft() + target.x + 8, y = s.getGuiTop() + target.y + 8;
      if (!target.isActive() || x < 0 || y < 0 || x >= s.width || y >= s.height)
        throw new IllegalArgumentException("[TARGET_OFFSCREEN] 槽位不可见，未点击");
      before = target.getItem().copy();
      cursor = mc.player.containerMenu.getCarried().copy();
      screen = s;
      menu = mc.player.containerMenu;
      revision = MenuContext.revision();
      AgentPointer.move(x, y);
      handled = s.mouseClicked(x, y, button);
      s.mouseReleased(x, y, button);
      sent = true;
      MenuContext.consumeObservation();
      GuiContext.invalidate();
      return;
    }
    if (++ticks < 12) return;
    var out = new JsonObject();
    out.addProperty("status", "dispatched");
    out.addProperty("native_handled", handled);
    out.addProperty("business_success", false);
    boolean same = screen == mc.screen && menu == mc.player.containerMenu;
    out.addProperty("same_menu", same);
    if (same) {
      var actual = mc.player.containerMenu.getSlot(slot).getItem();
      out.addProperty("target_changed", !ItemStack.matches(before, actual));
      out.addProperty(
          "cursor_changed", !ItemStack.matches(cursor, mc.player.containerMenu.getCarried()));
      out.addProperty("server_menu_updated", MenuContext.revision() > revision);
      out.add("target", Actions.item(actual));
      out.add("cursor", Actions.item(mc.player.containerMenu.getCarried()));
    }
    out.addProperty("next", "重新inspect_gui核对；虚拟配方更改不一定移动cursor。未重复点击。编码后核对样板NBT。");
    finish(out.toString());
  }
}
