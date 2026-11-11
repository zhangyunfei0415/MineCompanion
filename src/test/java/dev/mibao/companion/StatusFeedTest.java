package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StatusFeedTest {
  @Test
  void importantNoticeStaysPinnedAcrossOrdinaryUpdatesUntilAcknowledged() {
    var feed = new StatusFeed();
    feed.add("失败：背包已满", "alert", 0);
    feed.add("正在查询仓库", "info", 1);
    assertEquals("失败：背包已满", feed.attention().text);
    feed.acknowledge();
    assertNull(feed.attention());
    assertEquals(2, feed.snapshot().size());
    feed.add("待审批：打开", "ask", 2);
    assertNotNull(feed.attention());
    feed.clear();
    assertNull(feed.attention());
  }

  @Test
  void messagesAreBoundedAndConsecutiveDuplicatesAreCoalesced() {
    var feed = new StatusFeed();
    feed.add("缺少铁锭", "ask", 100);
    feed.add("缺少铁锭", "ask", 200);
    assertEquals(1, feed.snapshot().size());
    assertEquals(2, feed.snapshot().get(0).repeats);
    feed.add("缺少铁锭", "ask", 10000);
    assertEquals(2, feed.snapshot().size());
    for (int i = 0; i < 100; i++) feed.add("消息" + i, "info", 11000 + i);
    assertEquals(40, feed.snapshot().size());
    assertEquals("消息60", feed.snapshot().get(0).text);
    feed.clear();
    assertTrue(feed.snapshot().isEmpty());
  }

  @Test
  void liveActivityOverridesOldPlanningAndFinishMessages() {
    assertEquals(
        StatusFeed.Kind.INTERFACE,
        StatusFeed.classify(true, false, false, true, "GUI槽位操作 · 等待服务器核对"));
    assertEquals(
        StatusFeed.Kind.INTERFACE, StatusFeed.classify(true, false, false, true, "背包整理 · 等待服务器确认"));
    assertEquals(
        StatusFeed.Kind.EATING, StatusFeed.classify(true, false, false, true, "进食 · 核对服务器背包与饱食度"));
    assertEquals(StatusFeed.Kind.SENSING, StatusFeed.classify(true, false, false, true, "观察附近世界"));
    assertEquals(
        StatusFeed.Kind.MINING,
        StatusFeed.classify(true, false, false, true, "采集 minecraft:oak_log 3/16"));
    assertEquals(
        StatusFeed.Kind.MINING, StatusFeed.classify(true, false, false, true, "采矿 · 已核实 3/16"));
    assertEquals(
        StatusFeed.Kind.COMBAT, StatusFeed.classify(true, false, false, true, "清怪 · 已确认死亡 0/1"));
    assertEquals(
        StatusFeed.Kind.COMBAT, StatusFeed.classify(true, false, false, true, "自卫 · 已确认攻击来源"));
    assertEquals(
        StatusFeed.Kind.HELP, StatusFeed.classify(false, false, false, false, "自卫未能及时脱险，请协助撤离"));
    assertEquals(
        StatusFeed.Kind.STORAGE, StatusFeed.classify(true, false, false, true, "取出 铁锭 16/32"));
    assertEquals(
        StatusFeed.Kind.WALKING, StatusFeed.classify(true, false, false, true, "前往 10, 64, 20"));
    assertEquals(
        StatusFeed.Kind.IDLE, StatusFeed.classify(true, false, false, false, "DeepSeek 正在规划"));
    assertEquals(StatusFeed.Kind.WAITING, StatusFeed.classify(true, true, true, true, "采集"));
    assertEquals(
        StatusFeed.Kind.HELP, StatusFeed.classify(false, false, false, false, "生命值过低，请协助撤离"));
    assertEquals(StatusFeed.Kind.REST, StatusFeed.classify(false, false, true, true, "采集"));
  }

  @Test
  void userMessagesDoNotIncludeBlankAndUnboundedControlText() {
    var feed = new StatusFeed();
    feed.add("", "info", 0);
    assertTrue(feed.snapshot().isEmpty());
    feed.add("x".repeat(10000) + "\u0000", "info", 1);
    assertEquals(1200, feed.snapshot().get(0).text.length());
    assertEquals("alert", StatusFeed.level("失败：箱子没有打开"));
    assertEquals("ask", StatusFeed.level("待审批：取出"));
    assertEquals("info", StatusFeed.level("成功：已确认1个目标死亡。"));
    assertEquals("ask", StatusFeed.level("自卫未能及时脱险，请协助撤离"));
  }

  @Test
  void sharedViewHasOnlyDisplayFields() {
    var fields =
        java.util.Arrays.stream(StatusFeed.View.class.getFields())
            .map(java.lang.reflect.Field::getName)
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(
        java.util.Set.of(
            "name",
            "player",
            "task",
            "detail",
            "theme",
            "scope",
            "kind",
            "updated",
            "requests",
            "inputTokens",
            "outputTokens",
            "health",
            "food",
            "scanMillis",
            "scanAge",
            "notices",
            "taskCard",
            "attention",
            "plan"),
        fields);
  }

  @Test
  void oldMessagesFadeWithoutDeletingTheHistoryOrKeepingErrorsForever() {
    assertEquals(255, StatusFeed.opacity(20_000, "info"));
    assertTrue(StatusFeed.opacity(25_000, "info") < 255);
    assertTrue(StatusFeed.opacity(25_000, "info") > 0);
    assertEquals(0, StatusFeed.opacity(30_000, "info"));
    assertEquals(255, StatusFeed.opacity(30_000, "alert"));
    assertEquals(0, StatusFeed.opacity(65_000, "alert"));
    var feed = new StatusFeed();
    feed.add("历史消息", "info", 0);
    assertEquals(0, StatusFeed.opacity(100_000, "info"));
    assertEquals(1, feed.snapshot().size());
  }

  @Test
  void avatarFollowsSharedActivityInsteadOfOldFreeText() {
    assertEquals(0, AvatarState.column(StatusFeed.Kind.REST, false, true));
    assertEquals(0, AvatarState.column(StatusFeed.Kind.WORKING, true, false));
    assertEquals(1, AvatarState.column(StatusFeed.Kind.STORAGE, false, false));
    assertEquals(2, AvatarState.column(StatusFeed.Kind.MINING, false, false));
    assertEquals(3, AvatarState.column(StatusFeed.Kind.MINING, false, true));
    assertEquals(4, AvatarState.column(StatusFeed.Kind.COMBAT, false, true));
    assertEquals(5, AvatarState.column(StatusFeed.Kind.THINKING, false, false));
    assertEquals(7, AvatarState.column(StatusFeed.Kind.WAITING, false, false));
  }
}
