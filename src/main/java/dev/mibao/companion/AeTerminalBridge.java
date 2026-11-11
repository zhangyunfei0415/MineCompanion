package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** AE2 15.x optional public client menu/repository bridge. Never accesses a server grid. */
public final class AeTerminalBridge {
  public record Entry(
      long serial, ItemStack stack, long stored, long requestable, boolean craftable) {}

  private static final Map<Class<?>, Map<String, java.lang.reflect.Method>> accessors =
      new HashMap<>();

  private static Object call(Object target, String method) throws ReflectiveOperationException {
    var methods = accessors.computeIfAbsent(target.getClass(), c -> new HashMap<>());
    var accessor = methods.get(method);
    if (accessor == null) {
      accessor = target.getClass().getMethod(method);
      methods.put(method, accessor);
    }
    return accessor.invoke(target);
  }

  public static boolean available() {
    try {
      return Class.forName("appeng.menu.me.common.MEStorageMenu")
          .isInstance(Minecraft.getInstance().player.containerMenu);
    } catch (ClassNotFoundException | LinkageError | NullPointerException e) {
      return false;
    }
  }

  public static List<Entry> entries() {
    if (!available()) throw new IllegalArgumentException("请先打开AE2物品终端");
    try {
      var menu = Minecraft.getInstance().player.containerMenu;
      Object repo = call(menu, "getClientRepo");
      if (repo == null) throw new IllegalArgumentException("AE客户端库存尚未同步");
      Object values = call(repo, "getAllEntries");
      if (!(values instanceof Collection<?> all) || all.size() > 4096)
        throw new IllegalArgumentException("AE库存条目超过本轮4096预算或接口不兼容，请缩小网络");
      List<Entry> result = new ArrayList<>();
      for (Object e : all) {
        Object key = call(e, "getWhat");
        if (key == null || !key.getClass().getName().equals("appeng.api.stacks.AEItemKey"))
          continue;
        var stack = ((ItemStack) call(key, "toStack")).copy();
        result.add(
            new Entry(
                ((Number) call(e, "getSerial")).longValue(),
                stack,
                ((Number) call(e, "getStoredAmount")).longValue(),
                ((Number) call(e, "getRequestableAmount")).longValue(),
                (Boolean) call(e, "isCraftable")));
      }
      return result;
    } catch (ReflectiveOperationException | LinkageError ex) {
      throw new IllegalArgumentException("AE2客户端接口不兼容：" + ex.getClass().getSimpleName());
    }
  }

  public static Entry find(String item) {
    var found = entries().stream().filter(e -> Actions.match(e.stack, item)).toList();
    if (found.size() != 1)
      throw new IllegalArgumentException("AE目标不存在或存在多个NBT变体；先ae_inventory，使用完整fingerprint");
    return found.get(0);
  }

  public static long amount(String fingerprint) {
    return entries().stream()
        .filter(e -> Actions.match(e.stack, fingerprint))
        .mapToLong(Entry::stored)
        .sum();
  }

  public static JsonObject observe(String query, int offset, int limit) {
    var all = entries();
    var list =
        all.stream()
            .filter(
                e ->
                    query.isBlank()
                        || Actions.match(e.stack, query)
                        || e.stack.getHoverName().getString().contains(query))
            .sorted(Comparator.comparing(e -> WorldSense.fingerprint(e.stack)))
            .toList();
    offset = Math.max(0, offset);
    limit = Math.max(1, Math.min(40, limit));
    JsonObject out = new JsonObject();
    JsonArray items = new JsonArray();
    for (int i = offset; i < Math.min(list.size(), offset + limit); i++) {
      var e = list.get(i);
      JsonObject o = new JsonObject();
      o.addProperty(
          "item",
          net.minecraft.core.registries.BuiltInRegistries.ITEM
              .getKey(e.stack.getItem())
              .toString());
      o.addProperty("fingerprint", WorldSense.fingerprint(e.stack));
      o.addProperty("name", e.stack.getHoverName().getString());
      o.addProperty("stored", e.stored);
      o.addProperty("requestable", e.requestable);
      o.addProperty("craftable", e.craftable);
      o.addProperty("serial", e.serial);
      items.add(o);
    }
    out.addProperty("source", "AE2 client repository updated by server network inventory messages");
    out.addProperty("observed_at", System.currentTimeMillis());
    out.addProperty("menu_id", Minecraft.getInstance().player.containerMenu.containerId);
    out.addProperty("known_item_entries", all.size());
    out.addProperty("matching_entries", list.size());
    out.addProperty("has_more", offset + limit < list.size());
    out.add("items", items);
    out.addProperty(
        "rule", "stored是真实客户端网络数量；普通RepoSlot的ItemStack数量不是网络库存。记录只适用于当前终端观察，不包括未知/未同步网络。");
    return out;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void interaction(long serial, String action) {
    if (!Set.of("PICKUP_SINGLE", "AUTO_CRAFT").contains(action))
      throw new IllegalArgumentException("不允许该AE操作");
    try {
      var menu = Minecraft.getInstance().player.containerMenu;
      Class<? extends Enum> type =
          (Class<? extends Enum>) Class.forName("appeng.helpers.InventoryAction");
      Object value = Enum.valueOf(type, action);
      menu.getClass().getMethod("handleInteraction", long.class, type).invoke(menu, serial, value);
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalArgumentException("AE客户端动作接口不兼容：" + e.getClass().getSimpleName());
    }
  }
}
