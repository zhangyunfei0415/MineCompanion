package dev.mibao.companion;

import java.util.List;
import java.util.Locale;

/** Deterministic choice among multiple GUI matches; never silently picks the first one. */
public final class GuiChoice {
  public static final String AMBIGUOUS = "GUI_MULTIPLE_MATCHES";

  public record Slot(int slot, String id, int count) {}

  private GuiChoice() {}

  public static boolean specified(int index, String nth) {
    return index >= 0 || (nth != null && !nth.isBlank());
  }

  /**
   * 0-based choice among the matches, or -1 when the caller must refuse and offer the candidates.
   */
  public static int choose(int matches, int index, String nth) {
    if (matches <= 0) return -1;
    if (index >= 0) {
      if (index >= matches)
        throw new IllegalArgumentException(
            "[ARGUMENT_INVALID] index 超出范围：0.." + (matches - 1) + "，实际 " + index);
      return index;
    }
    if (nth == null || nth.isBlank()) return matches == 1 ? 0 : -1;
    String value = nth.trim().toLowerCase(Locale.ROOT);
    if (value.equals("first")) return 0;
    if (value.equals("last")) return matches - 1;
    throw new IllegalArgumentException("[ARGUMENT_INVALID] nth 须为 first 或 last");
  }

  public static String candidates(int matches, String subject, List<Slot> slots) {
    StringBuilder b =
        new StringBuilder("[")
            .append(AMBIGUOUS)
            .append("] ")
            .append(subject)
            .append("匹配 ")
            .append(matches)
            .append(" 个；候选 ");
    int n = 0;
    for (Slot s : slots) {
      if (n == 8) break;
      if (n++ > 0) b.append("、");
      b.append("槽").append(s.slot()).append(" ").append(s.id()).append("×").append(s.count());
    }
    if (matches > 8) b.append("、另有 ").append(matches - 8).append(" 个");
    b.append("；用 index=0..").append(matches - 1).append(" 或 nth=first/last 明确目标，不自动选第一个。");
    return b.toString();
  }
}
