package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FailureAdviceTest {
  @Test
  void synchronizationUncertaintyNeverSuggestsRepeatingWrites() {
    var o = FailureAdvice.json("部分完成：同步超时，投料未核实");
    assertEquals("unverified", o.get("code").getAsString());
    assertFalse(o.get("auto_retry").getAsBoolean());
    assertEquals("observe", o.get("suggested_tool").getAsString());
  }

  @Test
  void recoverableFailureDoesNotDemandPlayerIntervention() {
    assertEquals("health", FailureAdvice.forResult("失败：[RECOVERY_REQUIRED] 生命恢复中").code());
    assertEquals(
        "try_bounded_self_recovery_before_asking",
        FailureAdvice.json("失败：没有可达站位").get("escalation").getAsString());
    assertEquals(
        "player_decision_required", FailureAdvice.json("失败：动作未授权").get("escalation").getAsString());
    assertEquals(
        "reobserve_without_replaying_writes",
        FailureAdvice.json("部分完成：同步超时，投料未核实").get("escalation").getAsString());
  }

  @Test
  void rejectedTreeDoesNotBecomeRouteFailureFromDiagnosticCounters() {
    var h = FailureAdvice.forResult("部分完成：不符合完整自然树条件 5 个原木采样；不可达/变化 0");
    assertEquals("natural_tree", h.code());
    assertEquals("query_world", h.tool());
  }

  @Test
  void permissionAndChangedMenusHaveDistinctRecoveryPaths() {
    assertEquals("permission", FailureAdvice.forResult("失败：当前动作未授权").code());
    assertEquals("inspect_gui", FailureAdvice.forResult("失败：菜单已经改变，旧槽位作废").tool());
    assertEquals("route", FailureAdvice.forResult("失败：没有可达站位，存在高差").code());
  }

  @Test
  void materialsAdviceUsesReadOnlyPreflightAndUnknownErrorsRemainObservation() {
    assertEquals("check_task", FailureAdvice.forResult("失败：现有普通材料无法完成整份计划").tool());
    assertEquals("observe", FailureAdvice.forResult("失败：未适配操作").tool());
    assertTrue(Rules.TOOLS.contains("check_task"));
    assertFalse(Rules.WRITE.contains("check_task"));
  }
}
