package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** Refined Storage 1.12 client view, never a server controller or storage cache. */
public final class RsTerminalBridge {
  public record Entry(String id, ItemStack stack, long stored) {}

  private static Object call(Object o, String method) throws ReflectiveOperationException {
    return o.getClass().getMethod(method).invoke(o);
  }

  public static boolean available() {
    var mc = Minecraft.getInstance();
    return mc.player != null
        && mc.screen != null
        && mc.screen
            .getClass()
            .getName()
            .equals("com.refinedmods.refinedstorage.screen.grid.GridScreen");
  }

  public static List<Entry> entries() {
    if (!available()) throw new IllegalArgumentException("请先打开RS物品终端");
    try {
      var view = call(Minecraft.getInstance().screen, "getView");
      var rows = (List<?>) call(view, "getStacks");
      if (rows.size() > 4096) throw new IllegalArgumentException("RS条目超过4096预算");
      var out = new ArrayList<Entry>();
      for (var row : rows)
        if (row.getClass()
            .getName()
            .equals("com.refinedmods.refinedstorage.screen.grid.stack.ItemGridStack")) {
          var stack = ((ItemStack) call(row, "getStack")).copy();
          out.add(
              new Entry(
                  call(row, "getId").toString(),
                  stack,
                  ((Number) call(row, "getQuantity")).longValue()));
        }
      return out;
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalArgumentException("RS客户端接口不兼容：" + e.getClass().getSimpleName());
    }
  }

  public static Entry find(String item) {
    var rows =
        entries().stream().filter(e -> e.stored > 0 && Actions.match(e.stack, item)).toList();
    if (rows.size() != 1)
      throw new IllegalArgumentException("RS目标缺失或NBT变体不唯一；请清除搜索/过滤后rs_inventory并使用fingerprint");
    return rows.get(0);
  }

  public static long amount(String item) {
    return entries().stream()
        .filter(e -> Actions.match(e.stack, item))
        .mapToLong(e -> Math.max(0, e.stored))
        .sum();
  }

  public static void interaction(String id, String action) {
    if (!action.equals("PICKUP_SINGLE")) throw new IllegalArgumentException("RS仅支持逐个提取");
    try {
      var message =
          Class.forName("com.refinedmods.refinedstorage.network.grid.GridItemPullMessage")
              .getConstructor(UUID.class, int.class)
              .newInstance(UUID.fromString(id), 2);
      var handler =
          Class.forName("com.refinedmods.refinedstorage.RS").getField("NETWORK_HANDLER").get(null);
      handler.getClass().getMethod("sendToServer", Object.class).invoke(handler, message);
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalArgumentException("RS正常客户端动作不可用：" + e.getClass().getSimpleName());
    }
  }

  public static JsonObject observe(String query, int offset, int limit) {
    var all = entries();
    var rows = all.stream().filter(e -> query.isBlank() || Actions.match(e.stack, query)).toList();
    var out = new JsonObject();
    var list = new JsonArray();
    offset = Math.max(0, offset);
    limit = Math.max(1, Math.min(40, limit));
    for (int i = offset; i < Math.min(rows.size(), offset + limit); i++) {
      var e = rows.get(i);
      var item = Actions.item(e.stack);
      item.addProperty("stored", e.stored);
      item.addProperty("entry_id", e.id);
      list.add(item);
    }
    out.add("items", list);
    out.addProperty("source", "RS client grid view; current search/filter scope only");
    out.addProperty("matching_entries", rows.size());
    out.addProperty("has_more", offset + limit < rows.size());
    out.addProperty("rule", "stored来自真实客户端库存条目数量；当前过滤外仍未知，craftable展示不证明有货");
    return out;
  }
}
