package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Offline rules for the one-step quest title resolver: unique matches open, ambiguous matches keep
 * a candidate list, and no game runtime is needed.
 */
class QuestMatchTest {
  private static QuestMatch.Entry open(int widget, String label) {
    return new QuestMatch.Entry(widget, label, true);
  }

  private static QuestMatch.Entry plain(int widget, String label) {
    return new QuestMatch.Entry(widget, label, false);
  }

  @Test
  void matchesAcceptsEmptyExactIgnoringCaseAndSubstring() {
    assertTrue(QuestMatch.matches("做出水瓶", ""));
    assertTrue(QuestMatch.matches("做出水瓶", "做出水瓶"));
    assertTrue(QuestMatch.matches("Mining Time", "mining time"));
    assertTrue(QuestMatch.matches("做出水瓶任务", "水瓶"));
    assertFalse(QuestMatch.matches("做出水瓶", "做水桶"));
    assertFalse(QuestMatch.matches("", "水瓶"));
    assertFalse(QuestMatch.matches(null, "水瓶"));
  }

  @Test
  void resolveUniqueExactReturnsWidget() {
    QuestMatch.Resolution res = QuestMatch.resolve(List.of(open(3, "做出水瓶"), open(7, "挖矿")), "做出水瓶");
    assertTrue(res.matched());
    assertEquals(3, res.widget());
    assertEquals("", res.code());
    assertTrue(res.message().contains("widget=3"));
    assertEquals(1, res.candidates().size());
  }

  @Test
  void resolveIgnoresNonSemanticRows() {
    QuestMatch.Resolution res =
        QuestMatch.resolve(List.of(plain(1, "章节"), open(5, "做出水瓶")), "做出水瓶");
    assertTrue(res.matched());
    assertEquals(5, res.widget());
  }

  @Test
  void resolveCaseInsensitiveExact() {
    QuestMatch.Resolution res = QuestMatch.resolve(List.of(open(2, "Craft Water")), "craft water");
    assertTrue(res.matched());
    assertEquals(2, res.widget());
  }

  @Test
  void resolveUniqueSubstring() {
    QuestMatch.Resolution res = QuestMatch.resolve(List.of(open(4, "做出水瓶任务"), open(8, "挖矿")), "水瓶");
    assertTrue(res.matched());
    assertEquals(4, res.widget());
  }

  @Test
  void resolveAmbiguousListsCandidatesAndNeverPicks() {
    QuestMatch.Resolution res =
        QuestMatch.resolve(List.of(open(1, "做出水桶"), open(2, "做出水瓶")), "做出水");
    assertFalse(res.matched());
    assertEquals("QUEST_AMBIGUOUS", res.code());
    assertTrue(res.message().contains("widget=1"));
    assertTrue(res.message().contains("widget=2"));
    assertEquals(2, res.candidates().size());
  }

  @Test
  void resolveEmptyQueryIsArgumentRequired() {
    QuestMatch.Resolution res = QuestMatch.resolve(List.of(open(1, "做出水瓶")), "  ");
    assertFalse(res.matched());
    assertEquals("ARGUMENT_REQUIRED", res.code());
    assertTrue(res.message().startsWith("[ARGUMENT_REQUIRED]"));
  }

  @Test
  void resolveMissingTitleIsNotFound() {
    QuestMatch.Resolution res = QuestMatch.resolve(List.of(open(1, "做出水瓶")), "挖矿");
    assertFalse(res.matched());
    assertEquals("QUEST_TARGET_NOT_FOUND", res.code());
    assertTrue(res.message().startsWith("[QUEST_TARGET_NOT_FOUND]"));
  }

  @Test
  void resolveMoreThanEightCandidatesTruncates() {
    List<QuestMatch.Entry> entries = new ArrayList<>();
    for (int i = 0; i < 10; i++) entries.add(open(i, "同名任务" + i));
    QuestMatch.Resolution res = QuestMatch.resolve(entries, "同名任务");
    assertEquals("QUEST_AMBIGUOUS", res.code());
    assertTrue(res.message().contains("已截断"));
    assertEquals(10, res.candidates().size());
  }

  @Test
  void ambiguousMessageCarriesMachineReadableCode() {
    QuestMatch.Resolution res = QuestMatch.resolve(List.of(open(1, "A任务"), open(2, "A任务")), "A任务");
    assertEquals("QUEST_AMBIGUOUS", res.code());
    String result = "失败：" + res.message();
    assertTrue(Rules.actionFailed(result));
    assertEquals("QUEST_AMBIGUOUS", ReasonCodes.describe(result).get("reason_code").getAsString());
  }
}
