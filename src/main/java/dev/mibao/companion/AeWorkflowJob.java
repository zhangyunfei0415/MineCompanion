package dev.mibao.companion;

import com.google.gson.*;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Dispatch one AE native client action. Never retries an encode or an order submission. */
final class AeWorkflowJob extends Actions.Job {
  private final JsonObject input;
  private final String operation;
  private JsonObject before;
  private AbstractContainerMenu menu;
  private boolean sent;
  private ItemStack blankBefore, encodedBefore;

  AeWorkflowJob(String operation, JsonObject input, Consumer<String> done) {
    super(done);
    this.operation = operation;
    this.input = input.deepCopy();
  }

  @Override
  void tick() {
    var mc = Minecraft.getInstance();
    if (!sent) {
      before = AeWorkflowBridge.require(Agent.number(input, "ae_view", -1));
      menu = mc.player.containerMenu;
      String kind = before.get("kind").getAsString();
      try {
        switch (operation) {
          case "mode" -> {
            requireKind(kind, "PatternEncodingTermMenu");
            String value = SkillCatalog.required(input, "mode");
            Class<?> type = Class.forName("appeng.parts.encoding.EncodingMode");
            Object mode = null;
            for (Object candidate : type.getEnumConstants())
              if (((Enum<?>) candidate).name().equals(value)) mode = candidate;
            if (mode == null)
              throw new IllegalArgumentException(
                  "[INVALID_ARGUMENT]"
                      + " mode使用AE当前枚举名CRAFTING/PROCESSING/STONECUTTING/SMITHING_TABLE");
            menu.getClass().getMethod("setMode", type).invoke(menu, mode);
          }
          case "clear" -> {
            requireKind(kind, "PatternEncodingTermMenu");
            AeWorkflowBridge.call(menu, "clear");
          }
          case "encode" -> {
            requireKind(kind, "PatternEncodingTermMenu");
            blankBefore = AeWorkflowBridge.roleStack(before, "BLANK_PATTERN");
            encodedBefore = AeWorkflowBridge.roleStack(before, "ENCODED_PATTERN");
            if (!encodedBefore.isEmpty()
                && !(input.has("replace_existing") && input.get("replace_existing").getAsBoolean()))
              throw new IllegalArgumentException(
                  "[OUTPUT_OCCUPIED] 编码输出已有物品；先取走，或明确replace_existing=true改写该样板，避免重复编码");
            if (encodedBefore.isEmpty() && blankBefore.isEmpty())
              throw new IllegalArgumentException("[NO_BLANK_PATTERN] 需在BLANK_PATTERN槽提供空白样板");
            int blank = AeWorkflowBridge.roleSlot(before, "BLANK_PATTERN");
            int output = AeWorkflowBridge.roleSlot(before, "ENCODED_PATTERN");
            if (!MenuContext.targetConfirmed(blank) || !MenuContext.targetConfirmed(output))
              throw new IllegalArgumentException("[MENU_UNCONFIRMED] 空白样板/编码输出尚无服务器一致数据；未编码");
            AeWorkflowBridge.call(menu, "encode");
          }
          case "plan" -> {
            requireKind(kind, "CraftAmountMenu");
            int count = SkillCatalog.bounded(input, "count", 1, 1, 65536);
            menu.getClass()
                .getMethod("confirm", int.class, boolean.class, boolean.class)
                .invoke(menu, count, false, false);
          }
          case "confirm" -> {
            requireKind(kind, "CraftConfirmMenu");
            if (before.get("calculating").getAsBoolean())
              throw new IllegalArgumentException("[AE_CALCULATING] 等待计算后重新观察，不提交");
            if (before.get("simulation").getAsBoolean())
              throw new IllegalArgumentException("[AE_MISSING_MATERIALS] 当前计划为缺料模拟，未提交");
            if (before.get("no_cpu").getAsBoolean())
              throw new IllegalArgumentException("[AE_NO_CPU] 没有可用合成CPU，未提交");
            AeWorkflowBridge.call(menu, "startJob");
          }
          default -> throw new IllegalArgumentException("[INVALID_ARGUMENT] 未知AE操作");
        }
      } catch (java.lang.reflect.InvocationTargetException e) {
        sent = true;
        AeWorkflowBridge.consume();
        GuiContext.invalidate();
        finish(
            receipt(
                    "unverified",
                    "AE原生动作抛出异常，派发结果未知；先观察，不重发："
                        + e.getTargetException().getClass().getSimpleName(),
                    null)
                .toString());
        return;
      } catch (ReflectiveOperationException | LinkageError e) {
        throw new IllegalArgumentException(
            "[AE_API_UNSUPPORTED] AE客户端动作不可用：" + e.getClass().getSimpleName());
      }
      sent = true;
      AeWorkflowBridge.consume();
      GuiContext.invalidate();
      Actions.status = "AE · " + operation + " 已发送一次，等待结果";
      return;
    }
    ticks++;
    if (mc.player.containerMenu != menu) {
      if (operation.equals("plan")
          && AeWorkflowBridge.kind(mc.player.containerMenu).equals("CraftConfirmMenu"))
        finish(receipt("verified", "已打开合成计算/确认页；尚未提交订单", AeWorkflowBridge.current()).toString());
      else
        finish(
            receipt(
                    "unverified",
                    operation.equals("confirm")
                        ? "提交后菜单已切换；订单受理与完成仍需查原生合成状态/网络库存，不重复下单"
                        : "菜单已改变，需重新观察；不重发",
                    null)
                .toString());
      return;
    }
    var after = AeWorkflowBridge.current();
    if (operation.equals("encode")) {
      ItemStack encoded = AeWorkflowBridge.roleStack(after, "ENCODED_PATTERN");
      ItemStack blank = AeWorkflowBridge.roleStack(after, "BLANK_PATTERN");
      boolean consumed =
          encodedBefore.isEmpty()
              ? blankBefore.getCount() - blank.getCount() == 1
              : blankBefore.getCount() == blank.getCount();
      String mode = before.get("mode").getAsString().toLowerCase(java.util.Locale.ROOT);
      String id =
          net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(encoded.getItem()).toString();
      boolean type = id.equals("ae2:" + mode + "_pattern");
      boolean server =
          MenuContext.targetConfirmed(AeWorkflowBridge.roleSlot(after, "BLANK_PATTERN"))
              && MenuContext.targetConfirmed(AeWorkflowBridge.roleSlot(after, "ENCODED_PATTERN"));
      if (consumed
          && type
          && encoded.hasTag()
          && server
          && after.has("decoded_pattern")
          && AePatternProof.matches(before, after.getAsJsonObject("decoded_pattern"))) {
        finish(
            receipt("verified", "服务器槽位确认样板类型、空白消耗及解码输入输出数量/NBT指纹与草稿一致；产物仍在编码输出槽", after)
                .toString());
        return;
      }
    } else if (operation.equals("mode")
        && after.has("mode")
        && after.get("mode").getAsString().equals(SkillCatalog.required(input, "mode"))) {
      finish(receipt("verified", "客户端同步编码模式已匹配", after).toString());
      return;
    } else if (operation.equals("clear") && ghostEmpty(after)) {
      finish(receipt("verified", "客户端虚拟输入/输出草稿已清空；未取出或删除真实样板", after).toString());
      return;
    } else if (operation.equals("confirm")
        && after.has("submit_error")
        && !after.get("submit_error").getAsString().isBlank()) {
      finish(
          receipt("rejected", "AE拒绝提交：" + after.get("submit_error").getAsString(), after)
              .toString());
      return;
    }
    if (ticks >= (operation.equals("plan") ? 200 : 60))
      finish(receipt("unverified", "已发送一次，尚无足够结果证据；查当前AE状态，不自动重放", after).toString());
  }

  private static boolean ghostEmpty(JsonObject state) {
    if (!state.has("slots")) return false;
    for (var e : state.getAsJsonArray("slots")) {
      var row = e.getAsJsonObject();
      if (Set.of(
                  "CRAFTING_GRID",
                  "PROCESSING_INPUTS",
                  "PROCESSING_OUTPUTS",
                  "STONECUTTING_INPUT",
                  "SMITHING_TABLE_TEMPLATE",
                  "SMITHING_TABLE_BASE",
                  "SMITHING_TABLE_ADDITION")
              .contains(row.get("role").getAsString())
          && row.getAsJsonObject("item").get("count").getAsInt() > 0) return false;
    }
    return true;
  }

  private static void requireKind(String actual, String expected) {
    if (!actual.equals(expected))
      throw new IllegalArgumentException("[AE_WRONG_MENU] 需要" + expected + "，当前" + actual);
  }

  private JsonObject receipt(String state, String detail, JsonObject after) {
    var out = new JsonObject();
    out.addProperty("operation", operation);
    out.addProperty("status", state);
    out.addProperty("business_success", state.equals("verified"));
    out.addProperty("sent_once", sent);
    out.addProperty("detail", detail);
    out.addProperty("production_complete", false);
    if (after != null) out.add("after", after);
    return out;
  }

  @Override
  String interrupted() {
    return receipt("interrupted_unknown_result", "AE动作已停止；已发出的编码/订单不能自动重发", null).toString();
  }
}
