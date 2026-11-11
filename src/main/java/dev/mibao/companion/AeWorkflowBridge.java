package dev.mibao.companion;

import com.google.gson.*;
import java.lang.reflect.Array;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Optional public AE2 15.x client menus. No grid access and no fabricated pattern NBT. */
final class AeWorkflowBridge {
  private static long counter, observedView, observedEpoch;
  private static AbstractContainerMenu observedMenu;
  private static JsonObject observed;

  static Object call(Object object, String method) throws ReflectiveOperationException {
    return object.getClass().getMethod(method).invoke(object);
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  static String kind(AbstractContainerMenu menu) {
    try {
      for (String kind :
          List.of(
              "items.PatternEncodingTermMenu",
              "crafting.CraftAmountMenu",
              "crafting.CraftConfirmMenu"))
        if (Class.forName("appeng.menu.me." + kind).isInstance(menu))
          return kind.substring(kind.indexOf('.') + 1);
    } catch (ClassNotFoundException | LinkageError ignored) {
      // Optional integration is absent or incompatible; the core remains usable.
    }
    return "unsupported";
  }

  static JsonObject observe() {
    observed = current();
    observedMenu = mc().player.containerMenu;
    observedEpoch = Agent.epoch;
    observedView = ++counter;
    var out = observed.deepCopy();
    out.addProperty("ae_view", observedView);
    out.addProperty("observed_at", System.currentTimeMillis());
    out.addProperty("source", "current AE2 public client menu / synchronized client plan");
    out.addProperty("rule", "草稿、编码物品、提交订单、实际产出是不同阶段；此观察不证明生产完成");
    return out;
  }

  static JsonObject require(long view) {
    if (mc().player == null
        || view <= 0
        || view != observedView
        || observedEpoch != Agent.epoch
        || observedMenu != mc().player.containerMenu
        || observed == null
        || !observed.equals(current()))
      throw new IllegalArgumentException("[AE_VIEW_STALE] AE目标草稿/计划已变，请重新ae_workflow_state；未派发动作");
    if (!mc().player.containerMenu.getCarried().isEmpty())
      throw new IllegalArgumentException("[CURSOR_OCCUPIED] 先收好鼠标物品");
    return observed.deepCopy();
  }

  static void consume() {
    observedView = 0;
  }

  static JsonObject current() {
    if (mc().player == null) throw new IllegalArgumentException("[NOT_CONNECTED] 尚未进入世界");
    var menu = mc().player.containerMenu;
    var out = new JsonObject();
    String kind = kind(menu);
    out.addProperty("kind", kind);
    out.addProperty("menu_id", menu.containerId);
    try {
      switch (kind) {
        case "PatternEncodingTermMenu" -> {
          out.addProperty("mode", call(menu, "getMode").toString());
          out.addProperty("substitute", (Boolean) call(menu, "isSubstitute"));
          out.addProperty("substitute_fluids", (Boolean) call(menu, "isSubstituteFluids"));
          var slots = new JsonArray();
          for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.getSlot(i);
            Object semantic =
                menu.getClass().getMethod("getSlotSemantic", Slot.class).invoke(menu, slot);
            if (semantic == null || (Boolean) call(semantic, "playerSide")) continue;
            String role = String.valueOf(call(semantic, "id"));
            if (!slot.isActive() && !Set.of("BLANK_PATTERN", "ENCODED_PATTERN").contains(role))
              continue;
            if (role.equals("VIEW_CELL") || role.equals("STORAGE")) continue;
            var row = new JsonObject();
            row.addProperty("slot", i);
            row.addProperty("role", role);
            row.addProperty("active", slot.isActive());
            var item = Actions.item(slot.getItem());
            if (role.equals("ENCODED_PATTERN") && slot.getItem().hasTag()) {
              String nbt = slot.getItem().getTag().toString();
              item.addProperty("nbt", nbt.length() <= 65536 ? nbt : nbt.substring(0, 65536));
              item.addProperty("nbt_truncated", nbt.length() > 65536);
            }
            row.add("item", item);
            slots.add(row);
          }
          out.add("slots", slots);
          ItemStack encoded = roleStack(out, "ENCODED_PATTERN");
          if (!encoded.isEmpty()) out.add("decoded_pattern", decode(encoded));
        }
        case "CraftAmountMenu" -> out.add("requested_item", stack(call(menu, "getWhatToCraft"), 1));
        case "CraftConfirmMenu" -> {
          out.addProperty("no_cpu", (Boolean) call(menu, "hasNoCPU"));
          out.addProperty("cpu_bytes", ((Number) call(menu, "getCpuAvailableBytes")).longValue());
          Object plan = call(menu, "getPlan");
          out.addProperty("calculating", plan == null);
          if (plan != null) {
            out.addProperty("simulation", (Boolean) call(plan, "isSimulation"));
            out.addProperty("used_bytes", ((Number) call(plan, "getUsedBytes")).longValue());
            var entries = new JsonArray();
            var all = (List<?>) call(plan, "getEntries");
            if (all.size() > 1024)
              throw new IllegalArgumentException("[AE_PLAN_BUDGET] 计划超过1024条，本轮不截断后确认");
            for (Object entry : all) {
              var row = key(call(entry, "getWhat"));
              row.addProperty("missing", ((Number) call(entry, "getMissingAmount")).longValue());
              row.addProperty("stored", ((Number) call(entry, "getStoredAmount")).longValue());
              row.addProperty("craft", ((Number) call(entry, "getCraftAmount")).longValue());
              entries.add(row);
            }
            out.add("plan", entries);
          }
          Object error = menu.getClass().getField("submitError").get(menu);
          Object result = call(error, "result");
          out.addProperty(
              "submit_error", result == null ? "" : String.valueOf(call(result, "errorCode")));
        }
        default -> out.addProperty("reason", "需打开AE样板终端、合成数量页或合成确认页；其他终端使用ae_inventory");
      }
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalArgumentException(
          "[AE_API_UNSUPPORTED] 该AE版本客户端接口不兼容：" + e.getClass().getSimpleName());
    }
    return out;
  }

  static ItemStack roleStack(JsonObject state, String role) {
    int slot = roleSlot(state, role);
    var menu = mc().player.containerMenu;
    return slot >= 0 && slot < menu.slots.size()
        ? menu.getSlot(slot).getItem().copy()
        : ItemStack.EMPTY;
  }

  static int roleSlot(JsonObject state, String role) {
    if (state.has("slots"))
      for (var e : state.getAsJsonArray("slots")) {
        var row = e.getAsJsonObject();
        if (role.equals(row.get("role").getAsString())) return row.get("slot").getAsInt();
      }
    return -1;
  }

  private static JsonObject key(Object key) throws ReflectiveOperationException {
    if (key != null && key.getClass().getName().equals("appeng.api.stacks.AEItemKey"))
      return Actions.item((ItemStack) call(key, "toStack"));
    var out = new JsonObject();
    out.addProperty("kind", key == null ? "unknown" : key.getClass().getName());
    out.addProperty("client_description", String.valueOf(key));
    out.addProperty("exact_item_comparison_supported", false);
    return out;
  }

  private static JsonObject stack(Object stack, long multiplier)
      throws ReflectiveOperationException {
    if (stack == null) return new JsonObject();
    var out = key(call(stack, "what"));
    out.addProperty(
        "amount", Math.multiplyExact(((Number) call(stack, "amount")).longValue(), multiplier));
    return out;
  }

  static JsonObject decode(ItemStack encoded) throws ReflectiveOperationException {
    var out = new JsonObject();
    Object details =
        Class.forName("appeng.api.crafting.PatternDetailsHelper")
            .getMethod("decodePattern", ItemStack.class, net.minecraft.world.level.Level.class)
            .invoke(null, encoded, mc().level);
    out.addProperty("decodable", details != null);
    if (details == null) return out;
    var api = Class.forName("appeng.api.crafting.IPatternDetails");
    var inputApi = Class.forName("appeng.api.crafting.IPatternDetails$IInput");
    var inputs = new JsonArray();
    var outputs = new JsonArray();
    Object source = api.getMethod("getInputs").invoke(details);
    for (int i = 0; i < Array.getLength(source); i++) {
      Object input = Array.get(source, i);
      Object variants = inputApi.getMethod("getPossibleInputs").invoke(input);
      if (Array.getLength(variants) > 0)
        inputs.add(
            stack(
                Array.get(variants, 0),
                ((Number) inputApi.getMethod("getMultiplier").invoke(input)).longValue()));
    }
    Object result = api.getMethod("getOutputs").invoke(details);
    for (int i = 0; i < Array.getLength(result); i++) outputs.add(stack(Array.get(result, i), 1));
    out.add("inputs", inputs);
    out.add("outputs", outputs);
    return out;
  }

  private AeWorkflowBridge() {}
}
