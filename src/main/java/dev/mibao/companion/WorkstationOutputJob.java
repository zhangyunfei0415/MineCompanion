package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;

/** Takes one displayed and approved output from a supported vanilla menu, then stows it. */
final class WorkstationOutputJob extends Actions.Job {
  private final AbstractContainerMenu menu;
  private final Screen screen;
  private final int output;
  private final long view;
  private final String filter;
  private ItemStack expected;
  private String fingerprint;
  private int phase, wait, countBefore, destination = -1;
  private long revision;

  WorkstationOutputJob(JsonObject args, Consumer<String> done) {
    super(done);
    var mc = Minecraft.getInstance();
    screen = mc.screen;
    menu = mc.player.containerMenu;
    if (screen == null)
      throw new IllegalArgumentException("[NO_STATION] 先打开原版工作站或交易菜单");
    if (menu instanceof StonecutterMenu) output = 1;
    else if (menu instanceof CartographyTableMenu || menu instanceof GrindstoneMenu
        || menu instanceof MerchantMenu) output = 2;
    else if (menu instanceof LoomMenu || menu instanceof SmithingMenu) output = 3;
    else throw new IllegalArgumentException("[UNSUPPORTED_MENU] 当前菜单没有受支持的产物槽");
    view = Agent.requiredView(args);
    filter = SkillCatalog.required(args, "item");
    if (filter.equals("*"))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 请指定预期产物，不接受通配符");
  }

  @Override void tick() {
    var mc = Minecraft.getInstance();
    if (mc.screen != screen || mc.player.containerMenu != menu) {
      result("STALE_VIEW", "菜单已变化，先核对鼠标和背包", 0);
      return;
    }
    if (phase == 0) {
      MenuContext.validateStored(view, output);
      if (!MenuContext.synchronizedNow() || !menu.getCarried().isEmpty()) {
        result("MENU_NOT_READY", "菜单需要同步且鼠标需要为空", 0);
        return;
      }
      var slot = menu.getSlot(output);
      expected = slot.getItem().copy();
      if (expected.isEmpty() || !slot.mayPickup(mc.player) || !Actions.match(expected, filter)) {
        result("WORKSTATION_OUTPUT_MISMATCH", "当前产物槽未显示指定物品", 0);
        return;
      }
      fingerprint = WorldSense.fingerprint(expected);
      countBefore = PlayerEvidence.total(PlayerEvidence.actual(fingerprint));
      for (int i = 0; i < menu.slots.size(); i++) {
        var candidate = menu.getSlot(i);
        var existing = candidate.getItem();
        if (candidate.container == mc.player.getInventory() && candidate.mayPlace(expected)
            && (existing.isEmpty() || ItemStack.isSameItemSameTags(existing, expected))
            && candidate.getMaxStackSize(expected) - existing.getCount() >= expected.getCount()) {
          destination = i;
          break;
        }
      }
      if (destination < 0) {
        result("NO_CAPACITY", "背包没有可容纳当前产物的单格空间", 0);
        return;
      }
      click(output);
      return;
    }
    if (MenuContext.revision() <= revision || !MenuContext.synchronizedNow()) {
      if (++wait > 120) result("MENU_NOT_SYNCED", "菜单回读未完成，请核对现场，避免重取", 0);
      return;
    }
    if (phase == 1) {
      if (!ItemStack.matches(menu.getCarried(), expected)) {
        result("OUTPUT_UNCONFIRMED", "鼠标物品与原产物不一致", 0);
        return;
      }
      var slot = menu.getSlot(destination);
      var existing = slot.getItem();
      if (!slot.mayPlace(expected)
          || !(existing.isEmpty() || ItemStack.isSameItemSameTags(existing, expected))
          || slot.getMaxStackSize(expected) - existing.getCount() < expected.getCount()) {
        result("NO_CAPACITY", "预留槽已变化，请先收好鼠标物品", 0);
        return;
      }
      click(destination);
      return;
    }
    int gained = PlayerEvidence.total(PlayerEvidence.actual(fingerprint)) - countBefore;
    boolean verified = menu.getCarried().isEmpty() && gained == expected.getCount();
    result(verified ? "NONE" : "OUTPUT_UNCONFIRMED",
        verified ? "已取出一次产物并核对实际入包" : "产物入包回读不一致，先检查现场", gained);
  }

  private void click(int slot) {
    revision = MenuContext.revision();
    wait = 0;
    phase++;
    Actions.click(slot, 0, ClickType.PICKUP);
    MenuRefresh.request();
  }

  private void result(String code, String detail, int gained) {
    var out = new JsonObject();
    out.addProperty("status", code.equals("NONE") ? "verified" : "partial");
    out.addProperty("business_success", code.equals("NONE"));
    out.addProperty("reason_code", code);
    out.addProperty("detail", detail);
    out.addProperty("output_slot", output);
    out.addProperty("confirmed_inventory_increase", Math.max(0, gained));
    if (fingerprint != null) out.addProperty("item_fingerprint", fingerprint);
    finish(out.toString());
  }
}
