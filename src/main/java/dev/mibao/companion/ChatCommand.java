package dev.mibao.companion;

import java.util.Locale;
import java.util.regex.*;

/** Only extraction/normalization. Identity must come from the actual chat event. */
public final class ChatCommand {
  public static String extract(String message, String name) {
    var match =
        Pattern.compile(
                "[@＠]" + Pattern.quote(name) + "(?![A-Za-z0-9_\\p{IsHan}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
            .matcher(message);
    if (!match.find()) return "";
    return normalize(message.substring(match.end()));
  }

  public static String normalize(String text) {
    return text.strip().replaceFirst("^[：:,，\\s]+", "").replaceAll("[。！!？?]+$", "").trim();
  }

  public static String control(String text) {
    return switch (text.toLowerCase(Locale.ROOT)) {
      case "停止", "stop", "急停" -> "停止";
      case "暂停", "pause" -> "暂停";
      case "继续", "resume" -> "继续";
      case "进度", "任务列表", "进度怎么样" -> "进度";
      default -> text;
    };
  }

  private ChatCommand() {}
}
