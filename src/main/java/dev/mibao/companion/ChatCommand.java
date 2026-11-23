package dev.mibao.companion;

import java.util.Locale;
import java.util.regex.*;

/** Only extraction/normalization. Identity must come from the actual chat event. */
public final class ChatCommand {
  enum FollowAction { START, STOP, STATUS, PORTAL }

  record Follow(FollowAction action, String argument) {}

  enum DutyAction {
    STANDBY,
    BASE_STEWARD,
    ESCORT_GUARD,
    EXPEDITION_GATHER,
    GUIDE_HOME,
    PAUSE,
    RESUME,
    STATUS
  }

  record Duty(DutyAction action, String argument) {
    Duty(DutyAction action) {
      this(action, "");
    }
  }

  private static final Pattern NAMED_FOLLOW =
      Pattern.compile("跟随(?:玩家)?\\s+([A-Za-z0-9_]{1,16})", Pattern.UNICODE_CHARACTER_CLASS);
  private static final Pattern GUIDE_TO =
      Pattern.compile("(?:带我去|带路去|领我去|带我回)\\s*(.+)");

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

  static Duty duty(String text) {
    if (text == null || text.isBlank()) return null;
    String command = normalize(text);
    return switch (command) {
      case "纯待命", "切换待命", "切回待命", "待命模式" -> new Duty(DutyAction.STANDBY);
      case "驻守基地", "基地值守", "看家", "驻守模式" -> new Duty(DutyAction.BASE_STEWARD);
      case "跟随守护", "跟着并保护我", "守护跟随", "守护我", "护卫模式" ->
          new Duty(DutyAction.ESCORT_GUARD);
      case "外出探索", "外出采集", "外出探索采集", "探索搜刮" ->
          new Duty(DutyAction.EXPEDITION_GATHER);
      case "带我回家", "领路回家", "带路回家", "带我回基地", "带我回入口", "送我回家" ->
          new Duty(DutyAction.GUIDE_HOME, command.contains("入口") ? "nearest_entry" : "nearest_safe");
      case "暂停值守", "停止值守" -> new Duty(DutyAction.PAUSE);
      case "恢复值守", "启动值守", "开始值守" -> new Duty(DutyAction.RESUME);
      case "值守状态", "陪同状态" -> new Duty(DutyAction.STATUS);
      default -> {
        var guide = GUIDE_TO.matcher(command);
        yield guide.matches()
            ? new Duty(DutyAction.GUIDE_HOME, guide.group(1).trim())
            : null;
      }
    };
  }

  private ChatCommand() {}
}
