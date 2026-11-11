package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CommunityBoardTest {
  @Test
  void fifoDoesNotReplaceActiveGoal() {
    var q = new CommunityBoard();
    var a = q.add("A", "Alice", "搬铁", "dim", "箱A", 1);
    a.state = "ACTIVE";
    var b = q.add("B", "Bob", "收菜", "dim", "箱B", 2);
    assertSame(a, q.active());
    assertSame(b, q.next("dim"));
    assertNull(q.next("other"));
    assertEquals("箱A", a.delivery);
  }

  @Test
  void sameRequestWithinThirtySecondsIsNotDuplicated() {
    var q = new CommunityBoard();
    var a = q.add("A", "Alice", "搬铁", "d", "", 1);
    assertSame(a, q.add("A", "Alice", "搬铁", "d", "", 100));
    assertEquals(1, q.tasks.size());
    assertNotSame(a, q.add("B", "Bob", "搬铁", "d", "", 100));
  }

  @Test
  void membersOnlyEditOwnPendingGoals() {
    var q = new CommunityBoard();
    var a = q.add("A", "Alice", "搬铁", "d", "", 1);
    assertThrows(IllegalArgumentException.class, () -> q.change(a.id, "B", false, "新目标"));
    q.change(a.id, "A", false, "改数量");
    assertEquals("改数量", a.text);
    q.change(a.id, "B", true, null);
    assertEquals("CANCELLED", a.state);
  }

  @Test
  void startedGoalsCannotBeEditedOrReplayedAsPending() {
    var q = new CommunityBoard();
    var a = q.add("A", "Alice", "投料", "d", "", 1);
    a.state = "ACTIVE";
    assertThrows(IllegalArgumentException.class, () -> q.change(a.id, "A", true, "投料"));
    q.reconnect();
    assertEquals("SUSPENDED", a.state);
    assertNull(q.next("d"));
  }

  @Test
  void emergencyStopAlsoCancelsWaitingMaterialGoals() {
    var q = new CommunityBoard();
    var a = q.add("A", "Alice", "投料", "d", "", 1);
    a.state = "WAITING_MATERIALS";
    var b = q.add("B", "Bob", "搬运", "d", "", 2);
    q.stop();
    assertEquals("STOPPED", a.state);
    assertEquals("STOPPED", b.state);
    assertNull(q.next("d"));
  }

  @Test
  void confirmedUuidWinsOverDisplayName() {
    var m = new CommunityBoard.Member();
    m.name = "Alice";
    m.uuid = "id-A";
    assertSame(m, CommunityBoard.resolve(List.of(m), "id-A", "Renamed"));
    assertNull(CommunityBoard.resolve(List.of(m), "id-B", "Alice"));
    assertNull(CommunityBoard.resolve(List.of(m), "", "Alice"));
    m.enabled = false;
    assertNull(CommunityBoard.resolve(List.of(m), "id-A", "Alice"));
  }

  @Test
  void explicitlyNamedUnboundMemberRequiresAConfirmedIdentity() {
    var m = new CommunityBoard.Member();
    m.name = "Alice";
    assertNull(CommunityBoard.resolve(List.of(m), "", "Alice"));
    assertSame(m, CommunityBoard.resolve(List.of(m), "confirmed", "Alice"));
  }

  @Test
  void queueAndPerSenderBudgetsAreBounded() {
    var q = new CommunityBoard();
    for (int i = 0; i < 6; i++) q.add("A", "Alice", "目标" + i, "d", "", i);
    assertThrows(IllegalArgumentException.class, () -> q.add("A", "Alice", "第七项", "d", "", 9));
    for (int i = 0; i < 18; i++) q.add("B" + i, "B", "目标", "d", "", i);
    assertThrows(IllegalArgumentException.class, () -> q.add("C", "C", "额外", "d", "", 99));
  }

  @Test
  void reportPreferenceIsSeparateFromPublicAuthorization() {
    assertEquals("BRIEF", CommunityReport.mode("DEFAULT", "BRIEF"));
    assertEquals("DETAIL", CommunityReport.mode("DEFAULT", "DETAIL"));
    assertEquals("BRIEF", CommunityReport.mode("BRIEF", "DETAIL"));
    assertFalse(CommunityReport.intermediate("BRIEF", true, true, false, "转头了"));
    assertTrue(CommunityReport.intermediate("BRIEF", false, false, true, "待审批：放置"));
    assertTrue(CommunityReport.intermediate("DETAIL", true, false, false, "阶段进展"));
    assertTrue(CommunityReport.compact("长".repeat(1000), "BRIEF").length() <= 110);
  }

  @Test
  void supplyWakeRequiresCurrentDimensionAndAllMaterialsWithoutChangingOriginalGoal() {
    var q = new CommunityBoard();
    var t = q.add("A", "Alice", "制作铁镐", "dim", "箱", 1);
    t.state = "WAITING_MATERIALS";
    t.requires.put("minecraft:iron_ingot", 3);
    t.note = "木棍已准备，尚未投料";
    assertFalse(CommunityBoard.wake(t, "other", id -> 3));
    assertFalse(CommunityBoard.wake(t, "dim", id -> 2));
    assertTrue(CommunityBoard.wake(t, "dim", id -> 3));
    assertEquals("WAITING", t.state);
    assertTrue(t.resume);
    assertEquals("制作铁镐", t.text);
    assertEquals("木棍已准备，尚未投料", t.note);
  }

  @Test
  void emptySupplyConditionCannotTriggerAutomaticResume() {
    var t = new CommunityBoard.Task();
    t.state = "WAITING_MATERIALS";
    t.dimension = "d";
    assertFalse(CommunityBoard.wake(t, "d", id -> 99));
  }
}
