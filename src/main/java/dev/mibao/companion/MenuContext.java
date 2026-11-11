package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

/** Bind only menus opened by a recent verified block interaction to a world location. */
public final class MenuContext {
  private static BlockPos pending, source;
  private static String dimension = "";
  private static long expires;
  private static int id = -1;
  private static AbstractContainerMenu bound;
  private static long remembered = -1;
  private static final MenuSyncEvidence<ItemStack> sync =
      new MenuSyncEvidence<>(ItemStack::matches);
  private static final MenuEvidence<ItemStack> evidence = new MenuEvidence<>(ItemStack::matches);
  private static Set<Integer> observedSlots = Set.of();
  private static long observedView, observedAt;
  private static int observedMenuId = -1, observedState = -1;
  private static String observedLayout = "";

  private static String layout() {
    var mc = Minecraft.getInstance();
    if (mc.player == null) return "";
    var m = mc.player.containerMenu;
    var out =
        new StringBuilder(
            m.getClass().getName()
                + ":"
                + m.slots.size()
                + ":"
                + mc.getWindow().getGuiScaledWidth()
                + ":"
                + mc.getWindow().getGuiScaledHeight());
    for (var s : m.slots)
      out.append('|')
          .append(s.getClass().getName())
          .append(':')
          .append(s.x)
          .append(',')
          .append(s.y);
    return out.toString();
  }

  /** The newest captured observation; 0 means no click may use inspect_menu/inspect_gui. */
  public static long view() {
    return observedView;
  }

  /** Invalidate the current observation after its click was issued, so a receipt cannot replay. */
  public static void consumeObservation() {
    clearObservation();
  }

  private static void clearObservation() {
    observedView = 0;
    observedAt = 0;
    observedMenuId = -1;
    observedState = -1;
    observedSlots = Set.of();
    observedLayout = "";
    evidence.clear();
  }

  public static void reset() {
    pending = null;
    source = null;
    id = -1;
    expires = 0;
    dimension = "";
    bound = null;
    remembered = -1;
    sync.clear();
    clearObservation();
  }

  public static void expect(BlockPos pos) {
    var mc = Minecraft.getInstance();
    pending = pos.immutable();
    source = null;
    id = -1;
    dimension = mc.level.dimension().location().toString();
    expires = System.currentTimeMillis() + 5000;
  }

  public static void tick() {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) {
      reset();
      return;
    }
    if (!dimension.equals(mc.level.dimension().location().toString())) {
      reset();
      dimension = mc.level.dimension().location().toString();
      return;
    }
    var menu = mc.player.containerMenu;
    if (bound != menu) {
      bound = menu;
      sync.bind(menu, menu.containerId);
      remembered = -1;
      clearObservation();
    }
    if (source != null && (menu == mc.player.inventoryMenu || menu.containerId != id)) {
      source = null;
      id = -1;
    }
    if (pending != null) {
      if (System.currentTimeMillis() > expires) {
        pending = null;
        return;
      }
      if (menu != mc.player.inventoryMenu) {
        source = pending;
        pending = null;
        id = menu.containerId;
      }
    }
  }

  public static boolean matches(BlockPos pos) {
    tick();
    return source != null
        && source.equals(pos)
        && Minecraft.getInstance().player.containerMenu.containerId == id;
  }

  public static BlockPos source() {
    tick();
    return source;
  }

  public static void content(int menuId, int state, List<ItemStack> slots, ItemStack cursor) {
    tick();
    if (bound != null)
      sync.content(bound, menuId, state, slots, cursor, System.currentTimeMillis());
  }

  public static void slot(int menuId, int state, int slot, ItemStack value) {
    tick();
    if (bound == null) return;
    if (menuId == -1 && slot == -1) sync.cursor(bound, value, System.currentTimeMillis());
    else if (bound != Minecraft.getInstance().player.inventoryMenu
        && (menuId == 0 || menuId == -2)) {
      // Vanilla can synchronize player inventory through menu 0 / inventory indices while a
      // custom container is open. Map that real receipt into its shared player slots.
      var p = Minecraft.getInstance().player;
      int inventorySlot = -1;
      if (menuId == -2) inventorySlot = slot;
      else if (slot >= 0 && slot < p.inventoryMenu.slots.size()) {
        var original = p.inventoryMenu.getSlot(slot);
        if (original.container == p.getInventory()) inventorySlot = original.getContainerSlot();
      }
      if (inventorySlot >= 0)
        for (int i = 0; i < bound.slots.size(); i++) {
          var candidate = bound.getSlot(i);
          if (candidate.container == p.getInventory()
              && candidate.getContainerSlot() == inventorySlot)
            sync.slot(bound, bound.containerId, sync.state(), i, value, System.currentTimeMillis());
        }
    } else sync.slot(bound, menuId, state, slot, value, System.currentTimeMillis());
  }

  public static boolean synchronizedNow() {
    tick();
    if (bound == null) return false;
    var values = bound.slots.stream().map(Slot::getItem).toList();
    if (AeTerminalBridge.available() && sync.received() && sync.size() <= values.size())
      values = values.subList(0, sync.size());
    return sync.ready(
        bound, bound.containerId, values, bound.getCarried(), System.currentTimeMillis(), 0);
  }

  public static String syncStatus() {
    return synchronizedNow()
        ? "server_confirmed"
        : sync.received()
            ? "waiting_for_stable_server_contents"
            : "waiting_for_server_full_content";
  }

  public static boolean targetConfirmed(int slot) {
    tick();
    return bound != null
        && slot >= 0
        && slot < bound.slots.size()
        && sync.targetReady(
            bound, bound.containerId, slot, bound.getSlot(slot).getItem(), bound.getCarried());
  }

  public static long revision() {
    return sync.revision();
  }

  public static int serverState() {
    return sync.state();
  }

  public static long receivedAt() {
    return sync.receivedAt();
  }

  public static void refreshMemory() {
    tick();
    if (source != null && remembered != sync.revision() && synchronizedNow()) {
      try {
        WorldSense.rememberInventory(source);
        remembered = sync.revision();
      } catch (IllegalArgumentException e) {
        // Unsupported world-memory categories must not erase a valid menu receipt.
        remembered = sync.revision();
      }
    }
  }

  public static String kind() {
    var m = Minecraft.getInstance().player.containerMenu;
    if (m == Minecraft.getInstance().player.inventoryMenu) return "player_inventory";
    Class<?> type = m.getClass();
    return type == ChestMenu.class
            || type == HopperMenu.class
            || type == DispenserMenu.class
            || type == ShulkerBoxMenu.class
        ? "storage"
        : "unclassified";
  }

  public static long capture(int offset, int limit) {
    var m = Minecraft.getInstance().player.containerMenu;
    Map<Integer, ItemStack> slots = new HashMap<>();
    for (int i = offset; i < Math.min(m.slots.size(), offset + limit); i++)
      slots.put(i, m.slots.get(i).getItem().copy());
    observedSlots = Set.copyOf(slots.keySet());
    observedMenuId = m.containerId;
    observedState = m.getStateId();
    observedLayout = layout();
    observedAt = System.currentTimeMillis();
    observedView = evidence.capture(m, m.containerId, m.getStateId(), slots, m.getCarried().copy());
    return observedView;
  }

  /**
   * Reject a click unless the submitted ids still describe the newest capture and no observed slot,
   * cursor or menu state moved. Failures carry a distinct reason code.
   */
  public static void validate(int expectedId, int expectedState, long view, int target) {
    require(expectedId, expectedState, view, target);
  }

  /**
   * Same validation for jobs that already received a fresh menu view; ids come from the capture.
   */
  public static void validateStored(long view, int target) {
    require(observedMenuId, observedState, view, target);
  }

  private static void require(int expectedId, int expectedState, long view, int target) {
    tick();
    boolean sync = synchronizedNow();
    var m = Minecraft.getInstance().player.containerMenu;
    Map<Integer, ItemStack> current = new HashMap<>();
    for (int i : observedSlots) if (i < m.slots.size()) current.put(i, m.slots.get(i).getItem());
    boolean valid =
        target >= 0
            && target < m.slots.size()
            && observedLayout.equals(layout())
            && evidence.validTarget(
                m, observedMenuId, view, target, m.getSlot(target).getItem(), m.getCarried());
    var facts =
        new MenuViewCheck.Facts(
            sync,
            view > 0 && view == observedView,
            MenuViewCheck.expired(observedAt, System.currentTimeMillis()),
            expectedId != m.containerId
                || observedMenuId != m.containerId
                || !observedLayout.equals(layout()),
            expectedState != observedState,
            true,
            observedSlots.contains(target),
            valid);
    String code = MenuViewCheck.code(facts);
    if (!code.isEmpty())
      throw new IllegalArgumentException(
          MenuViewCheck.message(code, detail(code, view, expectedId, expectedState, m, target)));
  }

  private static String detail(
      String code,
      long view,
      int expectedId,
      int expectedState,
      AbstractContainerMenu m,
      int target) {
    return switch (code) {
      case MenuViewCheck.MENU_NOT_SYNCED -> "（menu_sync=" + syncStatus() + "）";
      case MenuViewCheck.STALE_VIEW ->
          "（expected_view=" + view + " actual_view=" + observedView + "）";
      case MenuViewCheck.VIEW_EXPIRED ->
          "（expected_view="
              + view
              + " actual_view="
              + observedView
              + "，上次观察于 "
              + Math.max(0, (System.currentTimeMillis() - observedAt) / 1000)
              + " 秒前）";
      case MenuViewCheck.MENU_CHANGED ->
          "（expected_menu_id="
              + expectedId
              + " observed_menu_id="
              + observedMenuId
              + " actual_menu_id="
              + m.containerId
              + "）";
      case MenuViewCheck.MENU_STATE_CHANGED ->
          "（expected_menu_state_id="
              + expectedState
              + " observed_menu_state_id="
              + observedState
              + " actual_menu_state_id="
              + m.getStateId()
              + "）";
      case MenuViewCheck.SLOT_NOT_OBSERVED ->
          "（目标槽 " + target + "，本次观察 " + observedSlots.size() + " 槽）";
      default -> "";
    };
  }
}
