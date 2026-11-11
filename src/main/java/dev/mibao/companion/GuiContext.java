package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Observe the actual container screen; no guessed coordinates, desktop input, chat, or settings
 * fields.
 */
public final class GuiContext {
  private static long view;
  private static Screen screen;
  private static String stamp = "";
  private static String widgetStamp = "";
  private static List<net.minecraft.world.item.ItemStack> observed = List.of();
  private static net.minecraft.world.item.ItemStack observedCursor =
      net.minecraft.world.item.ItemStack.EMPTY;
  private static final Map<Integer, AbstractWidget> widgets = new LinkedHashMap<>();

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  private static void require() {
    if (!(mc().screen instanceof AbstractContainerScreen)
        || mc().player.containerMenu.slots.size() > 512)
      throw new IllegalArgumentException("仅支持当前物品GUI且最多512槽；其他界面或虚拟仓储需专用适配");
  }

  private static void collect(ContainerEventHandler parent, int depth) {
    if (depth > 4 || widgets.size() >= 128) return;
    for (var child : parent.children()) {
      if (child instanceof AbstractWidget w && widgets.size() < 128) widgets.put(widgets.size(), w);
      if (child instanceof ContainerEventHandler nested) collect(nested, depth + 1);
    }
  }

  private static String stamp() {
    return stamp(true);
  }

  private static String stamp(boolean contents) {
    var m = mc().player.containerMenu;
    StringBuilder s =
        new StringBuilder(
            m.containerId
                + ":"
                + m.getStateId()
                + ":"
                + mc().screen.width
                + ":"
                + mc().screen.height);
    if (!contents)
      s = new StringBuilder(m.containerId + ":" + mc().screen.width + ":" + mc().screen.height);
    if (contents) {
      for (var slot : m.slots)
        s.append('|')
            .append(slot.getItem().getCount())
            .append(':')
            .append(WorldSense.fingerprint(slot.getItem()));
      s.append('|')
          .append(m.getCarried().getCount())
          .append(':')
          .append(WorldSense.fingerprint(m.getCarried()));
    }
    for (var w : widgets.values())
      s.append('|')
          .append(w.getClass().getName())
          .append(':')
          .append(w.getX())
          .append(':')
          .append(w.getY())
          .append(':')
          .append(w.getWidth())
          .append(':')
          .append(w.getHeight())
          .append(':')
          .append(w.active)
          .append(':')
          .append(w.visible)
          .append(':')
          .append(w.getMessage().getString())
          .append(':')
          .append(w instanceof EditBox e ? e.getValue() : "");
    for (var slot : m.slots)
      s.append("|layout:")
          .append(slot.getClass().getName())
          .append(':')
          .append(slot.x)
          .append(',')
          .append(slot.y)
          .append(':')
          .append(slot.container == mc().player.getInventory());
    return s.toString();
  }

  public static JsonObject observe() {
    require();
    screen = mc().screen;
    widgets.clear();
    collect(screen, 0);
    stamp = stamp();
    widgetStamp = stamp(false);
    observed = mc().player.containerMenu.slots.stream().map(s -> s.getItem().copy()).toList();
    observedCursor = mc().player.containerMenu.getCarried().copy();
    JsonObject out = Actions.inspectMenu(0, 512);
    // inspect_menu owns the observation number: gui_view and view_id share one sequence, so a
    // view taken here also satisfies click_slot and vice versa.
    view = out.get("view_id").getAsLong();
    out.addProperty("gui_view", view);
    out.addProperty("screen_class", screen.getClass().getName());
    out.addProperty("gui_width", screen.width);
    out.addProperty("gui_height", screen.height);
    out.addProperty("vanilla_resync_supported", MenuRefresh.supported());
    out.add("vanilla_options", VanillaMenuModel.observe());
    var ac = (AbstractContainerScreen<?>) screen;
    var geometry = new JsonObject();
    geometry.addProperty("gui_left", ac.getGuiLeft());
    geometry.addProperty("gui_top", ac.getGuiTop());
    geometry.addProperty("gui_scale", mc().getWindow().getGuiScale());
    geometry.addProperty("framebuffer_width", mc().getMainRenderTarget().width);
    geometry.addProperty("framebuffer_height", mc().getMainRenderTarget().height);
    geometry.addProperty("slot_coordinates", "container_local; add gui_left/gui_top");
    geometry.addProperty("widget_coordinates", "logical_gui_absolute");
    out.add("geometry", geometry);
    out.addProperty("semantic_signature", VisualAssist.signature());
    if (RsTerminalBridge.available())
      try {
        out.add("rs_network", RsTerminalBridge.observe("", 0, 20));
      } catch (RuntimeException | LinkageError e) {
        out.addProperty("rs_network_unavailable", Rules.clean(e.getMessage(), 180));
      }
    if (AeTerminalBridge.available())
      try {
        out.add("ae_network", AeTerminalBridge.observe("", 0, 20));
      } catch (RuntimeException | LinkageError e) {
        out.addProperty("ae_network_unavailable", Rules.clean(e.getMessage(), 180));
      }
    if (mc().player.containerMenu
        instanceof net.minecraft.world.inventory.AbstractFurnaceMenu furnace) {
      JsonObject state = new JsonObject();
      state.addProperty("lit", furnace.isLit());
      state.addProperty("burn_progress_scaled", furnace.getBurnProgress());
      state.addProperty("lit_progress_scaled", furnace.getLitProgress());
      state.addProperty("note", "进度为客户端同步刻度，产物数量仍查输出槽");
      out.add("furnace", state);
    }
    JsonArray controls = new JsonArray();
    for (var e : widgets.entrySet()) {
      var w = e.getValue();
      JsonObject o = new JsonObject();
      o.addProperty("widget", e.getKey());
      o.addProperty("x", w.getX());
      o.addProperty("y", w.getY());
      o.addProperty("width", w.getWidth());
      o.addProperty("height", w.getHeight());
      o.addProperty("label", Rules.clean(w.getMessage().getString(), 200));
      o.addProperty(
          "kind",
          w instanceof EditBox ? "text" : w instanceof AbstractButton ? "button" : "observe_only");
      o.addProperty("active", w.active);
      o.addProperty("visible", w.visible);
      if (w instanceof EditBox box) o.addProperty("has_text", !box.getValue().isEmpty());
      controls.add(o);
    }
    out.add("widgets", controls);
    out.addProperty(
        "guidance",
        "槽位用途未知时先观察/请求视觉，不按位置猜测。按钮触发不等于生产成功。鼠标物品先stow_cursor，再close_screen退出。虚拟槽优先jei_transfer_recipe或gui_slot_target；can_pickup=false不等于无法编辑配方。");
    return out;
  }

  public static void validate(long expected) {
    require();
    widgets.clear();
    collect(mc().screen, 0);
    if (!GuiGuard.valid(expected, view, screen, mc().screen, stamp, stamp()))
      throw new IllegalArgumentException(
          ViewReject.stale("界面/内容/按钮/尺寸已改变，请重新inspect_gui，不复用旧GUI编号", expected, view));
  }

  private static void validateWidgets(long expected) {
    require();
    widgets.clear();
    collect(mc().screen, 0);
    if (!GuiGuard.valid(expected, view, screen, mc().screen, widgetStamp, stamp(false)))
      throw new IllegalArgumentException(
          ViewReject.stale("界面/控件已改变，请重新inspect_gui", expected, view));
  }

  public static void validateSlot(long expected, int slot) {
    validateWidgets(expected);
    var m = mc().player.containerMenu;
    if (slot < 0
        || slot >= observed.size()
        || slot >= m.slots.size()
        || !net.minecraft.world.item.ItemStack.matches(
            observed.get(slot), m.getSlot(slot).getItem())
        || !net.minecraft.world.item.ItemStack.matches(observedCursor, m.getCarried()))
      throw new IllegalArgumentException(
          ViewReject.stale("目标槽位或鼠标物品已改变，请重新inspect_gui", expected, view));
  }

  public static AbstractWidget widget(long expected, int index) {
    validateWidgets(expected);
    var w = widgets.get(index);
    if (w == null || !w.active || !w.visible)
      throw new IllegalArgumentException("[GUI_TARGET_INVALID] 按钮不存在、隐藏或不可用");
    return w;
  }

  public static String summary() {
    var o = observe();
    StringBuilder s =
        new StringBuilder(
            "GUI观察编号 "
                + o.get("gui_view")
                + "；菜单 "
                + o.get("menu_kind")
                + "；共 "
                + o.get("total_slots")
                + " 槽。\n");
    int n = 0;
    for (var el : o.getAsJsonArray("slots")) {
      var v = el.getAsJsonObject();
      if (!v.get("player_inventory").getAsBoolean() || v.get("count").getAsInt() > 0) {
        s.append("槽")
            .append(v.get("menu_slot"))
            .append(' ')
            .append(v.get("role").getAsString())
            .append(' ')
            .append(v.get("id").getAsString())
            .append('×')
            .append(v.get("count"))
            .append('\n');
        if (++n >= 12) break;
      }
    }
    for (var el : o.getAsJsonArray("widgets")) {
      var v = el.getAsJsonObject();
      s.append("控件")
          .append(v.get("widget"))
          .append(' ')
          .append(v.get("kind").getAsString())
          .append(' ')
          .append(v.get("label").getAsString())
          .append('\n');
      if (++n >= 18) break;
    }
    return s.toString();
  }

  public static String point(JsonObject a) {
    validate(a.get("gui_view").getAsLong());
    double x = a.get("x").getAsDouble(), y = a.get("y").getAsDouble();
    String space = Agent.str(a, "space", "gui");
    if (space.equals("framebuffer")) {
      var p =
          GuiGeometry.toGui(
              x,
              y,
              mc().getMainRenderTarget().width,
              mc().getMainRenderTarget().height,
              screen.width,
              screen.height);
      x = p.x();
      y = p.y();
    } else if (!space.equals("gui")) throw new IllegalArgumentException("space为gui/framebuffer");
    if (!Double.isFinite(x)
        || !Double.isFinite(y)
        || !GuiGeometry.inside(x, y, 0, 0, screen.width, screen.height))
      throw new IllegalArgumentException("坐标不在当前GUI中");
    var ac = (AbstractContainerScreen<?>) screen;
    boolean target = false;
    for (var slot : mc().player.containerMenu.slots)
      if (GuiGeometry.inside(x, y, ac.getGuiLeft() + slot.x, ac.getGuiTop() + slot.y, 16, 16))
        throw new IllegalArgumentException("坐标命中物品槽，须用gui_slot取得服务器核验");
    for (var widget : widgets.values())
      if (widget.active
          && widget.visible
          && GuiGeometry.inside(
              x, y, widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()))
        target = true;
    if (!target) throw new IllegalArgumentException("未命中已观察到的可用控件；自绘控件需适配，不盲点背景");
    int button = SkillCatalog.bounded(a, "button", 0, 0, 1);
    boolean handled = screen.mouseClicked(x, y, button);
    screen.mouseReleased(x, y, button);
    invalidate();
    return handled ? "界面控件点击已发送一次；功能效果未核验，请重新观察。" : "部分完成：控件未接收点击，不重放。";
  }

  public static void invalidate() {
    view = 0;
    screen = null;
    stamp = "";
    widgets.clear();
  }
}
