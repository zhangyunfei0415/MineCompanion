package dev.mibao.companion;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;

/** A compact current scene; complete slots, NBT and world records are requested on demand. */
final class AgentObservation {
  static JsonObject current() {
    var mc = Minecraft.getInstance();
    var o = Actions.compact();
    if (mc.player == null) return o;
    o.addProperty("observed_at", System.currentTimeMillis());
    o.addProperty("health", mc.player.getHealth());
    o.addProperty("food", mc.player.getFoodData().getFoodLevel());
    o.addProperty("game_mode", mc.gameMode.getPlayerMode().getName());
    o.addProperty("goal", Agent.task);
    o.add("plan", ProgressBoard.snapshot());
    var inv = new JsonArray();
    for (int i = 0; i < 36; i++) {
      var s = mc.player.getInventory().getItem(i);
      if (s.isEmpty()) continue;
      var r = new JsonObject();
      r.addProperty("slot", i);
      r.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
      r.addProperty("count", s.getCount());
      r.addProperty("fingerprint", WorldSense.fingerprint(s));
      inv.add(r);
    }
    o.add("inventory", inv);
    o.add("cursor", Actions.item(mc.player.containerMenu.getCarried()));
    o.addProperty("screen", mc.screen == null ? "world" : mc.screen.getClass().getName());
    o.addProperty(
        "detail_tools",
        "inspect_gui/menu/block; integration_status; command_status; task_timeline; task_status");
    o.addProperty(
        "source", "current_client_view; inventory quantities are not action completion evidence");
    return o;
  }
}
