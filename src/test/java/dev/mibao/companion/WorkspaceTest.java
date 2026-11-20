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
  void followOrdersRecognizeOnlyCompleteCommands() {
    assertEquals(new ChatCommand.Follow(ChatCommand.FollowAction.START, ""),
        ChatCommand.follow(" 跟我来！ "));
    assertEquals(new ChatCommand.Follow(ChatCommand.FollowAction.START, "Lmao"),
        ChatCommand.follow("跟随玩家　Lmao"));
    assertEquals(ChatCommand.FollowAction.STOP, ChatCommand.follow("停止跟随").action());
    assertEquals(ChatCommand.FollowAction.STATUS, ChatCommand.follow("跟随状态").action());
    assertNull(ChatCommand.follow("先别跟我"));
    assertNull(ChatCommand.follow("跟随 Lmao 后去采矿"));
  }

  @Test
  void remoteFollowOrderMustStartThePlayersOwnMessage() {
    assertEquals("跟我", ChatCommand.followMention(" ＠小勤：跟我！", "小勤"));
    assertEquals("停止跟随", ChatCommand.followMention("@小勤 停止跟随", "小勤"));
    assertEquals("", ChatCommand.followMention("<Alice> @小勤 跟我", "小勤"));
    assertEquals("", ChatCommand.followMention("我刚才说过 @小勤 跟我", "小勤"));
    assertEquals("", ChatCommand.followMention("@小勤甲 跟我", "小勤"));
  }

  @Test
  void naturalFollowAndPortalOrdersDoNotCaptureCompoundGoals() {
    assertEquals("跟着我", ChatCommand.followMention("@小勤 跟着我", "小勤"));
    assertEquals(new ChatCommand.Follow(ChatCommand.FollowAction.PORTAL, "end"),
        ChatCommand.follow("进入末地传送门"));
    assertTrue(ChatCommand.incompleteFollow("跟随一下"));
    assertFalse(ChatCommand.incompleteFollow("跟着我去挖铁"));
    assertNull(ChatCommand.follow("跟着我去挖铁"));
    assertTrue(FollowPolicy.freshPortal(120));
    assertFalse(FollowPolicy.freshPortal(121));
    assertFalse(FollowPolicy.freshPortal(-1));
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
