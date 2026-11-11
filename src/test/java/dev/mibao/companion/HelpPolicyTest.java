package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HelpPolicyTest {
  @Test
  void muteOnlyHelpNotReceiptsOrDirectReplies() {
    assertTrue(HelpPolicy.suppress(false, false, "生命偏低，请协助撤离"));
    assertTrue(HelpPolicy.suppress(false, false, "恢复待处理：没有普通食物"));
    assertFalse(HelpPolicy.suppress(false, false, "成功：服务器确认存入32"));
    assertFalse(HelpPolicy.suppress(false, true, "主人问我如何求助"));
    assertFalse(HelpPolicy.suppress(true, false, "需要求助"));
  }

  @Test
  void permissionRequestsRemainVisibleWhenHelpIsMuted() {
    assertFalse(HelpPolicy.suppress(false, false, "请授权一次，否则需要求助"));
    assertFalse(HelpPolicy.suppress(false, false, "待审批：eat_food"));
  }

  @Test
  void testingBypassDoesNotChangeNormalThresholds() {
    assertTrue(SurvivalPolicy.blocked(20, SurvivalPolicy.critical(100), false));
    assertFalse(SurvivalPolicy.blocked(20, SurvivalPolicy.critical(100), true));
    assertFalse(SurvivalPolicy.blocked(50, SurvivalPolicy.work(100), false));
  }
}
