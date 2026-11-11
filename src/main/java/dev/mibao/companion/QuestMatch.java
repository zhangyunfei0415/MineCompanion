package dev.mibao.companion;

import java.util.*;

/**
 * Pure one-step resolver for FTB quest titles. {@code quest_book_open} and {@code quest_book_act}
 * may receive only a human query, so the nearest unique visible quest button is selected. An
 * ambiguous or empty query returns a candidate list instead of picking arbitrarily. No game runtime
 * is required, so the matching rules stay testable offline.
 */
public final class QuestMatch {
  /** One observed widget row reduced to what matching needs. */
  public record Entry(int widget, String label, boolean semantic) {}

  /** Resolution: {@code widget >= 0} on a unique match, otherwise {@code code}/{@code message}. */
  public record Resolution(int widget, String code, String message, List<Entry> candidates) {
    public boolean matched() {
      return widget >= 0;
    }
  }

  private QuestMatch() {}

  /** True when {@code title} equals the query or uniquely contains it; empty query matches. */
  public static boolean matches(String title, String query) {
    if (query == null || query.isEmpty()) return true;
    if (title == null || title.isEmpty()) return false;
    String q = query.trim();
    return title.equals(q)
        || title.equalsIgnoreCase(q)
        || title.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT));
  }

  /**
   * Resolve a query to one visible semantic quest button: exact match, then case-insensitive exact,
   * then unique substring. Ambiguity never picks a winner.
   */
  public static Resolution resolve(List<Entry> entries, String query) {
    String q = query == null ? "" : query.trim();
    if (q.isEmpty())
      return fail(
          "ARGUMENT_REQUIRED",
          "[ARGUMENT_REQUIRED] 需要 query：给出任务/章节标题（先精确、再唯一包含匹配），或先用 quest_book_state 取 widget 编号",
          List.of());
    List<Entry> open = entries.stream().filter(Entry::semantic).toList();
    List<Entry> exact = open.stream().filter(e -> e.label().equals(q)).toList();
    if (exact.size() == 1) return match(exact.get(0));
    if (exact.size() > 1) return ambiguous(exact, "精确匹配的可见任务按钮有 " + exact.size() + " 个");
    List<Entry> ci = open.stream().filter(e -> e.label().equalsIgnoreCase(q)).toList();
    if (ci.size() == 1) return match(ci.get(0));
    if (ci.size() > 1) return ambiguous(ci, "忽略大小写匹配的可见任务按钮有 " + ci.size() + " 个");
    String lower = q.toLowerCase(Locale.ROOT);
    List<Entry> contains =
        open.stream().filter(e -> e.label().toLowerCase(Locale.ROOT).contains(lower)).toList();
    if (contains.size() == 1) return match(contains.get(0));
    if (contains.isEmpty())
      return fail(
          "QUEST_TARGET_NOT_FOUND",
          "[QUEST_TARGET_NOT_FOUND] 当前任务书可见任务按钮中没有匹配《"
              + q
              + "》的标题；先 quest_book_state 查看可见控件，必要时滚动任务树后重试",
          List.of());
    return ambiguous(contains, "包含匹配的可见任务按钮有 " + contains.size() + " 个");
  }

  private static Resolution match(Entry entry) {
    return new Resolution(
        entry.widget(),
        "",
        "匹配 widget=" + entry.widget() + "《" + entry.label() + "》",
        List.of(entry));
  }

  private static Resolution ambiguous(List<Entry> candidates, String why) {
    var text = new StringBuilder("[QUEST_AMBIGUOUS] ").append(why).append("；候选：");
    int shown = Math.min(8, candidates.size());
    for (int i = 0; i < shown; i++) {
      Entry e = candidates.get(i);
      if (i > 0) text.append("，");
      text.append("widget=").append(e.widget()).append("《").append(e.label()).append("》");
    }
    if (candidates.size() > shown) text.append("…（共").append(candidates.size()).append("个，已截断）");
    text.append("；请改用完整标题再试，或先 quest_book_state 后按 widget 编号操作");
    return new Resolution(-1, "QUEST_AMBIGUOUS", text.toString(), List.copyOf(candidates));
  }

  private static Resolution fail(String code, String message, List<Entry> candidates) {
    return new Resolution(-1, code, message, candidates);
  }
}
