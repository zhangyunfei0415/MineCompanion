package dev.mibao.companion;

import com.google.gson.*;
import net.minecraft.client.Minecraft;

public final class CommandSettings {
  public static CommandProfile profile() {
    if (Store.data.commands == null) Store.data.commands = new CommandProfile();
    return Store.data.commands;
  }

  public static JsonObject observe() {
    var o = Store.GSON.toJsonTree(profile()).getAsJsonObject();
    var mc = Minecraft.getInstance();
    o.addProperty("scope", Store.scope());
    o.addProperty("environment", mc.isLocalServer() ? "singleplayer" : "multiplayer");
    o.addProperty("server_permission_level_2", mc.player != null && mc.player.hasPermissions(2));
    var names = new JsonArray();
    if (mc.getConnection() != null)
      mc.getConnection().getCommands().getRoot().getChildren().stream()
          .limit(128)
          .forEach(n -> names.add(n.getName()));
    o.add("received_command_tree", names);
    o.addProperty("permission_note", "Agent授权和服务器权限分开；树缺少命令不代表插件命令一定不可用。实际由服务器决定。");
    return o;
  }

  public static JsonObject preview(String command) {
    var mc = Minecraft.getInstance();
    if (mc.player == null) throw new IllegalArgumentException("[NOT_CONNECTED] 需要当前世界坐标进行边界预览");
    var pos = mc.player.position();
    var plan = GameCommandPolicy.parse(command, profile(), pos.x, pos.y, pos.z);
    var result = Store.GSON.toJsonTree(plan).getAsJsonObject();
    result.addProperty("sent", false);
    result.addProperty("server_permission_verified", false);
    result.addProperty("agent_permission", PermissionView.current().get("mode").getAsString());
    result.addProperty("note", "边界预览不预留区域、不获取权限；真正发送时按当时位置重新校验");
    return result;
  }

  public static JsonObject details() {
    var out = observe();
    out.add("world_bindings", Store.GSON.toJsonTree(Store.data.commandBindings));
    out.addProperty("binding_rule", "本服参数独立保存；command_skill_bind parameters={}清除此技能本服绑定，通用模板保留");
    return out;
  }

  private CommandSettings() {}
}
