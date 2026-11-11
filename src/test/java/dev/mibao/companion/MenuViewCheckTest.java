package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class MenuViewCheckTest {
  private static MenuViewCheck.Facts facts(
      boolean sync,
      boolean current,
      boolean expired,
      boolean menu,
      boolean state,
      boolean cursorEmpty,
      boolean targetObserved,
      boolean evidence) {
    return new MenuViewCheck.Facts(
        sync, current, expired, menu, state, cursorEmpty, targetObserved, evidence);
  }

  private static MenuViewCheck.Facts ready() {
    return facts(true, true, false, false, false, true, true, true);
  }

  @Test
  void aFullyMatchingObservationIsAccepted() {
    assertEquals("", MenuViewCheck.code(ready()));
  }

  @Test
  void anUnsyncedMenuIsReportedBeforeAnythingElse() {
    var bad = facts(false, false, false, true, true, false, false, false);
    assertEquals(MenuViewCheck.MENU_NOT_SYNCED, MenuViewCheck.code(bad));
  }

  @Test
  void reusingAnOldViewIsStaleViewAndNotCursorOccupied() {
    // The 0.7.8.34 failure: a passive observe consumed the number, then a click was refused with a
    // generic cursor wording although the cursor was empty.
    var stale = facts(true, false, false, false, false, true, true, true);
    assertEquals(MenuViewCheck.STALE_VIEW, MenuViewCheck.code(stale));
    String text =
        MenuViewCheck.message(MenuViewCheck.STALE_VIEW, "（提交 view_id=46614，当前 view_id=46623）");
    assertTrue(text.startsWith("[STALE_VIEW]"));
    assertTrue(text.contains("46614"));
    assertTrue(text.contains("46623"));
    assertFalse(text.contains("CURSOR_OCCUPIED"));
  }

  @Test
  void aViewOlderThanTheTtlExpiresButTheBoundaryDoesNot() {
    long now = 50_000_000L;
    assertFalse(MenuViewCheck.expired(0, now));
    assertFalse(MenuViewCheck.expired(now - MenuViewCheck.VIEW_TTL_MILLIS, now));
    assertTrue(MenuViewCheck.expired(now - MenuViewCheck.VIEW_TTL_MILLIS - 1, now));
    var expired = facts(true, true, true, false, false, true, true, true);
    assertEquals(MenuViewCheck.VIEW_EXPIRED, MenuViewCheck.code(expired));
  }

  @Test
  void menuAndStateChangesUseDistinctCodes() {
    var menuChanged = facts(true, true, false, true, false, true, true, true);
    assertEquals(MenuViewCheck.MENU_CHANGED, MenuViewCheck.code(menuChanged));
    var stateChanged = facts(true, true, false, false, true, true, true, true);
    assertEquals(MenuViewCheck.MENU_STATE_CHANGED, MenuViewCheck.code(stateChanged));
  }

  @Test
  void anOccupiedCursorIsItsOwnActionableCode() {
    var held = facts(true, true, false, false, false, false, true, true);
    assertEquals(MenuViewCheck.CURSOR_OCCUPIED, MenuViewCheck.code(held));
    var receipt =
        ReasonCodes.describe("失败：" + MenuViewCheck.message(MenuViewCheck.CURSOR_OCCUPIED, ""));
    assertEquals("CURSOR_OCCUPIED", receipt.get("reason_code").getAsString());
    assertEquals(
        "stow_cursor", receipt.getAsJsonArray("suggested_alternatives").get(0).getAsString());
  }

  @Test
  void unobservedSlotAndChangedSlotStayDistinct() {
    var notObserved = facts(true, true, false, false, false, true, false, true);
    assertEquals(MenuViewCheck.SLOT_NOT_OBSERVED, MenuViewCheck.code(notObserved));
    var changed = facts(true, true, false, false, false, true, true, false);
    assertEquals(MenuViewCheck.SLOT_CHANGED, MenuViewCheck.code(changed));
  }

  @Test
  void everyRefusalReachesTheReceiptAsAnExactReasonCode() {
    var codes =
        new String[] {
          MenuViewCheck.MENU_NOT_SYNCED,
          MenuViewCheck.STALE_VIEW,
          MenuViewCheck.VIEW_EXPIRED,
          MenuViewCheck.MENU_CHANGED,
          MenuViewCheck.MENU_STATE_CHANGED,
          MenuViewCheck.CURSOR_OCCUPIED,
          MenuViewCheck.SLOT_NOT_OBSERVED,
          MenuViewCheck.SLOT_CHANGED
        };
    for (String code : codes) {
      String message = MenuViewCheck.message(code, "（诊断）");
      assertTrue(message.startsWith("[" + code + "]"), message);
      assertTrue(message.length() < 300, message);
      JsonObject receipt = ReasonCodes.describe("失败：" + message);
      assertEquals(code, receipt.get("reason_code").getAsString(), message);
      assertTrue(Rules.actionFailed("失败：" + message));
    }
  }

  @Test
  void diagnosticsAreAppendedInsideTheSentence() {
    String withDetail = MenuViewCheck.message(MenuViewCheck.MENU_CHANGED, "（menu_id 9→10）");
    String withoutDetail = MenuViewCheck.message(MenuViewCheck.MENU_CHANGED, "");
    assertTrue(withDetail.contains("（menu_id 9→10）。"));
    assertTrue(withoutDetail.endsWith("。"));
    assertFalse(withoutDetail.contains("（）"));
  }
}
