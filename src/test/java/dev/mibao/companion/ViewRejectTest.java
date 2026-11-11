package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Offline checks that stale view/control rejections stay diagnosable and survive the Agent failure
 * prefix without being misclassified as cursor occupation.
 */
class ViewRejectTest {
  @Test
  void staleCarriesExpectedAndActualView() {
    String text = ViewReject.stale("界面/控件已改变，请重新inspect_gui", 12, 13);
    assertTrue(text.startsWith("[STALE_VIEW] "));
    assertTrue(text.contains("expected_view=12"));
    assertTrue(text.contains("actual_view=13"));
    assertTrue(text.contains("界面/控件已改变，请重新inspect_gui"));
  }

  @Test
  void staleCodeSurvivesAgentFailurePrefix() {
    String result = "失败：" + ViewReject.stale("请重新quest_book_state，不能复用旧控件", 7, 9);
    assertTrue(Rules.actionFailed(result));
    var reason = ReasonCodes.describe(result);
    assertEquals("STALE_VIEW", reason.get("reason_code").getAsString());
    assertTrue(reason.get("reason_detail").getAsString().contains("expected_view=7"));
  }

  @Test
  void staleNeverMisclassifiedAsCursorOccupied() {
    String result = "失败：" + ViewReject.stale("目标槽位或鼠标物品已改变，请重新inspect_gui", 3, 4);
    var reason = ReasonCodes.describe(result);
    assertEquals("STALE_VIEW", reason.get("reason_code").getAsString());
    assertTrue(reason.get("reason_detail").getAsString().contains("actual_view=4"));
  }
}
