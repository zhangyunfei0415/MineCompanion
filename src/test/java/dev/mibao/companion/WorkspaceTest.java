package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class WorkspaceTest {
  @Test
  void callBoundariesAndChinesePunctuation() {
    assertEquals("搬铁", ChatCommand.extract("<Alice> ＠伙伴：搬铁！", "伙伴"));
    assertEquals("", ChatCommand.extract("@伙伴甲 搬铁", "伙伴"));
    assertEquals("进度", ChatCommand.extract("@Bot, 进度?", "bot"));
  }

  @Test
  void negativeStopDoesNotBecomeEmergency() {
    assertEquals("不要停止", ChatCommand.control("不要停止"));
    assertEquals("停止搬石头，改搬木头", ChatCommand.control("停止搬石头，改搬木头"));
    assertEquals("停止", ChatCommand.control("stop"));
  }

  @Test
  void editingMisspelledNameUsesStableRecord() {
    var members = new ArrayList<CommunityBoard.Member>();
    var a = MemberDirectory.save(members, "", "Alicee", "", "MEMBER", "", "DEFAULT");
    var id = a.id;
    var fixed = MemberDirectory.save(members, id, "Alice", "", "MEMBER", "", "BRIEF");
    assertSame(a, fixed);
    assertEquals(1, members.size());
    assertEquals("Alice", a.name);
  }

  @Test
  void duplicateAndBoundIdentityCannotBeReassigned() {
    var members = new ArrayList<CommunityBoard.Member>();
    var a = MemberDirectory.save(members, "", "Alice", "uuidA", "ADMIN", "", "DEFAULT");
    assertThrows(
        IllegalArgumentException.class,
        () -> MemberDirectory.save(members, a.id, "Bob", "uuidB", "MEMBER", "", "BRIEF"));
    assertThrows(
        IllegalArgumentException.class,
        () -> MemberDirectory.save(members, "", "Alice", "", "MEMBER", "", "BRIEF"));
  }

  @Test
  void revokedLegacyNameOrUuidCannotAutoMigrate() {
    var revoked = Set.of(MemberDirectory.key("Alice"), "uuid:idB");
    assertTrue(MemberDirectory.revoked(revoked, "", "ALICE"));
    assertTrue(MemberDirectory.revoked(revoked, "idB", "NewName"));
    assertFalse(MemberDirectory.revoked(revoked, "idC", "Charlie"));
  }

  @Test
  void timelineRedactsCredentialsAndLabelsEvidence() {
    assertTrue(TaskTimeline.clean("Authorization Bearer secret").contains("隐藏"));
    assertEquals("操作回执", TaskTimeline.label("result"));
    assertEquals("工作说明", TaskTimeline.label("note"));
  }
}
