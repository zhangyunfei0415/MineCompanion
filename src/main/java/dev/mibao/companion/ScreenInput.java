package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

/** Fresh screen identity and dimensions for window-relative input. */
public final class ScreenInput {
  private static Screen screen;
  private static int width, height;
  private static long view, at;

  public static JsonObject inspect() {
    var mc = Minecraft.getInstance();
    screen = mc.screen;
    width = mc.getWindow().getGuiScaledWidth();
    height = mc.getWindow().getGuiScaledHeight();
    at = System.currentTimeMillis();
    view++;
    var o = new JsonObject();
    o.addProperty("screen_view", view);
    o.addProperty("class", screen == null ? "world" : screen.getClass().getName());
    o.addProperty("title", screen == null ? "" : screen.getTitle().getString());
    o.addProperty("width", width);
    o.addProperty("height", height);
    o.addProperty("coordinate_space", "gui_scaled_pixels");
    o.add("agent_pointer", AgentPointer.view());
    o.add("navigation_state", navigationState());
    o.addProperty("full_game_control", Store.config.fullGameControl);
    o.addProperty("input_hint", "game_input需完全游戏控制；gui_back无需该开关。长界面用滚轮/拖动后重读，不复用旧坐标。");
    var rows = new JsonArray();
    if (screen != null)
      for (var child : screen.children()) {
        if (rows.size() >= 128) break;
        if (child instanceof AbstractWidget w) {
          var r = new JsonObject();
          r.addProperty("label", w.getMessage().getString());
          r.addProperty("x", w.getX());
          r.addProperty("y", w.getY());
          r.addProperty("width", w.getWidth());
          r.addProperty("height", w.getHeight());
          r.addProperty("active", w.active);
          r.addProperty("visible", w.visible);
          rows.add(r);
        }
      }
    o.add("widgets", rows);
    return o;
  }

  public static void validate(long expected) {
    var mc = Minecraft.getInstance();
    if (expected != view
        || System.currentTimeMillis() - at > 15000
        || screen != mc.screen
        || width != mc.getWindow().getGuiScaledWidth()
        || height != mc.getWindow().getGuiScaledHeight())
      throw new IllegalArgumentException("[STALE_VIEW] expected_screen_view=" + expected
          + " current_screen_view=" + view + "；先inspect_screen获取当前界面与尺寸");
    view++;
  }

  public static boolean ftb() {
    var s = Minecraft.getInstance().screen;
    return s != null && s.getClass().getName().equals("dev.ftb.mods.ftblibrary.ui.ScreenWrapper");
  }

  public static String back() {
    var mc = Minecraft.getInstance();
    var previous = mc.screen;
    if (previous == null) return "成功：已在世界。";
    if (!mc.player.containerMenu.getCarried().isEmpty())
      throw new IllegalArgumentException("[CURSOR_OCCUPIED] 先收好鼠标物品");
    previous.keyPressed(256, 0, 0);
    GuiContext.invalidate();
    return "返回键已发送一次；当前界面="
        + (mc.screen == null ? "world" : mc.screen.getClass().getName())
        + "；同一ScreenWrapper也可能已返回一层，请重新观察，不视为奖励或生产成功。";
  }

  public static void position(double x, double y) {
    if (!ftb()) return;
    try {
      Object gui =
          Minecraft.getInstance()
              .screen
              .getClass()
              .getMethod("getGui")
              .invoke(Minecraft.getInstance().screen);
      gui.getClass()
          .getMethod("updateGui", int.class, int.class, float.class)
          .invoke(gui, (int) x, (int) y, 0f);
    } catch (ReflectiveOperationException e) {
      throw new IllegalArgumentException("[GUI_API_UNSUPPORTED] FTB鼠标定位接口不兼容");
    }
  }

  public static JsonObject navigationState() {
    var o = new JsonObject();
    if (!ftb()) return o;
    try {
      Object gui =
          Minecraft.getInstance()
              .screen
              .getClass()
              .getMethod("getGui")
              .invoke(Minecraft.getInstance().screen);
      o.addProperty("gui_class", gui.getClass().getName());
      Object panel;
      try {
        panel = gui.getClass().getField("questPanel").get(gui);
      } catch (NoSuchFieldException e) {
        panel = gui;
      }
      o.addProperty(
          "scroll_x",
          ((Number) panel.getClass().getMethod("getScrollX").invoke(panel)).doubleValue());
      o.addProperty(
          "scroll_y",
          ((Number) panel.getClass().getMethod("getScrollY").invoke(panel)).doubleValue());
    } catch (ReflectiveOperationException | RuntimeException e) {
      o.addProperty("available", false);
    }
    return o;
  }
}
