package dev.mibao.companion;

import com.google.gson.*;
import net.minecraft.client.Minecraft;

/** Stops work, preserves the control session, and tries one verified ordinary meal. */
public final class HealthRecovery {
  private static final RecoveryWindow window = new RecoveryWindow();
  private static boolean notified;
  private static String detail = "无需恢复";

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public static boolean active() {
    return window.active();
  }

  public static void reset() {
    window.reset();
    notified = false;
    detail = "无需恢复";
  }

  public static boolean restricted() {
    return !Store.config.testIgnoreLowHealth
        && (active()
            || mc().player != null
                && mc().player.getHealth() < SurvivalPolicy.critical(mc().player.getMaxHealth()));
  }

  public static boolean allowed(String tool) {
    return java.util.Set.of(
            "recipe_status",
            "search_items",
            "lookup_recipes",
            "lookup_client_recipes",
            "recipe_details",
            "plan_recipe",
            "companion_context",
            "knowledge_notes",
            "inventory_memory",
            "work_session_status",
            "observe",
            "check_task",
            "recovery_status",
            "input_capabilities",
            "list_controls",
            "list_places",
            "list_skills",
            "list_presets",
            "query_world",
            "inspect_block",
            "inspect_menu",
            "inspect_gui",
            "eat_food",
            "open_inventory",
            "close_screen",
            "stow_cursor",
            "say",
            "finish")
        .contains(tool);
  }

  public static boolean tick() {
    if (Store.config.testIgnoreLowHealth) {
      if (active()) reset();
      return false;
    }
    var p = mc().player;
    if (p == null || p.isDeadOrDying()) return false;
    boolean was = active();
    boolean ordinary = false;
    for (int i = 0; i < 36; i++) {
      var s = p.getInventory().getItem(i);
      if (!s.isEmpty()
          && !s.hasTag()
          && ControlPolicy.FOOD.contains(
              net.minecraft.core.registries.BuiltInRegistries.ITEM
                  .getKey(s.getItem())
                  .toString())) {
        ordinary = true;
        break;
      }
    }
    boolean safe =
        mc().screen == null && p.containerMenu == p.inventoryMenu && InventorySession.canClose();
    var next =
        window.step(
            System.currentTimeMillis(),
            p.getHealth(),
            p.getMaxHealth(),
            p.getFoodData().getFoodLevel(),
            ordinary,
            safe);
    if (next == RecoveryWindow.Step.NONE) return false;
    if (!was && active()) {
      Agent.interruptWorkForRecovery();
      notified = false;
      Companion.notifyUser("生命偏低，已中止旧作业；先尝试自行恢复，观察和进食仍可使用。");
    }
    if (next == RecoveryWindow.Step.RECOVERED) {
      detail = "生命已恢复到作业阈值；旧作业未重放";
      Agent.state = detail;
      Companion.notifyUser(detail);
      return false;
    }
    if (Actions.job != null) {
      Actions.tick();
      return true;
    }
    Navigator.release();
    if (!Agent.pendingTool.isBlank()) return true;
    if (next == RecoveryWindow.Step.EAT) {
      detail = "自行恢复：尝试吃一件普通食物并核对消耗";
      Agent.state = detail;
      Agent.execute(
          "eat_food",
          new JsonObject(),
          result -> {
            detail = ActionSummary.describe(result);
            Companion.log("自行恢复：" + detail);
          });
    } else if (next == RecoveryWindow.Step.HELP) {
      detail =
          safe
              ? ordinary ? "自行恢复窗口已结束；需要其他恢复方式，或确认现场安全" : "没有可用的普通食物；需要食物或其他恢复方式"
              : "当前界面不能安全进食；先收好鼠标及合成栏物品并关闭界面";
      Agent.state = "恢复待处理：" + detail;
      if (!notified) {
        Companion.notifyUser(Agent.state + "。控制会话保留，危险作业保持停止。");
        notified = true;
      }
    } else {
      detail = "停止作业，等待生命恢复；不重复进食或重放旧动作";
      Agent.state = detail;
    }
    return true;
  }

  public static JsonObject observe() {
    var o = new JsonObject();
    o.addProperty("active", active());
    o.addProperty("help_notifications", Store.config.helpNotifications);
    o.addProperty("test_ignore_low_health", Store.config.testIgnoreLowHealth);
    o.addProperty("detail", detail);
    o.addProperty("auto_resume_old_task", false);
    o.addProperty("meal_attempt_limit", 1);
    o.addProperty("window_seconds", 45);
    return o;
  }
}
