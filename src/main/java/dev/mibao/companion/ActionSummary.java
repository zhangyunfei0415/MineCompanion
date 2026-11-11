package dev.mibao.companion;

import com.google.gson.*;

/** Human status is bounded and never embeds the structured tool response. */
public final class ActionSummary {
  public static String step(String tool) {
    return switch (tool) {
      case "craft_batch" -> "制作并核对材料";
      case "smelt_batch" -> "熔炼并等待产物";
      case "open_inventory" -> "准备背包界面";
      case "open_container" -> "打开工作站并同步";
      case "close_screen" -> "收尾并退出界面";
      case "place_block" -> "放置缺少的工作站";
      case "fetch" -> "从指定来源补齐材料";
      case "deposit" -> "交付本次采集物";
      default -> "执行本地操作";
    };
  }

  public static String describe(String result) {
    String s = result == null ? "操作结束，结果待观察" : result.strip();
    if (s.startsWith("{") || s.startsWith("["))
      try {
        var parsed = JsonParser.parseString(s);
        if (parsed.isJsonObject()) {
          var o = parsed.getAsJsonObject();
          if (o.has("objects"))
            return "世界观察已更新 · 本次返回 " + o.getAsJsonArray("objects").size() + " 个对象；详情见账本";
          if (o.has("connected"))
            return "现场观察已更新 · "
                + (o.has("screen") ? o.get("screen").getAsString() : "已读取背包与世界")
                + "；数量以同步数据核验";
          if (o.has("slots") || o.has("menu_slots")) return "界面观察已更新 · 完整槽位数据已交给控制器";
        }
        return "结构化观察已返回，完整内容见工具记录";
      } catch (RuntimeException ignored) {
        return "观察数据已返回，等待控制器核对格式";
      }
    s = s.replaceAll("[\\r\\n]+", " · ");
    return Rules.clean(s, 220);
  }
}
