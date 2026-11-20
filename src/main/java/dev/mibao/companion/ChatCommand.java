package dev.mibao.companion;

import java.util.Locale;
import java.util.regex.*;

/** Only extraction/normalization. Identity must come from the actual chat event. */
public final class ChatCommand {
  enum FollowAction { START, STOP, STATUS, PORTAL }

  record Follow(FollowAction action, String argument) {}

  private static final Pattern NAMED_FOLLOW =
      Pattern.compile("跟随(?:玩家)?\\s+([A-Za-z0-9_]{1,16})", Pattern.UNICODE_CHARACTER_CLASS);

  public static String extract(String message, String name) {
    var match = mention(message, name);
    if (!match.find()) return "";
    return normalize(message.substring(match.end()));
  }

  static String followMention(String message, String name) {
    String body = message.stripLeading();
    var match = mention(body, name);
    if (!match.lookingAt()) return "";
    String command = normalize(body.substring(match.end()));
    return follow(command) == null ? "" : command;
  }

  private static Matcher mention(String message, String name) {
    return Pattern.compile(
            "[@＠]" + Pattern.quote(name) + "(?![A-Za-z0-9_\\p{IsHan}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
        .matcher(message);
  }

  static Follow follow(String text) {
    String command = normalize(text);
    return switch (command) {
      case "跟我", "跟我来", "跟着我", "跟随我", "跟紧我", "开始跟随" ->
          new Follow(FollowAction.START, "");
      case "停止跟随", "取消跟随" -> new Follow(FollowAction.STOP, "");
      case "跟随状态" -> new Follow(FollowAction.STATUS, "");
      case "进门", "跟我进门", "进传送门", "进入传送门" -> new Follow(FollowAction.PORTAL, "");
      case "进下界门", "进入下界传送门" -> new Follow(FollowAction.PORTAL, "nether");
      case "进末地门", "进入末地传送门" -> new Follow(FollowAction.PORTAL, "end");
      default -> {
        var named = NAMED_FOLLOW.matcher(command);
        yield named.matches() ? new Follow(FollowAction.START, named.group(1)) : null;
      }
    };
  }

  static boolean incompleteFollow(String text) {
    return normalize(text).matches("(?:(?:跟着|跟紧|跟随)(?:我)?(?:吧|一下)?|跟随玩家|开始跟随玩家)");
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
