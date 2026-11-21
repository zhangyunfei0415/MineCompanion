package dev.mibao.companion;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Container-relative point clicks, UiProfile semantic skill actions (gui_put_role, gui_take_role,
 * gui_trigger_control, gui_wait_output), and local demonstration summarization.
 */
public final class GuiProfileActions {
  private GuiProfileActions() {}

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public static Actions.Job relativePointJob(JsonObject a, Consumer<String> done) {
    return new RelativePointJob(a, done);
  }

  public static Actions.Job putRoleJob(JsonObject a, Consumer<String> done) {
    return new PutRoleJob(a, done);
  }

  public static Actions.Job takeRoleJob(JsonObject a, Consumer<String> done) {
    return new TakeRoleJob(a, done);
  }

  public static Actions.Job triggerControlJob(JsonObject a, Consumer<String> done) {
    return new TriggerControlJob(a, done);
  }

  public static Actions.Job waitOutputJob(JsonObject a, Consumer<String> done) {
    return new WaitOutputJob(a, done);
  }

  private static boolean dispatchNativeButtonClick(Screen screen, int gx, int gy, int button, boolean shift) {
    boolean handled;
    if (shift) {
      VirtualInput.scrollInput = true;
      VirtualInput.begin(Set.of(GLFW.GLFW_KEY_LEFT_SHIFT));
    }
    try {
      AgentPointer.beginProbe(gx, gy);
      handled = screen.mouseClicked(gx, gy, button);
      screen.mouseReleased(gx, gy, button);
    } finally {
      AgentPointer.endProbe();
      if (shift) {
        VirtualInput.clear();
        VirtualInput.scrollInput = false;
      }
      GuiContext.invalidate();
    }
    return handled;
  }

  private static boolean matchesItemFilter(ItemStack stack, String filter) {
    if (stack == null || stack.isEmpty()) return false;
    if (filter == null || filter.isBlank()) return true;
    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    return id.equals(filter) || WorldSense.fingerprint(stack).equals(filter);
  }

  private static int countPlayerInventory(String itemFilter) {
    var p = mc().player;
    if (p == null) return 0;
    int total = 0;
    for (int i = 0; i < 36; i++) {
      var s = p.getInventory().getItem(i);
      if (matchesItemFilter(s, itemFilter)) total += s.getCount();
    }
    return total;
  }

  private static JsonObject buildDeltaJson(
      Map<Integer, Integer> before, Map<Integer, Integer> after) {
    var deltaObj = new JsonObject();
    for (var e : MenuDataState.delta(before, after).entrySet()) {
      var row = new JsonObject();
      if (e.getValue()[0] != Integer.MIN_VALUE) row.addProperty("before", e.getValue()[0]);
      if (e.getValue()[1] != Integer.MIN_VALUE) row.addProperty("after", e.getValue()[1]);
      deltaObj.add(Integer.toString(e.getKey()), row);
    }
    return deltaObj;
  }

  private static final class RelativePointJob extends Actions.Job {
    private final int gx, gy, dx, dy, button;
    private final boolean shift;
    private final Screen expectedScreen;
    private final String beforeClass;
    private final long menuRevBefore, dataRevBefore;
    private final Map<Integer, Integer> dataBefore;
    private boolean sent, handled;

    RelativePointJob(JsonObject a, Consumer<String> done) {
      super(done);
      long view = a.get("gui_view").getAsLong();
      this.dx = a.get("dx").getAsInt();
      this.dy = a.get("dy").getAsInt();
      this.button = SkillCatalog.bounded(a, "button", 0, 0, 1);
      this.shift = a.has("shift") && a.get("shift").getAsBoolean();
      int[] pos = GuiContext.validateRelativePoint(view, dx, dy);
      this.gx = pos[0];
      this.gy = pos[1];
      this.expectedScreen = mc().screen;
      this.beforeClass = expectedScreen.getClass().getName();
      this.menuRevBefore = MenuContext.revision();
      this.dataRevBefore = MenuContext.dataRevision();
      this.dataBefore = MenuContext.dataSnapshot();
    }

    @Override
    void tick() {
      if (!sent) {
        if (mc().screen != expectedScreen || Panel.managing(mc().screen)) {
          finish("部分完成：相对坐标点击前界面已变更，未点击。");
          return;
        }
        handled = dispatchNativeButtonClick(expectedScreen, gx, gy, button, shift);
        sent = true;
        return;
      }
      if (++ticks < 6) return;
      JsonObject out = new JsonObject();
      out.addProperty("status", "dispatched");
      out.addProperty("native_handled", handled);
      out.addProperty("business_success", false);
      out.addProperty("dx", dx);
      out.addProperty("dy", dy);
      out.addProperty("screen_changed", mc().screen != expectedScreen);
      out.addProperty(
          "new_screen_class", mc().screen == null ? "world" : mc().screen.getClass().getName());
      out.addProperty("menu_updated", MenuContext.revision() > menuRevBefore);
      out.addProperty("data_slots_changed", MenuContext.dataRevision() > dataRevBefore);
      out.add("data_delta", buildDeltaJson(dataBefore, MenuContext.dataSnapshot()));
      out.addProperty("prior_screen_class", beforeClass);
      out.addProperty("next", "请重新 inspect_gui 或 inspect_tooltip 核对实际效果；未重放点击。");
      finish(out.toString());
    }
  }

  private static final class PutRoleJob extends Actions.Job {
    private enum Phase {
      INIT,
      PLACE,
      STOW,
      VERIFY
    }

    private final String requestedProfile, role, item;
    private final int wantCount, slotIndex;
    private Phase phase = Phase.INIT;
    private Object menu, screen;
    private boolean virtualSlot;
    private int targetMenuSlot = -1;
    private int sourceMenuSlot = -1;
    private int toPlace;
    private int placed;
    private int targetBeforeCount;
    private long revBefore;
    private boolean refreshRequested;
    private boolean stowSent;
    private final String style = RuntimeStrategy.executionStyle();

    PutRoleJob(JsonObject a, Consumer<String> done) {
      super(done);
      this.requestedProfile = Agent.str(a, "profile", "");
      this.role = SkillCatalog.required(a, "role");
      this.item = SkillCatalog.required(a, "item");
      this.wantCount = SkillCatalog.bounded(a, "count", 1, 1, 64);
      this.slotIndex = SkillCatalog.bounded(a, "slot_index", 0, 0, 63);
    }

    @Override
    void tick() {
      var player = mc().player;
      if (player == null || !(mc().screen instanceof AbstractContainerScreen<?> ac)) {
        finish("部分完成：gui_put_role 需要已打开的容器界面。");
        return;
      }
      var m = player.containerMenu;
      if (phase == Phase.INIT) {
        if (!MenuContext.synchronizedNow()) {
          if (++ticks > 40) finish("失败：[MENU_UNCONFIRMED] 菜单尚未完成服务器同步。");
          return;
        }
        if (!m.getCarried().isEmpty()) {
          finish("失败：[CURSOR_OCCUPIED] 鼠标持有物品，请先 stow_cursor。");
          return;
        }
        UiProfile profile = GuiContext.resolveProfile(requestedProfile);
        List<UiProfile.ResolvedSlot> resolved =
            profile.resolveRoleSlots(role, GuiContext.liveSlots());
        if (slotIndex >= resolved.size()) {
          finish("失败：UiProfile " + profile.id + " 中未匹配到角色槽位 " + role + "[" + slotIndex + "]");
          return;
        }
        var targetInfo = resolved.get(slotIndex);
        this.targetMenuSlot = targetInfo.index();
        this.virtualSlot = targetInfo.definition().virtual();
        var targetSlot = m.getSlot(targetMenuSlot);
        this.menu = m;
        this.screen = ac;

        if (virtualSlot) {
          this.revBefore = MenuContext.revision();
          double gx = ac.getGuiLeft() + targetSlot.x + 8;
          double gy = ac.getGuiTop() + targetSlot.y + 8;
          if (!targetSlot.isActive() || gx < 0 || gy < 0 || gx >= ac.width || gy >= ac.height) {
            finish("失败：[TARGET_OFFSCREEN] 虚拟槽位当前不可见");
            return;
          }
          AgentPointer.beginProbe(gx, gy);
          try {
            ac.mouseClicked(gx, gy, 0);
            ac.mouseReleased(gx, gy, 0);
          } finally {
            AgentPointer.endProbe();
            GuiContext.invalidate();
          }
          phase = Phase.VERIFY;
          return;
        }

        int bestIdx = -1;
        int bestCount = -1;
        for (int i = 0; i < m.slots.size(); i++) {
          var s = m.getSlot(i);
          if (s.container != player.getInventory()
              || s.getContainerSlot() < 0
              || s.getContainerSlot() >= 36
              || !s.mayPickup(player)) continue;
          ItemStack stack = s.getItem();
          if (!matchesItemFilter(stack, item)) continue;
          if (!targetSlot.mayPlace(stack)) continue;
          ItemStack existing = targetSlot.getItem();
          if (!existing.isEmpty() && !ItemStack.isSameItemSameTags(existing, stack)) continue;
          if (bestIdx < 0
              || (bestCount < wantCount && stack.getCount() > bestCount)
              || (bestCount >= wantCount && stack.getCount() >= wantCount && stack.getCount() < bestCount)) {
            bestIdx = i;
            bestCount = stack.getCount();
          }
        }
        if (bestIdx < 0) {
          finish("失败：[ITEM_NOT_FOUND] 背包没有可放入 " + role + " 槽的匹配物品：" + item);
          return;
        }
        ItemStack picked = m.getSlot(bestIdx).getItem();
        int room = targetSlot.getMaxStackSize(picked) - targetSlot.getItem().getCount();
        if (room <= 0) {
          finish("部分完成：目标角色槽位 " + role + " 已满，无法放入。");
          return;
        }
        this.sourceMenuSlot = bestIdx;
        this.toPlace = Math.min(wantCount, Math.min(picked.getCount(), room));
        this.placed = 0;
        this.targetBeforeCount = targetSlot.getItem().getCount();
        this.revBefore = MenuContext.revision();
        Actions.click(sourceMenuSlot, 0, ClickType.PICKUP);
        GuiContext.invalidate();
        phase = Phase.PLACE;
        return;
      }

      if (m != menu || mc().screen != screen) {
        finish("部分完成：投料期间界面改变，未重放；请重新观察。");
        return;
      }

      if (phase == Phase.PLACE) {
        ItemStack carried = m.getCarried();
        if (carried.isEmpty()) {
          phase = Phase.VERIFY;
          return;
        }
        if (toPlace >= carried.getCount() && placed == 0) {
          Actions.click(targetMenuSlot, 0, ClickType.PICKUP);
          placed = carried.getCount();
          GuiContext.invalidate();
          phase = Phase.VERIFY;
          return;
        }
        if (placed < toPlace) {
          Actions.click(targetMenuSlot, 1, ClickType.PICKUP);
          placed++;
          GuiContext.invalidate();
          if (placed >= toPlace) {
            phase = m.getCarried().isEmpty() ? Phase.VERIFY : Phase.STOW;
          }
          return;
        }
        phase = m.getCarried().isEmpty() ? Phase.VERIFY : Phase.STOW;
        return;
      }

      if (phase == Phase.STOW) {
        if (m.getCarried().isEmpty()) {
          phase = Phase.VERIFY;
          return;
        }
        if (!stowSent) {
          int stowSlot = MenuClickJob.stowTargetSlot(m, m.getCarried());
          if (stowSlot < 0) {
            finish("部分完成：投料后背包无空位收回剩余鼠标物品，已停止点击。");
            return;
          }
          Actions.click(stowSlot, 0, ClickType.PICKUP);
          GuiContext.invalidate();
          stowSent = true;
          if (!ExecutionStyle.fluent(style)) {
            refreshRequested = true;
            MenuRefresh.request();
          }
        }
        phase = Phase.VERIFY;
        return;
      }

      // Phase.VERIFY
      if (!virtualSlot && MenuContext.revision() > revBefore && MenuContext.synchronizedNow()) {
        int after = m.getSlot(targetMenuSlot).getItem().getCount();
        int delta = after - targetBeforeCount;
        if (delta >= 1 && m.getCarried().isEmpty()) {
          finish(
              "成功：已向 " + role + " 槽位（槽" + targetMenuSlot + "）投入 " + item + " ×" + delta + "（服务端核验通过）。");
        } else {
          finish("部分完成：投料已发送但目标槽位增量或鼠标状态未达到预期（added=" + delta + "），未重放。");
        }
        return;
      }
      if (virtualSlot && ++ticks >= 12) {
        finish("部分完成：虚拟槽位已点击（槽" + targetMenuSlot + "），请重新 inspect_gui 核对幽灵配方。");
        return;
      }
      ++ticks;
      if (!virtualSlot && ExecutionStyle.needsRefresh(style, refreshRequested, ticks, false)) {
        refreshRequested = true;
        MenuRefresh.request();
      }
      if (ticks > 120) {
        finish("部分完成：投料操作已发送但超时未取得完整服务器核对，保留现场不重试。");
      }
    }
  }

  private static final class TakeRoleJob extends Actions.Job {
    private final String requestedProfile, role, itemFilter;
    private final int minCount, requestedSlotIdx;
    private Object menu, screen;
    private int targetMenuSlot = -1;
    private String takenItemId = "";
    private int playerBeforeCount;
    private long revBefore;
    private boolean clicked, refreshRequested;
    private final String style = RuntimeStrategy.executionStyle();

    TakeRoleJob(JsonObject a, Consumer<String> done) {
      super(done);
      this.requestedProfile = Agent.str(a, "profile", "");
      this.role = SkillCatalog.required(a, "role");
      this.itemFilter = Agent.str(a, "item", "");
      this.minCount = SkillCatalog.bounded(a, "min_count", 1, 1, 256);
      this.requestedSlotIdx = a.has("slot_index") ? a.get("slot_index").getAsInt() : -1;
    }

    @Override
    void tick() {
      var player = mc().player;
      if (player == null || !(mc().screen instanceof AbstractContainerScreen<?> ac)) {
        finish("部分完成：gui_take_role 需要已打开的容器界面。");
        return;
      }
      var m = player.containerMenu;
      if (!clicked) {
        if (!MenuContext.synchronizedNow()) {
          if (++ticks > 40) finish("失败：[MENU_UNCONFIRMED] 菜单尚未完成服务器同步。");
          return;
        }
        if (!m.getCarried().isEmpty()) {
          finish("失败：[CURSOR_OCCUPIED] 鼠标持有物品，请先 stow_cursor。");
          return;
        }
        UiProfile profile = GuiContext.resolveProfile(requestedProfile);
        List<UiProfile.ResolvedSlot> resolved =
            profile.resolveRoleSlots(role, GuiContext.liveSlots());
        if (resolved.isEmpty()) {
          finish("失败：UiProfile " + profile.id + " 中未匹配到角色槽位 " + role);
          return;
        }
        if (requestedSlotIdx >= 0) {
          if (requestedSlotIdx >= resolved.size()) {
            finish("失败：slot_index 越界：" + requestedSlotIdx);
            return;
          }
          int idx = resolved.get(requestedSlotIdx).index();
          if (m.getSlot(idx).getItem().isEmpty()
              || !itemFilter.isBlank() && !matchesItemFilter(m.getSlot(idx).getItem(), itemFilter)) {
            finish("部分完成：目标角色槽位 " + role + "[" + requestedSlotIdx + "] 无匹配物品可取。");
            return;
          }
          targetMenuSlot = idx;
        } else {
          for (var rs : resolved) {
            ItemStack st = m.getSlot(rs.index()).getItem();
            if (!st.isEmpty() && (itemFilter.isBlank() || matchesItemFilter(st, itemFilter))) {
              targetMenuSlot = rs.index();
              break;
            }
          }
          if (targetMenuSlot < 0) {
            finish("部分完成：角色槽位 " + role + " 当前没有可取出的匹配产物。");
            return;
          }
        }
        ItemStack stack = m.getSlot(targetMenuSlot).getItem();
        if (!m.getSlot(targetMenuSlot).mayPickup(player)) {
          finish("失败：目标槽位当前禁止取出。");
          return;
        }
        this.takenItemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        this.playerBeforeCount =
            countPlayerInventory(itemFilter.isBlank() ? takenItemId : itemFilter);
        this.menu = m;
        this.screen = ac;
        this.revBefore = MenuContext.revision();
        Actions.click(targetMenuSlot, 0, ClickType.QUICK_MOVE);
        GuiContext.invalidate();
        clicked = true;
        if (!ExecutionStyle.fluent(style)) {
          refreshRequested = true;
          MenuRefresh.request();
        }
        return;
      }

      if (m != menu || mc().screen != screen) {
        finish("部分完成：取物期间界面变更，请重新观察背包。");
        return;
      }
      if (MenuContext.revision() > revBefore && MenuContext.synchronizedNow()) {
        int after = countPlayerInventory(itemFilter.isBlank() ? takenItemId : itemFilter);
        int gained = after - playerBeforeCount;
        if (gained >= minCount && m.getCarried().isEmpty()) {
          finish("成功：已从 " + role + " 槽位取出 " + takenItemId + " ×" + gained + " 并核验入包。");
        } else if (gained > 0) {
          finish("部分完成：已取出 " + takenItemId + " ×" + gained + "，少于 min_count=" + minCount + "。");
        } else {
          finish("部分完成：未检测到背包新增产物，请核对背包空位与机器槽位状态。");
        }
        return;
      }
      ++ticks;
      if (ExecutionStyle.needsRefresh(style, refreshRequested, ticks, false)) {
        refreshRequested = true;
        MenuRefresh.request();
      }
      if (ticks > 100) {
        finish("部分完成：取物点击已发送但未取得完整服务器核对，不重放。");
      }
    }
  }

  private static final class TriggerControlJob extends Actions.Job {
    private enum Phase {
      INIT,
      WAIT_TOOLTIP,
      WAIT_EFFECT
    }

    private final String requestedProfile, controlName;
    private final boolean verifyTooltip;
    private Phase phase = Phase.INIT;
    private Screen expectedScreen;
    private UiProfile.ControlDef ctrl;
    private int gx, gy;
    private long menuRevBefore, dataRevBefore;
    private boolean handled;
    private int waitTicks;

    TriggerControlJob(JsonObject a, Consumer<String> done) {
      super(done);
      this.requestedProfile = Agent.str(a, "profile", "");
      this.controlName = SkillCatalog.required(a, "control");
      this.verifyTooltip = !a.has("verify_tooltip") || a.get("verify_tooltip").getAsBoolean();
    }

    @Override
    void tick() {
      var mc = mc();
      if (phase == Phase.INIT) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> ac) || Panel.managing(ac)) {
          finish("失败：[WRONG_CONTEXT] gui_trigger_control 需要非管理类容器界面");
          return;
        }
        UiProfile profile = GuiContext.resolveProfile(requestedProfile);
        this.ctrl = profile.findControl(controlName);
        if (ctrl == null) {
          finish("失败：UiProfile " + profile.id + " 中不存在控件 " + controlName);
          return;
        }
        this.expectedScreen = ac;
        this.menuRevBefore = MenuContext.revision();
        this.dataRevBefore = MenuContext.dataRevision();

        if (ctrl.kind().equals("widget_label")) {
          AbstractWidget w = GuiContext.findActiveWidgetByLabel(ctrl.label());
          if (w == null) {
            finish("失败：[GUI_TARGET_INVALID] 未找到可用控件：" + ctrl.label());
            return;
          }
          if (w instanceof AbstractButton b) {
            b.onPress();
            handled = true;
          } else {
            handled =
                w.mouseClicked(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0, ctrl.button());
            w.mouseReleased(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0, ctrl.button());
          }
          GuiContext.invalidate();
          waitTicks = 0;
          phase = Phase.WAIT_EFFECT;
          return;
        }

        // relative_point
        this.gx = ac.getGuiLeft() + ctrl.dx();
        this.gy = ac.getGuiTop() + ctrl.dy();
        if (gx < 0 || gy < 0 || gx >= ac.width || gy >= ac.height) {
          finish("失败：[INPUT_BOUNDS] 控件坐标超出当前GUI窗口：(" + gx + "," + gy + ")");
          return;
        }
        for (var slot : mc.player.containerMenu.slots) {
          if (GuiGeometry.inside(gx, gy, ac.getGuiLeft() + slot.x, ac.getGuiTop() + slot.y, 16, 16)) {
            finish("失败：控件偏移命中标准物品槽，请使用槽位操作工具");
            return;
          }
        }
        if (verifyTooltip && !ctrl.expectedTooltipContains().isEmpty()) {
          AgentPointer.beginProbe(gx, gy);
          TooltipCapture.start();
          waitTicks = 0;
          phase = Phase.WAIT_TOOLTIP;
          return;
        }
        this.handled =
            dispatchNativeButtonClick(expectedScreen, gx, gy, ctrl.button(), ctrl.shift());
        waitTicks = 0;
        phase = Phase.WAIT_EFFECT;
        return;
      }

      if (phase == Phase.WAIT_TOOLTIP) {
        if (mc.screen != expectedScreen) {
          AgentPointer.endProbe();
          TooltipCapture.reset();
          finish("部分完成：自检期间界面已变更，已中止点击。");
          return;
        }
        if (TooltipCapture.hasCaptured() || ++waitTicks >= 3) {
          JsonObject cap = TooltipCapture.consume();
          AgentPointer.endProbe();
          List<String> lines = new ArrayList<>();
          for (JsonElement el : cap.getAsJsonArray("lines")) lines.add(el.getAsString());
          boolean matched = false;
          for (String line : lines) {
            String low = line.toLowerCase(Locale.ROOT);
            for (String kw : ctrl.expectedTooltipContains()) {
              if (low.contains(kw.toLowerCase(Locale.ROOT))) {
                matched = true;
                break;
              }
            }
            if (matched) break;
          }
          if (!matched) {
            finish(
                "失败：[TOOLTIP_MISMATCH] 相对控件 "
                    + controlName
                    + " 悬停提示未包含预期关键词 "
                    + ctrl.expectedTooltipContains()
                    + "，实际="
                    + lines
                    + "；已中止点击以防界面偏移误触。");
            return;
          }
          this.handled =
              dispatchNativeButtonClick(expectedScreen, gx, gy, ctrl.button(), ctrl.shift());
          waitTicks = 0;
          phase = Phase.WAIT_EFFECT;
        }
        return;
      }

      // WAIT_EFFECT
      if (++waitTicks < 6) return;
      boolean screenChanged = mc.screen != expectedScreen;
      boolean menuUpdated = MenuContext.revision() > menuRevBefore;
      boolean dataChanged = MenuContext.dataRevision() > dataRevBefore;
      finish(
          "成功：已触发控件 "
              + controlName
              + "（handled="
              + handled
              + ", screen_changed="
              + screenChanged
              + ", menu_updated="
              + menuUpdated
              + ", data_changed="
              + dataChanged
              + "）；最终生产结果仍须观察核验。");
    }

    @Override
    String interrupted() {
      AgentPointer.endProbe();
      TooltipCapture.reset();
      return "部分完成：控件触发操作已中断，界面保留现场，请重新观察。";
    }
  }

  private static final class WaitOutputJob extends Actions.Job {
    private final String requestedProfile, role, itemFilter;
    private final boolean explicitRoleOrItem, hasDataSlot, hasDataTarget;
    private final int minCount, dataSlot, dataTarget, maxTicks;
    private final Screen expectedScreen;

    WaitOutputJob(JsonObject a, Consumer<String> done) {
      super(done);
      var mc = mc();
      if (!(mc.screen instanceof AbstractContainerScreen<?>)) {
        throw new IllegalArgumentException("[WRONG_CONTEXT] gui_wait_output 需要已打开的容器界面");
      }
      this.expectedScreen = mc.screen;
      this.requestedProfile = Agent.str(a, "profile", "");
      this.hasDataSlot = a.has("data_slot");
      this.dataSlot = hasDataSlot ? a.get("data_slot").getAsInt() : -1;
      this.hasDataTarget = a.has("data_target");
      this.dataTarget = hasDataTarget ? a.get("data_target").getAsInt() : 0;
      this.itemFilter = Agent.str(a, "item", "");
      boolean explicitRole = a.has("role") && !a.get("role").getAsString().isBlank();
      this.role = explicitRole ? a.get("role").getAsString().trim() : "output";
      this.explicitRoleOrItem = explicitRole || !itemFilter.isBlank() || !hasDataSlot;
      this.minCount = SkillCatalog.bounded(a, "min_count", 1, 1, 256);
      int secs = SkillCatalog.bounded(a, "seconds", 30, 1, 120);
      this.maxTicks = secs * 20;
    }

    @Override
    void tick() {
      var mc = mc();
      if (mc.screen != expectedScreen || mc.player == null) {
        finish("部分完成：等待期间菜单已关闭或切换。");
        return;
      }
      if (++ticks % 5 != 1 && ticks < maxTicks) return;
      if (MenuContext.synchronizedNow()) {
        boolean dataOk = true;
        if (hasDataSlot) {
          Integer v = MenuContext.dataSnapshot().get(dataSlot);
          dataOk = v != null && (hasDataTarget ? v == dataTarget : v > 0);
        }
        boolean roleOk = true;
        int observedCount = 0;
        if (explicitRoleOrItem) {
          UiProfile profile = GuiContext.resolveProfile(requestedProfile);
          List<UiProfile.ResolvedSlot> resolved =
              profile.resolveRoleSlots(role, GuiContext.liveSlots());
          if (resolved.isEmpty()) {
            finish("失败：UiProfile " + profile.id + " 中未匹配到角色槽位 " + role);
            return;
          }
          for (var rs : resolved) {
            ItemStack st = mc.player.containerMenu.getSlot(rs.index()).getItem();
            if (!st.isEmpty() && (itemFilter.isBlank() || matchesItemFilter(st, itemFilter))) {
              observedCount += st.getCount();
            }
          }
          roleOk = observedCount >= minCount;
        }
        if (dataOk && roleOk) {
          finish(
              "成功：等待目标达成（"
                  + (explicitRoleOrItem ? role + " 数量=" + observedCount : "")
                  + (hasDataSlot
                      ? (explicitRoleOrItem ? ", " : "")
                          + "data["
                          + dataSlot
                          + "]="
                          + MenuContext.dataSnapshot().get(dataSlot)
                      : "")
                  + "）。");
          return;
        }
      }
      if (ticks >= maxTicks) {
        finish(
            "部分完成：等待产出超时（未发送额外点击），data="
                + MenuContext.dataSnapshot()
                + "。");
      }
    }
  }

  public static JsonObject summarizeLatestDemo(Path demosRoot, String worldScope) {
    JsonObject out = new JsonObject();
    Path scopeDir =
        (worldScope == null || worldScope.isBlank())
            ? demosRoot
            : demosRoot.resolve(worldScope);
    if (!Files.isDirectory(scopeDir)) {
      out.addProperty("found", false);
      out.addProperty("reason", "尚未记录本地人工示范（请先开启 DEMO 模式并操作）");
      return out;
    }
    try (var stream = Files.list(scopeDir)) {
      List<Path> jsonFiles =
          stream
              .filter(p -> p.getFileName().toString().endsWith(".json"))
              .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
              .limit(5)
              .toList();
      if (jsonFiles.isEmpty()) {
        out.addProperty("found", false);
        out.addProperty("reason", "示范目录暂无记录");
        return out;
      }
      JsonArray entries = new JsonArray();
      String latestMenuType = "";
      String latestSignature = "";
      List<JsonObject> suggestedSlots = new ArrayList<>();
      for (Path p : jsonFiles) {
        try {
          JsonObject row =
              JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
          JsonObject summary = new JsonObject();
          summary.addProperty("file", p.getFileName().toString());
          summary.addProperty("action", Agent.str(row, "action", ""));
          String sig = Agent.str(row, "interface_signature", "");
          if (latestSignature.isEmpty() && !sig.isEmpty()) latestSignature = sig;
          JsonArray slotDiffs = diffDemoSlots(row.get("before"), row.get("after"));
          summary.add("slot_changes", slotDiffs);
          if (row.has("before") && row.get("before").isJsonObject() && latestMenuType.isEmpty()) {
            JsonObject beforeObj = row.getAsJsonObject("before");
            if (beforeObj.has("menu_type")) latestMenuType = beforeObj.get("menu_type").getAsString();
          }
          for (JsonElement diffEl : slotDiffs) {
            if (suggestedSlots.size() >= 12) break;
            JsonObject d = diffEl.getAsJsonObject();
            if (!d.has("player_inventory") || d.get("player_inventory").getAsBoolean()) continue;
            JsonObject sk = new JsonObject();
            sk.addProperty("role", d.get("after_count").getAsInt() > d.get("before_count").getAsInt() ? "input" : "output");
            sk.addProperty("menu_slot", d.get("menu_slot").getAsInt());
            if (d.has("x") && d.has("y")) {
              sk.addProperty("x", d.get("x").getAsInt());
              sk.addProperty("y", d.get("y").getAsInt());
            }
            sk.addProperty("note", "由示范变动推断，请人工确认角色");
            suggestedSlots.add(sk);
          }
          entries.add(summary);
        } catch (Exception ignored) {
        }
      }
      out.addProperty("found", true);
      out.addProperty("count", entries.size());
      out.add("demonstrations", entries);
      JsonObject skeleton = new JsonObject();
      skeleton.addProperty("id", "local:demo-draft");
      skeleton.addProperty("name", "人工示范提炼草稿");
      JsonObject match = new JsonObject();
      if (!latestMenuType.isEmpty()) {
        JsonArray mcArr = new JsonArray();
        mcArr.add(latestMenuType);
        match.add("menu_classes", mcArr);
      }
      skeleton.add("match", match);
      JsonArray slArr = new JsonArray();
      suggestedSlots.forEach(slArr::add);
      skeleton.add("slots", slArr);
      out.add("ui_profile_skeleton", skeleton);
      out.addProperty("legacy_signature", latestSignature);
      out.addProperty("note", "示范提炼仅为候选骨架，提交 ui_profile_draft 后仍须核验槽位角色。");
    } catch (Exception e) {
      out.addProperty("found", false);
      out.addProperty("reason", Rules.clean(e.getMessage(), 180));
    }
    return out;
  }

  private static JsonArray diffDemoSlots(JsonElement beforeEl, JsonElement afterEl) {
    JsonArray diffs = new JsonArray();
    if (beforeEl == null || afterEl == null || !beforeEl.isJsonObject() || !afterEl.isJsonObject()) {
      return diffs;
    }
    JsonObject b = beforeEl.getAsJsonObject();
    JsonObject a = afterEl.getAsJsonObject();
    if (!b.has("slots") || !a.has("slots") || !b.get("slots").isJsonArray() || !a.get("slots").isJsonArray()) {
      return diffs;
    }
    Map<Integer, JsonObject> beforeMap = new LinkedHashMap<>();
    for (JsonElement el : b.getAsJsonArray("slots")) {
      JsonObject s = el.getAsJsonObject();
      if (s.has("menu_slot")) beforeMap.put(s.get("menu_slot").getAsInt(), s);
    }
    for (JsonElement el : a.getAsJsonArray("slots")) {
      JsonObject as = el.getAsJsonObject();
      if (!as.has("menu_slot")) continue;
      int slot = as.get("menu_slot").getAsInt();
      JsonObject bs = beforeMap.get(slot);
      String bid = bs != null && bs.has("id") ? bs.get("id").getAsString() : "minecraft:air";
      int bc = bs != null && bs.has("count") ? bs.get("count").getAsInt() : 0;
      String aid = as.has("id") ? as.get("id").getAsString() : "minecraft:air";
      int ac = as.has("count") ? as.get("count").getAsInt() : 0;
      if (!bid.equals(aid) || bc != ac) {
        JsonObject row = new JsonObject();
        row.addProperty("menu_slot", slot);
        if (as.has("x") && as.has("y")) {
          row.addProperty("x", as.get("x").getAsInt());
          row.addProperty("y", as.get("y").getAsInt());
        }
        row.addProperty("player_inventory", as.has("player_inventory") && as.get("player_inventory").getAsBoolean());
        row.addProperty("before_item", bid);
        row.addProperty("before_count", bc);
        row.addProperty("after_item", aid);
        row.addProperty("after_count", ac);
        diffs.add(row);
      }
    }
    return diffs;
  }
}
