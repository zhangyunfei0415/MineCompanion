package dev.mibao.companion;

import java.util.*;

/** FTB TextField is a read-only label; TextBox is an editable control. */
public final class QuestWidgetPolicy {
  public static boolean privateInput(List<String> hierarchy) {
    for (String name : hierarchy) {
      String lower = name.toLowerCase(Locale.ROOT);
      if (lower.contains("textbox") || lower.contains("editbox") || lower.contains("password"))
        return true;
    }
    if (hierarchy.contains("dev.ftb.mods.ftblibrary.ui.TextField")) return false;
    return hierarchy.stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).contains("textfield"));
  }

  public static String role(String type) {
    if (type.endsWith(".CollectRewardsButton")) return "claim_all_rewards";
    if (type.endsWith(".TaskButton")) return "task_action";
    if (type.endsWith(".RewardButton")) return "claim_reward";
    if (type.endsWith(".QuestButton")) return "open_quest";
    if (type.endsWith(".ChapterButton")) return "open_chapter";
    return "other_widget";
  }
}
