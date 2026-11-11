package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * World file + player UUID, separate from shared skills. Observation changes have no production
 * attribution.
 */
public final class InventoryMemory {
  private static long nextTick, lastSave;
  private static boolean dirty;
  private static String session = UUID.randomUUID().toString();

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public static void reset() {
    nextTick = 0;
    lastSave = 0;
    dirty = false;
    session = UUID.randomUUID().toString();
  }

  private static String player() {
    return mc().player.getUUID().toString();
  }

  public static void normalize(Map<String, JsonObject> memories) {
    memories
        .entrySet()
        .removeIf(
            e ->
                e.getKey() == null
                    || e.getValue() == null
                    || !e.getValue().has("slots")
                    || !e.getValue().get("slots").isJsonArray());
    while (memories.size() > 16) memories.remove(memories.keySet().iterator().next());
  }

  public static boolean capture() {
    if (mc().player == null || mc().level == null || Store.scope().equals("disconnected"))
      return false;
    long now = System.currentTimeMillis();
    var o = new JsonObject();
    o.addProperty("player", player());
    o.addProperty("dimension", mc().level.dimension().location().toString());
    o.addProperty("observed_at", now);
    o.addProperty("source", "client_inventory");
    o.addProperty("session", session);
    var slots = new JsonArray();
    int free = 0;
    for (int i = 0; i < 41; i++) {
      var s = mc().player.getInventory().getItem(i);
      if (i >= 9 && i < 36 && s.isEmpty()) free++;
      if (s.isEmpty()) continue;
      var v = Actions.item(s);
      v.addProperty("inventory_slot", i);
      v.addProperty("maximum", s.getMaxStackSize());
      if (s.isDamageableItem()) {
        v.addProperty("durability_remaining", s.getMaxDamage() - s.getDamageValue());
        v.addProperty("durability_maximum", s.getMaxDamage());
        var identity = s.copy();
        if (identity.hasTag()) identity.getTag().remove("Damage");
        v.addProperty("quantity_fingerprint", WorldSense.fingerprint(identity));
      }
      slots.add(v);
    }
    o.add("slots", slots);
    o.addProperty("free_main_slots", free);
    boolean full = matchesFull();
    o.addProperty("last_full_server_snapshot_at", InventoryEvidence.receivedAt);
    o.addProperty("full_server_snapshot_matches_current", full);
    o.add("cursor", Actions.item(mc().player.containerMenu.getCarried()));
    var old = Store.data.inventoryMemories.get(player());
    boolean changed =
        old == null
            || !slots.equals(old.get("slots"))
            || !o.get("cursor").equals(old.get("cursor"));
    JsonArray changes = InventoryJournal.changes(old, o, session, now);
    if (full) {
      var confirmed = new JsonObject();
      confirmed.addProperty("confirmed_at", InventoryEvidence.receivedAt);
      confirmed.addProperty("dimension", mc().level.dimension().location().toString());
      confirmed.addProperty("historical", true);
      confirmed.add("slots", slots.deepCopy());
      confirmed.add("cursor", o.get("cursor").deepCopy());
      o.add("last_confirmed_inventory", confirmed);
    } else if (old != null && old.has("last_confirmed_inventory"))
      o.add("last_confirmed_inventory", old.get("last_confirmed_inventory").deepCopy());
    o.add("changes", changes);
    Store.data.inventoryMemories.put(player(), o);
    normalize(Store.data.inventoryMemories);
    dirty |= changed;
    return changed;
  }

  private static boolean matchesFull() {
    var p = mc().player;
    if (p.containerMenu != p.inventoryMenu) return false;
    var actual = p.inventoryMenu.slots;
    if (InventoryEvidence.receivedAt == 0 || actual.size() != InventoryEvidence.slots.size())
      return false;
    for (int i = 0; i < actual.size(); i++)
      if (!ItemStack.matches(actual.get(i).getItem(), InventoryEvidence.slots.get(i))) return false;
    return ItemStack.matches(p.inventoryMenu.getCarried(), InventoryEvidence.cursor);
  }

  public static void tick() {
    long now = System.currentTimeMillis();
    if (now < nextTick) return;
    nextTick = now + 1000;
    capture();
    if (dirty && now - lastSave >= 5000) {
      lastSave = now;
      Store.save();
      dirty = false;
    }
  }

  public static JsonObject summary() {
    capture();
    var out = new JsonObject();
    if (mc().player == null) return out;
    var current = Store.data.inventoryMemories.get(player());
    if (current == null) return out;
    for (String key :
        List.of(
            "observed_at",
            "free_main_slots",
            "last_full_server_snapshot_at",
            "full_server_snapshot_matches_current")) out.add(key, current.get(key).deepCopy());
    var changes = current.getAsJsonArray("changes");
    var recent = new JsonArray();
    for (int i = Math.max(0, changes.size() - 3); i < changes.size(); i++)
      recent.add(changes.get(i).deepCopy());
    out.add("recent_changes_unattributed", recent);
    out.addProperty("details_tool", "inventory_memory");
    return out;
  }

  public static JsonObject observe() {
    return observe("", 0);
  }

  /**
   * @param item registry id or exact NBT fingerprint filter, blank for no filter
   * @param minCount minimum matched count for has_item, at least 1
   * @return the regular snapshot plus a precise query block when a filter is given
   */
  public static JsonObject observe(String item, int minCount) {
    var o = base();
    var query = InventoryQuery.parse(item);
    if (query == null) return o;
    int matched = 0;
    if (mc().player != null)
      for (int i = 0; i < 41; i++) {
        var s = mc().player.getInventory().getItem(i);
        if (s.isEmpty()) continue;
        var row = Actions.item(s);
        if (InventoryQuery.matches(
            query, row.get("id").getAsString(), row.get("fingerprint").getAsString()))
          matched += s.getCount();
      }
    o.add("query", InventoryQuery.result(query, matched, minCount));
    return o;
  }

  private static JsonObject base() {
    capture();
    var o = new JsonObject();
    o.addProperty("scope", Store.scope());
    o.addProperty("quantity_rule", "当前客户端观察与历史记忆分开；变化来源未确认，不计作生产成果；完整指纹区分NBT");
    if (mc().player != null && Store.data.inventoryMemories.containsKey(player())) {
      var current = Store.data.inventoryMemories.get(player()).deepCopy();
      current.remove("session");
      o.add("current", current);
    }
    o.addProperty("saved_players", Store.data.inventoryMemories.size());
    return o;
  }

  public static String human() {
    var o = observe();
    if (!o.has("current")) return "当前没有背包观察。";
    var c = o.getAsJsonObject("current");
    var text =
        new StringBuilder(
            "随身库存 · 当前角色 / 当前世界\n空主背包槽："
                + c.get("free_main_slots")
                + "\n观察时间："
                + java.time.Instant.ofEpochMilli(c.get("observed_at").getAsLong())
                + "\n完整服务器快照匹配："
                + c.get("full_server_snapshot_matches_current")
                + "\n物品按ID与NBT指纹区分，变化来源不自动归因。\n");
    for (var e : c.getAsJsonArray("slots")) {
      var s = e.getAsJsonObject();
      text.append("槽 ")
          .append(s.get("inventory_slot"))
          .append(" · ")
          .append(s.get("name").getAsString())
          .append(" × ")
          .append(s.get("count"))
          .append(" · ")
          .append(s.get("fingerprint").getAsString())
          .append('\n');
    }
    text.append("\n最近库存变化（未归因）：\n").append(c.getAsJsonArray("changes"));
    return text.toString();
  }

  private InventoryMemory() {}
}
