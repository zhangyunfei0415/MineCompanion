package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/** Human view of structured evidence; raw values remain copyable through the tools. */
public final class RecipeText {
  public static String details(JsonObject j) {
    var b = new StringBuilder();
    b.append("配方 · ")
        .append(text(j, "title"))
        .append("\n")
        .append(text(j, "recipe_id"))
        .append("\n分类：")
        .append(text(j, "category"))
        .append("\n\n");
    if (j.has("workstations"))
      b.append("工作站：\n").append(stacks(j.getAsJsonArray("workstations"))).append("\n");
    if (j.has("slots"))
      for (var e : j.getAsJsonArray("slots")) {
        var s = e.getAsJsonObject();
        b.append(
                switch (text(s, "role")) {
                  case "INPUT" -> "原料";
                  case "OUTPUT" -> "产物";
                  case "CATALYST" -> "工具/催化物";
                  default -> "其他";
                })
            .append(" ")
            .append(text(s, "name"))
            .append("：\n")
            .append(stacks(s.getAsJsonArray("alternatives")))
            .append("\n");
      }
    b.append("这些是JEI展示槽，并非机器槽编号。\n加热、能量、时间、产出概率及工具是否消耗，需查看明确条件或适配器；不能从布局猜测。\n配方定义不证明库存或生产成功。\n");
    if (j.has("client_recipe")) {
      var c = j.getAsJsonObject("client_recipe");
      if (c.has("cooking_ticks")) b.append("同步配方时间：").append(c.get("cooking_ticks")).append("刻\n");
      if (c.has("kitchen_conditions")) {
        b.append("\n已读取的厨具条件：\n");
        for (var field : c.getAsJsonObject("kitchen_conditions").entrySet())
          b.append("  ").append(field.getKey()).append("：").append(field.getValue()).append('\n');
      }
      b.append("执行方式：").append(text(c, "execution_adapter"));
    }
    return b.toString();
  }

  private static String stacks(JsonArray a) {
    if (a == null || a.isEmpty()) return "  未提供\n";
    var b = new StringBuilder();
    int n = 0;
    for (var e : a) {
      if (n++ >= 24) {
        b.append("  更多替代项见结构化回执\n");
        break;
      }
      var j = e.getAsJsonObject();
      b.append("  ").append(text(j, "name"));
      if (j.has("item"))
        b.append(" · ").append(text(j, "item")).append(" ×").append(text(j, "count"));
      else if (j.has("fluid"))
        b.append(text(j, "fluid")).append(" ").append(text(j, "amount_mb")).append(" mB");
      if (j.has("nbt")) b.append(" [NBT: ").append(text(j, "nbt")).append("]");
      b.append('\n');
    }
    return b.toString();
  }

  public static String plan(JsonObject j) {
    var b =
        new StringBuilder(
            "只读制作计划\n目标：" + text(j, "target") + " 新增 ×" + text(j, "additional") + "\n\n");
    if (j.has("ready") && j.get("ready").getAsBoolean()) {
      b.append("材料计划可行；尚未执行，也未预留材料。\n");
      if (j.has("needs_workbench"))
        b.append("需要准备工作台：").append(j.get("needs_workbench")).append('\n');
      if (j.has("batches")) {
        b.append("\n逐批配方：\n");
        for (var e : j.getAsJsonArray("batches"))
          b.append("  ").append(e.getAsString()).append('\n');
      }
      if (j.has("consumed_stock")) b.append("\n随身材料消耗：\n").append(j.get("consumed_stock"));
    } else
      b.append("当前不能自动完成：\n")
          .append(text(j, "reason"))
          .append("\n\n缺料或特殊加工请查JEI配方，先核实工序、工具和设备。\n未知机器保留现场，不盲目投料。");
    return b.toString();
  }

  private static String text(JsonObject j, String k) {
    return j.has(k) && !j.get(k).isJsonNull() ? j.get(k).getAsString() : "";
  }

  private RecipeText() {}
}
