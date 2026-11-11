package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

public final class LogisticsRuntime {
  public static String dimension() {
    return Minecraft.getInstance().level.dimension().location().toString();
  }

  public static String filter(String item) {
    if (item.contains("#")) return item;
    var key = net.minecraft.resources.ResourceLocation.tryParse(item);
    if (key == null || !BuiltInRegistries.ITEM.containsKey(key))
      throw new IllegalArgumentException("未知物品ID");
    return WorldSense.fingerprint(new ItemStack(BuiltInRegistries.ITEM.get(key)));
  }

  public static int count(String item) {
    int n = 0;
    String f = filter(item);
    for (int i = 0; i < 36; i++) {
      var s = Minecraft.getInstance().player.getInventory().getItem(i);
      if (Actions.match(s, f)) n += s.getCount();
    }
    return n;
  }

  public static boolean protectedItem(ItemStack s) {
    var bag = Store.data.bag;
    return bag.protectedItems.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).toString())
        || bag.protectedItems.contains(WorldSense.fingerprint(s));
  }

  public static boolean protectedId(String item) {
    String id = item.split("#", 2)[0];
    return Store.data.bag.protectedItems.contains(id)
        || Store.data.bag.protectedItems.contains(filter(item));
  }

  public static int reserve(String item) {
    return Store.data.bag.reserve(filter(item))
        + CommunityBoard.reserved(Store.data.community.tasks, dimension(), item.split("#", 2)[0]);
  }

  public static int usable(String item, int actual) {
    return protectedId(item) ? 0 : Math.max(0, actual - reserve(item));
  }

  public static void requireCraftMaterials(
      net.minecraft.world.item.crafting.CraftingRecipe recipe) {
    if (!(Agent.routine instanceof LogisticsTask)) return;
    Map<String, Integer> actual = new HashMap<>();
    for (var stack : Minecraft.getInstance().player.getInventory().items)
      if (!stack.isEmpty() && !stack.hasTag())
        actual.merge(
            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
            stack.getCount(),
            Integer::sum);
    for (var entry : actual.entrySet()) {
      String id = entry.getKey();
      int usable = usable(id, entry.getValue());
      if (usable == entry.getValue()) continue;
      var stack =
          new ItemStack(
              BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.tryParse(id)));
      int candidates =
          (int) recipe.getIngredients().stream().filter(i -> !i.isEmpty() && i.test(stack)).count();
      if (candidates > usable)
        throw new IllegalArgumentException("配方可能消耗常备/待办预留或固定物资 " + id + "，请补料或调整配方，不重复试填");
    }
  }

  public static LogisticsRecord record(String title, JsonObject plan, int target) {
    return record(title, plan, target, true);
  }

  public static LogisticsRecord record(String title, JsonObject plan, int target, boolean attach) {
    trim();
    if (Store.data.logisticsRecords.size() >= 256)
      throw new IllegalArgumentException("待核实作业记录已满，先结案旧目标或导出清理记录");
    var r = new LogisticsRecord();
    r.title = title;
    r.dimension = dimension();
    r.plan = plan.deepCopy();
    r.target = target;
    Store.data.logisticsRecords.put(r.id, r);
    var t = Store.data.community.active();
    if (t != null && attach) t.orders.add(r.id);
    trim();
    Store.save();
    return r;
  }

  private static void trim() {
    LogisticsHistory.trim(Store.data.logisticsRecords, Store.data.community.tasks, 96);
  }

  public static void resume(String id, Consumer<String> done) {
    var r = Store.data.logisticsRecords.get(id);
    if (r == null
        || !r.dimension.equals(dimension())
        || r.continuedBy != null && !r.continuedBy.isBlank()
        || Set.of("RUNNING", "COMPLETE").contains(r.state))
      throw new IllegalArgumentException("记录不可续接或已创建续接任务");
    if (!r.plan.has("item") || r.plan.get("kind").getAsString().equals("stock"))
      throw new IllegalArgumentException("首版仅续接普通物品委托");
    if (r.uncertain)
      throw new IllegalArgumentException("[REOBSERVE_REQUIRED] 存在未核实操作，先对照当前背包与目的箱结清证据，不自动续取");
    var args = r.plan.deepCopy();
    int remaining = r.target - r.delivered;
    if (remaining <= 0) throw new IllegalArgumentException("无剩余交付量");
    args.addProperty("count", remaining);
    start(args, done);
    if (Agent.routine instanceof LogisticsTask t) {
      r.continuedBy = t.record.id;
      r.note = "续接至 " + r.continuedBy;
      Store.save();
    }
  }

  public static void start(JsonObject args, Consumer<String> done) {
    if (Agent.routine != null || HarnessController.busy())
      throw new IllegalArgumentException("已有作业，先结算当前动作");
    String fallback =
        Optional.ofNullable(Store.data.community.active()).map(t -> t.delivery).orElse("");
    Agent.routine = new LogisticsTask(LogisticsPlan.from(args, fallback), done);
  }

  public static void bag(Consumer<String> done) {
    if (Agent.routine != null || HarnessController.busy())
      throw new IllegalArgumentException("已有作业");
    Agent.routine = new BagTask(done);
  }

  public static void humanPriority() {
    if (Agent.routine instanceof LogisticsTask task) task.requestYield();
  }

  public static boolean idle() {
    if (!Agent.enabled
        || Agent.paused
        || !Agent.task.isBlank()
        || Agent.routine != null
        || HarnessController.busy()
        || Minecraft.getInstance().screen != null) return false;
    long now = System.currentTimeMillis();
    for (var target : Store.data.stockTargets) {
      LogisticsPlan p;
      try {
        target.validate();
        p = LogisticsPlan.from(target.plan, "");
      } catch (Exception e) {
        target.enabled = false;
        target.state = Rules.clean(e.getMessage(), 140);
        continue;
      }
      boolean loaded = loadedPlace(p.destination()) && loadedPlace(p.source());
      if (!StockTarget.due(
          target.enabled, now, target.nextCheck, target.dimension, dimension(), loaded)) continue;
      target.nextCheck = now + target.intervalSeconds * 1000L;
      target.state = "检查实际库存";
      try {
        var routine =
            new LogisticsTask(
                p,
                true,
                result -> {
                  if (result.startsWith("已让出：")) {
                    target.state = Rules.clean(result, 220);
                    target.nextCheck = System.currentTimeMillis() + target.intervalSeconds * 1000L;
                    Store.save();
                    return;
                  }
                  boolean success = result.startsWith("成功：");
                  target.state = Rules.clean(result, 220);
                  target.settled(success, System.currentTimeMillis());
                  Store.save();
                });
        target.lastOrder = routine.record.id;
        Agent.routine = routine;
        return true;
      } catch (Exception e) {
        target.state = "维护前置条件受阻：" + Rules.clean(e.getMessage(), 180);
        target.settled(false, now);
        Store.save();
      }
    }
    return false;
  }

  public static boolean loadedPlace(String name) {
    try {
      var a = new JsonObject();
      a.addProperty("place", name);
      return Minecraft.getInstance().level.hasChunkAt(Actions.target(a));
    } catch (Exception e) {
      return false;
    }
  }

  public static void halt() {
    for (var r : Store.data.logisticsRecords.values())
      if (r.state.equals("RUNNING")) {
        r.state = "SUSPENDED";
        r.note = "控制停止，先核实现状再安排剩余目标";
      }
    for (var t : Store.data.stockTargets)
      if (t.enabled) {
        t.enabled = false;
        t.state = "控制已停止，需手动启用";
      }
  }

  private static JsonObject brief(LogisticsRecord r) {
    var o = new JsonObject();
    o.addProperty("id", r.id);
    o.addProperty("title", r.title);
    o.addProperty("state", r.state);
    o.addProperty("phase", r.phase);
    o.addProperty("target", r.target);
    o.addProperty("prepared", r.prepared);
    o.addProperty("produced", r.produced);
    o.addProperty("fetched", r.fetched);
    o.addProperty("delivered", r.delivered);
    o.addProperty("carrying", r.carrying);
    o.addProperty("destination_count", r.destinationCount);
    o.addProperty("uncertain", r.uncertain);
    o.addProperty("note", r.note);
    return o;
  }

  public static JsonObject summary() {
    var o = new JsonObject();
    var active = new JsonArray();
    Store.data.logisticsRecords.values().stream()
        .filter(r -> r.state.equals("RUNNING"))
        .limit(3)
        .forEach(r -> active.add(brief(r)));
    o.add("active", active);
    o.addProperty("stock_targets", Store.data.stockTargets.size());
    o.addProperty("stock_enabled", Store.data.stockTargets.stream().filter(t -> t.enabled).count());
    o.addProperty("details_tool", "logistics_status");
    return o;
  }

  public static JsonObject view(JsonObject args) {
    var o = new JsonObject();
    String id = Agent.str(args, "record_id", "");
    if (!id.isBlank()) {
      var record = Store.data.logisticsRecords.get(id);
      if (record == null) throw new IllegalArgumentException("委托记录不存在或已归档清理");
      o.add("record", Store.GSON.toJsonTree(record));
      return o;
    }
    int offset = SkillCatalog.bounded(args, "offset", 0, 0, 256),
        limit = SkillCatalog.bounded(args, "limit", 5, 1, 20);
    var list = new ArrayList<>(Store.data.logisticsRecords.values());
    Collections.reverse(list);
    var rows = new JsonArray();
    list.stream().skip(offset).limit(limit).forEach(r -> rows.add(brief(r)));
    o.add("records", rows);
    o.addProperty("total", list.size());
    o.addProperty("offset", offset);
    o.addProperty("has_more", offset + rows.size() < list.size());
    if (args.has("include_config") && args.get("include_config").getAsBoolean()) {
      o.add("stock_targets", Store.GSON.toJsonTree(Store.data.stockTargets));
      o.add("bag", Store.GSON.toJsonTree(Store.data.bag));
      o.add("favorites", Store.GSON.toJsonTree(Store.data.favoriteOrders));
    }
    return o;
  }

  public static String summary(CommunityBoard.Task t) {
    if (t.orders == null || t.orders.isEmpty()) return t.note;
    var b = new StringBuilder();
    for (String id : t.orders) {
      var r = Store.data.logisticsRecords.get(id);
      if (r != null) b.append(r.summary()).append("；");
    }
    return b.toString();
  }

  public static String outcome(CommunityBoard.Task t) {
    return LogisticsHistory.outcome(
        t.orders == null ? List.of() : t.orders, Store.data.logisticsRecords);
  }

  private LogisticsRuntime() {}
}
