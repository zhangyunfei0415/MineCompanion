package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/**
 * Survival-safe non-destructive inventory and open-menu resynchronization job ({@code
 * resync_inventory}). Never requires creative mode and never discards held cursor items.
 */
final class ResyncInventoryJob extends Actions.Job {
  private InventorySession session;
  private boolean containerRequested;
  private long containerBefore;

  ResyncInventoryJob(Consumer<String> done) {
    super(done);
    TaskReadiness.requireWorld(false);
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  @Override
  void tick() {
    var mc = mc();
    if (mc.player == null || mc.level == null) {
      finish("失败：[WORLD_REQUIRED] 玩家不可用。");
      return;
    }
    var menu = mc.player.containerMenu;
    if (menu != mc.player.inventoryMenu) {
      if (!menu.getCarried().isEmpty()) {
        finish(
            reply(
                "blocked",
                "container_menu",
                false,
                MenuContext.diagnoseSync(),
                "[CURSOR_NOT_EMPTY] 鼠标当前持有物品，请先 stow_cursor 收好后再重同步"));
        return;
      }
      if (!containerRequested) {
        containerBefore = MenuContext.revision();
        containerRequested = true;
        Actions.status = "同步菜单 · 正在请求当前容器服务器快照";
        if (!MenuRefresh.request() && MenuContext.synchronizedNow()) {
          finish(reply("synced", "container_menu", true, "matched", "当前容器已一致同步"));
          return;
        }
        return;
      }
      if (MenuContext.revision() > containerBefore && MenuContext.synchronizedNow()) {
        finish(reply("synced", "container_menu", true, "matched", "容器菜单服务器快照已重同步一致"));
        return;
      }
      if (++ticks > 50) {
        finish(
            reply(
                MenuContext.synchronizedNow() ? "synced" : "timeout",
                "container_menu",
                MenuContext.synchronizedNow(),
                MenuContext.diagnoseSync(),
                MenuContext.synchronizedNow()
                    ? "容器菜单已同步一致"
                    : "容器重同步超时，可安全关闭界面再试或核对诊断字段"));
      }
      return;
    }
    if (session == null) {
      session = new InventorySession(false);
    }
    Actions.status = "同步背包 · 等待服务器完整槽位快照";
    int r = session.tick();
    if (r > 0) {
      var vol = InventorySession.volatileSlots();
      String hint =
          vol.size() > 0
              ? "已完成同步，并识别到 " + vol.size() + " 个带易变NBT的储能/进度物品已自动豁免假失配"
              : "服务器与客户端背包快照已完全一致";
      finish(reply("synced", "player_inventory", true, "matched", hint));
    } else if (r < 0) {
      finish(
          reply(
              "timeout",
              "player_inventory",
              false,
              InventorySession.diagnoseMismatch(),
              session.error));
    }
  }

  private JsonObject resultJson(
      String status, String mode, boolean confirmed, String diagnose, String hint) {
    var out = new JsonObject();
    out.addProperty("status", status);
    out.addProperty("mode", mode);
    out.addProperty("confirmed", confirmed);
    out.addProperty("sync_status", confirmed ? "server_confirmed" : MenuContext.syncStatus());
    out.addProperty("full_revision", InventoryEvidence.fullRevision);
    out.addProperty("slot_revision", InventoryEvidence.slotRevision);
    out.addProperty("diagnose", diagnose);
    out.addProperty("hint", hint);
    JsonArray volatileSlots = InventorySession.volatileSlots();
    out.add("volatile_slots", volatileSlots);
    return out;
  }

  private String reply(
      String status, String mode, boolean confirmed, String diagnose, String hint) {
    return resultJson(status, mode, confirmed, diagnose, hint).toString();
  }
}
