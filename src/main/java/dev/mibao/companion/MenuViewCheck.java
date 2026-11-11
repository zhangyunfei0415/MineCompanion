package dev.mibao.companion;

/**
 * Pure classification of menu-observation failures.
 *
 * <p>The class references no Minecraft types so the model-facing receipts stay unit-testable
 * without a game runtime. Callers gather {@link Facts} from the live menu; every message starts
 * with {@code [CODE]} so {@link ReasonCodes} reports the exact refusal reason instead of a
 * heuristic that could be mistaken for a stale cursor.
 */
public final class MenuViewCheck {
  /** One observation may be used for this long; a longer idle period needs a fresh inspect. */
  public static final long VIEW_TTL_MILLIS = 30_000L;

  public static final String MENU_NOT_SYNCED = "MENU_NOT_SYNCED";
  public static final String STALE_VIEW = "STALE_VIEW";
  public static final String VIEW_EXPIRED = "VIEW_EXPIRED";
  public static final String MENU_CHANGED = "MENU_CHANGED";
  public static final String MENU_STATE_CHANGED = "MENU_STATE_CHANGED";
  public static final String CURSOR_OCCUPIED = "CURSOR_OCCUPIED";
  public static final String SLOT_NOT_OBSERVED = "SLOT_NOT_OBSERVED";
  public static final String SLOT_CHANGED = "SLOT_CHANGED";

  /**
   * Facts collected before an observation-based click, in the order they are classified.
   *
   * @param synchronizedNow the server sent a complete, stable slot snapshot
   * @param viewCurrent the submitted observation number is the newest one
   * @param viewExpired the newest observation is older than {@link #VIEW_TTL_MILLIS}
   * @param menuChanged the open menu or the submitted menu id no longer matches the observation
   * @param stateChanged the menu state id differs from the observed or submitted one
   * @param cursorEmpty no item is held on the cursor
   * @param targetObserved the target slot belongs to the captured observation range
   * @param evidenceValid every captured slot and the cursor still match the observation
   */
  public record Facts(
      boolean synchronizedNow,
      boolean viewCurrent,
      boolean viewExpired,
      boolean menuChanged,
      boolean stateChanged,
      boolean cursorEmpty,
      boolean targetObserved,
      boolean evidenceValid) {}

  /** Returns the most actionable refusal code, or an empty string when the click may proceed. */
  public static String code(Facts facts) {
    if (!facts.synchronizedNow()) return MENU_NOT_SYNCED;
    if (!facts.viewCurrent()) return STALE_VIEW;
    if (facts.viewExpired()) return VIEW_EXPIRED;
    if (facts.menuChanged()) return MENU_CHANGED;
    if (facts.stateChanged()) return MENU_STATE_CHANGED;
    if (!facts.cursorEmpty()) return CURSOR_OCCUPIED;
    if (!facts.targetObserved()) return SLOT_NOT_OBSERVED;
    if (!facts.evidenceValid()) return SLOT_CHANGED;
    return "";
  }

  /** Whether an observation captured at {@code observedAt} is too old to click at {@code now}. */
  public static boolean expired(long observedAt, long now) {
    return observedAt > 0 && now - observedAt > VIEW_TTL_MILLIS;
  }

  /**
   * Formats one refusal receipt.
   *
   * @param code a code returned by {@link #code(Facts)}
   * @param detail optional Chinese diagnostic suffix such as menu ids; may be empty
   */
  public static String message(String code, String detail) {
    String text =
        switch (code) {
          case MENU_NOT_SYNCED -> "菜单还没有稳定的服务器槽位快照，请稍候重新 inspect_menu 后再操作";
          case STALE_VIEW -> "观察编号不是当前编号，请重新 inspect_menu（或 inspect_gui）后再操作，禁止复用旧编号";
          case VIEW_EXPIRED -> "观察编号已超过" + VIEW_TTL_MILLIS / 1000 + "秒未刷新，请重新 inspect_menu 后再操作";
          case MENU_CHANGED -> "打开的菜单已改变，请重新 inspect_menu 后再操作";
          case MENU_STATE_CHANGED -> "菜单内容状态已更新，请重新 inspect_menu 后再操作";
          case CURSOR_OCCUPIED -> "鼠标持有物品，请先 stow_cursor 收好再 click_slot；本次没有发送点击";
          case SLOT_NOT_OBSERVED -> "目标槽位不在本次观察范围，请用 inspect_menu 的 offset/limit 覆盖该槽后再操作";
          case SLOT_CHANGED -> "已观察槽位或鼠标物品在观察后发生变化，请重新 inspect_menu 后再操作";
          default -> "菜单观察不可用，请重新 inspect_menu 后再操作";
        };
    return "[" + code + "] " + text + (detail == null || detail.isBlank() ? "" : detail) + "。";
  }

  private MenuViewCheck() {}
}
