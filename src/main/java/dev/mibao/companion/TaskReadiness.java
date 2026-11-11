package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.level.GameType;

/** Advisory client preflight: opens nothing, reserves nothing, and never claims future success. */
public final class TaskReadiness {
  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public static void requireWorld(boolean survival) {
    if (mc().player == null || mc().level == null || mc().gameMode == null)
      throw new IllegalArgumentException("请先进入世界");
    var mode = mc().gameMode.getPlayerMode();
    if (survival
        ? mode != GameType.SURVIVAL
        : mode != GameType.SURVIVAL && mode != GameType.ADVENTURE && mode != GameType.CREATIVE)
      throw new IllegalArgumentException(
          survival ? "采集核验仅支持生存模式，先切换再准备工具" : "[MODE_UNSUPPORTED] 旁观模式不支持生产/交互");
    if (mc().player.isDeadOrDying()
        || SurvivalPolicy.blocked(
            mc().player.getHealth(),
            SurvivalPolicy.critical(mc().player.getMaxHealth()),
            Store.config.testIgnoreLowHealth)) throw new IllegalArgumentException("生命不足，先补给并核实安全");
    if (!mc().player.containerMenu.getCarried().isEmpty())
      throw new IllegalArgumentException("鼠标持有物品，先收好再启动新任务");
  }

  public static JsonObject inspect(JsonObject args) {
    var out = new JsonObject();
    out.addProperty("advisory_only", true);
    out.addProperty("build", BuildInfo.ID);
    out.addProperty("inventory_source", "client_view; not an execution receipt");
    var issues = new JsonArray();
    String kind = Agent.str(args, "kind", "craft");
    out.addProperty("kind", kind);
    try {
      requireWorld(Set.of("mine", "wood", "farm", "hunt").contains(kind));
      int count = SkillCatalog.bounded(args, "count", 1, 1, kind.equals("collect") ? 256 : 64);
      int empty = 0, hotbar = 0;
      Map<String, Integer> stock = new TreeMap<>();
      boolean axe = false;
      java.util.List<WorkSlotPolicy.Slot> workSlots = new java.util.ArrayList<>();
      for (int i = 0; i < 36; i++) {
        var s = mc().player.getInventory().getItem(i);
        if (i < 9)
          workSlots.add(
              new WorkSlotPolicy.Slot(
                  BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.isEmpty(), s.hasTag()));
        if (s.isEmpty()) {
          empty++;
          if (i < 9) hotbar++;
        } else {
          if (!s.hasTag())
            stock.merge(
                BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
          if (s.getItem() instanceof AxeItem
              && (!s.isDamageableItem() || s.getMaxDamage() - s.getDamageValue() > 2)) axe = true;
        }
      }
      out.addProperty("empty_main_slots", empty);
      out.addProperty("empty_hotbar_slots", hotbar);
      out.addProperty(
          "work_slot_candidate",
          WorkSlotPolicy.choose(workSlots, mc().player.getInventory().selected));
      out.addProperty(
          "work_slot_semantics",
          "empty or ordinary-stack swap; existing hotbar tools need no spare slot");
      if (Set.of("craft", "wood").contains(kind)
          && hotbar == 0
          && WorkSlotPolicy.choose(workSlots, mc().player.getInventory().selected) < 0)
        issues.add("快捷栏均被保护；现有工具可直接用，新工具或工作站可能需要人工腾格");
      if (empty == 0) issues.add("背包没有空位；可先organize_inventory合并主背包相同ID/NBT堆叠，不能合并再按明确交付箱存货");
      if (kind.equals("wood") && !axe) issues.add("没有可用斧子，将先尝试用普通材料制作；材料不足就停止");
      String source = Agent.str(args, "source", Store.data.productionSource),
          delivery = Agent.str(args, "delivery", Store.data.resourceDelivery);
      out.addProperty("source", source);
      out.addProperty("delivery", delivery);
      out.addProperty("source_contents_known", false);
      if (kind.equals("craft")) {
        boolean table =
            Store.data.world.objects.values().stream()
                .anyMatch(
                    p ->
                        p.present
                            && p.dimension.equals(mc().level.dimension().location().toString())
                            && p.block.equals("minecraft:crafting_table")
                            && new net.minecraft.core.BlockPos(p.x, p.y, p.z)
                                    .distSqr(mc().player.blockPosition())
                                <= 64
                            && mc().level.hasChunkAt(new net.minecraft.core.BlockPos(p.x, p.y, p.z))
                            && mc().level
                                .getBlockState(new net.minecraft.core.BlockPos(p.x, p.y, p.z))
                                .is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE));
        try {
          var plan =
              new RecipePlanner(ClientRecipes.crafting().plans())
                  .plan(SkillCatalog.required(args, "item"), count, stock, table);
          out.addProperty("planned_batches", plan.batches().size());
          out.addProperty("needs_table_preparation", plan.needsTable());
        } catch (IllegalArgumentException e) {
          issues.add(Rules.clean(e.getMessage(), 250));
          if (!source.isBlank()) issues.add("指定来源尚未打开，需先同步库存才能确认能否补齐");
        }
      }
      if (kind.equals("craft") || kind.equals("wood") && !axe)
        validatePlace("补料来源", source, issues);
      if (kind.equals("wood") || kind.equals("mine")) validatePlace("采集交付", delivery, issues);
      if (kind.equals("farm")) {
        out.addProperty("supports_initial_planting", true);
        out.addProperty("supports_fertilizing", true);
        out.addProperty("details", "plant_crops/fertilize_crops/farm_cycle；具体耕地、种子与骨粉执行前核实");
      } else if (!Set.of("craft", "wood", "mine", "collect").contains(kind))
        out.addProperty("details", "仅通用准备检查；目标及设备槽位仍需观察");
      out.addProperty("status", issues.isEmpty() ? "ready_to_attempt" : "needs_preparation");
    } catch (Exception e) {
      out.addProperty("status", "blocked");
      issues.add(Rules.clean(e.getMessage(), 250));
    }
    out.add("notes", issues);
    out.addProperty("verification", "本检查不执行动作；开始后仍逐步检查权限、站位和服务器回执");
    return out;
  }

  public static String human(JsonObject o) {
    var text = new StringBuilder("任务准备检查（不会执行动作）\n");
    String status = Agent.str(o, "status", "unknown");
    text.append("结论：")
        .append(
            status.equals("ready_to_attempt")
                ? "可以尝试，实际结果仍需核验"
                : status.equals("blocked") ? "当前条件不允许开始" : "需要准备或进一步观察");
    if (o.has("empty_main_slots"))
      text.append("\n背包空位：").append(o.get("empty_main_slots").getAsInt());
    if (o.has("empty_hotbar_slots"))
      text.append("\n快捷栏空位：").append(o.get("empty_hotbar_slots").getAsInt());
    if (o.has("planned_batches"))
      text.append("\n当前材料可规划批次：").append(o.get("planned_batches").getAsInt());
    for (String key : new String[] {"source", "delivery"})
      if (o.has(key) && !o.get(key).getAsString().isBlank())
        text.append(key.equals("source") ? "\n制作补料来源：" : "\n采集交付箱：")
            .append(o.get(key).getAsString());
    if (o.has("notes"))
      for (var n : o.getAsJsonArray("notes")) text.append("\n• ").append(n.getAsString());
    return text.append("\n\n没有打开容器或使用物品。开始后仍逐步检查权限、站位与服务器同步。").toString();
  }

  private static void validatePlace(String label, String name, JsonArray issues) {
    if (name.isBlank()) return;
    try {
      var a = new JsonObject();
      a.addProperty("place", name);
      var p = Actions.target(a);
      if (!mc().level.hasChunkAt(p)) issues.add(label + "区块未加载，库存未知");
      else if (!Rules.standardStorage(
          BuiltInRegistries.BLOCK.getKey(mc().level.getBlockState(p).getBlock()).toString()))
        issues.add(label + "不是已适配普通仓储");
    } catch (Exception e) {
      issues.add(label + "需要重新绑定当前世界地点");
    }
  }
}
