package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChatOutboxTest {
  @Test
  void rateLimitsAndDeduplicatesWithoutLosingFullSidebarText() {
    var q = new ChatOutbox();
    q.offer("小勤", "主人", "补给完成", "A", 0);
    q.offer("小勤", "主人", "补给完成", "A", 1);
    q.offer("小勤", "主人", "缺货", "A", 2);
    assertEquals(2, q.size());
    assertEquals("@主人 小勤：补给完成", q.poll("A", 0));
    assertNull(q.poll("A", 2999));
    assertEquals("@主人 小勤：缺货", q.poll("A", 3000));
  }

  @Test
  void worldSwitchDropsOldAnnouncementsAndQueueIsBounded() {
    var q = new ChatOutbox();
    q.offer("小勤", "", "旧世界", "A", 0);
    q.offer("小勤", "", "新世界", "B", 1);
    assertEquals("小勤：新世界", q.poll("B", 0));
    assertNull(q.poll("B", 3000));
    for (int i = 0; i < 100; i++) q.offer("小勤", "", "消息" + i, "B", i + 100);
    assertEquals(16, q.size());
    q.clear();
    assertEquals(0, q.size());
  }

  @Test
  void sanitizesLongPublicMessagesWithoutBrokenSurrogates() {
    var q = new ChatOutbox();
    q.offer("小勤", "", "\n任务\t" + "😀".repeat(500), "A", 0);
    String text = q.poll("A", 0);
    assertTrue(text.length() <= 250);
    assertFalse(text.contains("\n"));
    assertFalse(text.contains("\t"));
    for (int i = 0; i < text.length(); i++)
      if (Character.isHighSurrogate(text.charAt(i)))
        assertTrue(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(++i)));
  }
}
