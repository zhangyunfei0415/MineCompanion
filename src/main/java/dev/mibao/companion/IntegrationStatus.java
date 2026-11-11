package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;

/** Discovery is not runtime capability. Optional adapters fail independently of the core. */
public final class IntegrationStatus {
  private static Map<String, String> versions;

  private static Map<String, String> mods() {
    if (versions == null) {
      var m = new TreeMap<String, String>();
      ModList.get().getMods().forEach(v -> m.put(v.getModId(), v.getVersion().toString()));
      versions = Map.copyOf(m);
    }
    return versions;
  }

  public static JsonObject view() {
    var o = new JsonObject();
    var entries = new JsonArray();
    var mc = Minecraft.getInstance();
    for (String id :
        List.of(
            "minecraft",
            "jei",
            "ae2",
            "mekanism",
            "create",
            "ftbquests",
            "ftbchunks",
            "journeymap",
            "xaerominimap",
            "xaeroworldmap",
            "jade",
            "theoneprobe")) {
      var r = new JsonObject();
      boolean present = mods().containsKey(id);
      r.addProperty("mod", id);
      r.addProperty("installed", present);
      r.addProperty("version", mods().getOrDefault(id, ""));
      String state = "adapter_not_implemented", reason = "仅发现安装；不声称可读写其数据";
      if (!present) {
        state = "not_installed";
        reason = "可选组件未安装，不影响核心";
      } else if (id.equals("minecraft")) {
        state = mc.level != null ? "available" : "not_ready";
        reason = "只读已加载客户端方块状态；未加载未知";
      } else if (id.equals("jei")) {
        var j = RecipeBridge.status();
        state = j.has("ready") && j.get("ready").getAsBoolean() ? "available" : "not_ready";
        r.add("runtime", j);
        reason = "查询就绪不代表当前菜单支持转移；jei_transfer_recipe execute=false预检";
      } else if (id.equals("ae2")) {
        String workflow =
            mc.player == null ? "unsupported" : AeWorkflowBridge.kind(mc.player.containerMenu);
        state =
            AeTerminalBridge.available() || !workflow.equals("unsupported")
                ? "context_available"
                : "context_required";
        r.addProperty("current_workflow", workflow);
        reason = "打开AE终端可读取客户端网络；样板与JEI按当前菜单检查";
      } else if (id.equals("ftbquests")) {
        state = QuestBookBridge.available() ? "context_available" : "context_required";
        reason = "任务书打开时读取客户端可见控件";
      } else if (id.equals("mekanism") || id.equals("create")) {
        state = "generic_operations";
        reason = "方块属性/通用界面/现有技能；不保证全部机器专用语义";
      }
      r.addProperty("state", state);
      r.addProperty("reason", reason);
      r.addProperty("permissions", "操作仍受当前Agent授权与服务器权限约束");
      entries.add(r);
    }
    o.add("integrations", entries);
    o.add("navigation", BaritoneNavigation.status());
    o.addProperty("observed_at", System.currentTimeMillis());
    o.addProperty("map_rule", "历史地图不代表已加载区块、实时库存或机器配置；地图API尚未接入");
    return o;
  }

  private IntegrationStatus() {}
}
