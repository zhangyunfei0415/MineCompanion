package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationBookTest {
  @TempDir Path folder;
  private final String a = "a".repeat(64), b = "b".repeat(64);

  @Test
  void rebootLoadsHistoryButWorldsNeverShareIt() throws Exception {
    var book = new ConversationBook(folder);
    book.append(a, "minecraft:overworld", "user", "种些小麦");
    book.append(b, "minecraft:overworld", "user", "另一个世界");
    var reopened = new ConversationBook(folder);
    assertEquals("种些小麦", reopened.read(a, 10).get(0).text());
    assertEquals(1, reopened.read(b, 10).size());
    assertEquals(1, reopened.read(a, 10).size());
  }

  @Test
  void haltsAreContextAndNeverExecutableCalls() throws Exception {
    var book = new ConversationBook(folder);
    book.append(a, "minecraft:the_nether", "halt", "停止，投料结果未知");
    assertTrue(book.context(a, 1000).contains("旧任务和中断操作不得自动恢复"));
    assertEquals("halt", book.read(a, 10).get(0).kind());
    book.append(a, "", "tool_calls", "not an allowed event");
    assertEquals(1, book.read(a, 10).size());
  }

  @Test
  void contextKeepsNewestWholeMessagesInChronologicalOrder() throws Exception {
    var book = new ConversationBook(folder);
    for (int i = 0; i < 15; i++) book.append(a, "world", "user", i + "x".repeat(100));
    String context = book.context(a, 320);
    assertTrue(context.contains("14xxx"));
    assertTrue(context.length() <= 320);
    assertFalse(context.contains("user [world] 3xxx"));
  }

  @Test
  void rejectsPathTraversalAndRetainsMalformedFile() throws Exception {
    var book = new ConversationBook(folder);
    assertThrows(IllegalArgumentException.class, () -> book.append("../other", "", "user", "text"));
    Path p = folder.resolve("world-" + a + ".jsonl");
    Files.writeString(p, "{broken\n");
    assertThrows(Exception.class, () -> book.read(a, 10));
    assertEquals("{broken\n", Files.readString(p));
  }

  @Test
  void unicodeMultilineTextRemainsOneJsonRecordAndReadIsBounded() throws Exception {
    var book = new ConversationBook(folder);
    book.append(a, "world", "assistant", "你好\n第二行");
    for (int i = 0; i < 30; i++) book.append(a, "world", "user", "消息" + i);
    assertEquals(4, book.read(a, 4).size());
    assertEquals("消息29", book.read(a, 4).get(3).text());
    assertEquals(31, Files.readAllLines(folder.resolve("world-" + a + ".jsonl")).size());
  }

  @Test
  void actualUuidWorldScopesPersistAcrossReopen() throws Exception {
    String scope = WorldScope.key("singleplayer-path:test");
    new ConversationBook(folder).append(scope, "world", "user", "真实标识");
    assertEquals("真实标识", new ConversationBook(folder).read(scope, 10).get(0).text());
  }
}
