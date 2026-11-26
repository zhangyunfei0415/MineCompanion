package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import dev.mibao.companion.MentionPolicy.ReplyPhase;
import dev.mibao.companion.MentionPolicy.Role;
import dev.mibao.companion.PlayerMentionInbox.State;
import java.util.*;
import org.junit.jupiter.api.Test;

class MentionPolicyAndInboxTest {

  @Test
  void extractsConfiguredOrGameIdAliasesAndRejectsEmptyOrUnknown() {
    var byConfig = MentionPolicy.extractMention("@小勤 帮我挖两个铁矿", "小勤", "BotPlayer");
    assertNotNull(byConfig);
    assertEquals("小勤", byConfig.alias());
    assertEquals("帮我挖两个铁矿", byConfig.body());
    assertTrue(byConfig.atStart());

    var byGameId = MentionPolicy.extractMention("＠botplayer 今天天气怎么样？", "小勤", "BotPlayer");
    assertNotNull(byGameId);
    assertEquals("BotPlayer", byGameId.alias());
    assertEquals("今天天气怎么样", byGameId.body());
    assertTrue(byGameId.atStart());

    var midSentence = MentionPolicy.extractMention("他刚才说了 @小勤 停止", "小勤", "BotPlayer");
    assertNotNull(midSentence);
    assertFalse(midSentence.atStart());

    var longerPrefix = MentionPolicy.extractMention("@Bot2 hi", "Bot", "Bot2");
    assertNotNull(longerPrefix);
    assertEquals("Bot2", longerPrefix.alias());
    assertEquals("hi", longerPrefix.body());

    assertNull(MentionPolicy.extractMention("@别人 你好", "小勤", "BotPlayer"));
    assertNull(MentionPolicy.extractMention("@小勤   ", "小勤", "BotPlayer"));
  }

  @Test
  void uuidBoundRoleResolutionBlocksImpostersAndRevokedMembers() {
    var alice = new CommunityBoard.Member();
    alice.name = "Alice";
    alice.uuid = "uuid-alice";
    alice.role = "MEMBER";
    alice.enabled = true;

    assertEquals(
        Role.MEMBER,
        MentionPolicy.resolveRole(
            List.of(alice), Set.of(), "OwnerA", "", "uuid-alice", "AliceRenamed"));
    // Imposter with same display name but different UUID must downgrade to GUEST.
    assertEquals(
        Role.GUEST,
        MentionPolicy.resolveRole(List.of(alice), Set.of(), "OwnerA", "", "uuid-evil", "Alice"));
    // Explicit revocation overrides even matching UUID.
    assertEquals(
        Role.GUEST,
        MentionPolicy.resolveRole(
            List.of(alice),
            Set.of("uuid:uuid-alice"),
            "OwnerA",
            "",
            "uuid-alice",
            "Alice"));
    assertEquals(
        Role.OWNER,
        MentionPolicy.resolveRole(List.of(), Set.of(), "OwnerA", "", "uuid-owner", "ownera"));
    assertEquals(
        Role.GUEST,
        MentionPolicy.resolveRole(List.of(), Set.of(), "OwnerA", "", "uuid-stranger", "Stranger"));
  }

  @Test
  void guestSandboxBlocksAllWriteAndSideEffectToolsAcrossEntireCatalog() {
    for (String tool : Rules.TOOLS) {
      if (MentionPolicy.isGuestReadOnlyTool(tool)) {
        assertFalse(Rules.WRITE.contains(tool), "Write tool leaked to guest: " + tool);
      }
    }
    for (String forbidden :
        List.of(
            "remember",
            "save_skill",
            "game_command",
            "chat_input",
            "team_task_wait",
            "team_task_done",
            "run_skill",
            "run_library_skill",
            "run_preset",
            "work_session",
            "run_order",
            "task_cancel",
            "duty_set",
            "config_reload",
            "observe",
            "place_block",
            "mine_expedition")) {
      assertFalse(
          MentionPolicy.isGuestReadOnlyTool(forbidden), "Side-effect tool allowed: " + forbidden);
    }
    assertTrue(MentionPolicy.isGuestReadOnlyTool("observe_compact"));
    assertTrue(MentionPolicy.isGuestReadOnlyTool("lookup_recipes"));
  }

  @Test
  void inboxLifecycleCooldownAndPublicOutboxGuardsHold() {
    var inbox = new PlayerMentionInbox();
    var mGuest =
        inbox.enqueue(
            "s|overworld", 1L, "uuid-g", "Guest1", Role.GUEST, "小勤", "你好呀", 10_000L);
    assertNotNull(mGuest);
    // 10s guest cooldown
    assertNull(
        inbox.enqueue(
            "s|overworld", 1L, "uuid-g", "Guest1", Role.GUEST, "小勤", "再来一次", 15_000L));
    // Guest cannot promote to work
    assertThrows(
        IllegalArgumentException.class,
        () -> inbox.resolveAsWork(mGuest.id, "s|overworld", "task-1", 11_000L));
    // Guest cannot use ACK phase
    assertThrows(
        IllegalArgumentException.class,
        () -> inbox.markReplySent(mGuest.id, "s|overworld", ReplyPhase.ACK, false, 11_000L));
    // Guest can receive 1 CHAT reply and immediately resolves
    var replied = inbox.markReplySent(mGuest.id, "s|overworld", ReplyPhase.CHAT, false, 11_000L);
    assertEquals(State.RESOLVED, replied.state);
    // Second reply fails
    assertThrows(
        IllegalArgumentException.class,
        () -> inbox.markReplySent(mGuest.id, "s|overworld", ReplyPhase.CHAT, true, 12_000L));

    // Member flow: PENDING -> ACKNOWLEDGED -> RESOLVED (with task_id)
    var mMember =
        inbox.enqueue(
            "s|overworld", 1L, "uuid-a", "Alice", Role.MEMBER, "小勤", "帮我挖两个铁", 20_000L);
    assertNotNull(mMember);
    assertNotNull(inbox.acknowledge(mMember.id, "s|overworld", 20_100L));
    inbox.markReplySent(mMember.id, "s|overworld", ReplyPhase.ACK, false, 20_200L);
    assertTrue(mMember.ackSent);
    assertEquals("task-99", inbox.resolveAsWork(mMember.id, "s|overworld", "task-99", 20_500L));

    // Public reply policy allows CHAT/ACK(once)/FINAL in BRIEF while filtering PROGRESS
    assertTrue(MentionPolicy.shouldSendPublicReply(true, ReplyPhase.ACK, "BRIEF", false, "好我试试"));
    assertFalse(
        MentionPolicy.shouldSendPublicReply(true, ReplyPhase.ACK, "BRIEF", true, "再发一遍ACK"));
    assertFalse(
        MentionPolicy.shouldSendPublicReply(true, ReplyPhase.PROGRESS, "BRIEF", true, "走到一半"));
    assertTrue(
        MentionPolicy.shouldSendPublicReply(true, ReplyPhase.FINAL, "BRIEF", true, "挖完啦"));

    // ChatOutbox rejects slash commands even behind whitespace or § codes
    var outbox = new ChatOutbox();
    assertFalse(outbox.offerSafe("小勤", "Alice", "/give @s diamond", "s|overworld", 1000L));
    assertFalse(outbox.offerSafe("小勤", "Alice", " \u00a7c/op Guest1", "s|overworld", 2000L));
    assertTrue(outbox.offerSafe("小勤", "Alice", "好，我试试看，这就去！", "s|overworld", 3000L));
    assertEquals("@Alice 小勤：好，我试试看，这就去！", outbox.poll("s|overworld", 3000L));

    // Stale/expired/closed mention_id must fail closed instead of dropping guest sandboxing.
    assertEquals(
        MentionPolicy.MentionActionDecision.NO_MENTION,
        MentionPolicy.evaluateActionMention("", false, null, false));
    assertEquals(
        MentionPolicy.MentionActionDecision.REJECT_STALE_OR_CLOSED,
        MentionPolicy.evaluateActionMention("m-expired", false, Role.GUEST, true));
    assertEquals(
        MentionPolicy.MentionActionDecision.REJECT_STALE_OR_CLOSED,
        MentionPolicy.evaluateActionMention("m-closed", true, Role.GUEST, true));
    assertEquals(
        MentionPolicy.MentionActionDecision.SANDBOX_GUEST,
        MentionPolicy.evaluateActionMention("m-guest", true, Role.GUEST, false));
    assertEquals(
        MentionPolicy.MentionActionDecision.ALLOW_MEMBER,
        MentionPolicy.evaluateActionMention("m-member", true, Role.MEMBER, false));
  }
}
