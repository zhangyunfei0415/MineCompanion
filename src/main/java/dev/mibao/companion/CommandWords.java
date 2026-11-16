package dev.mibao.companion;

import java.util.ArrayList;
import java.util.List;

/** Argument boundaries preserve quoted text and nested SNBT verbatim. */
final class CommandWords {
  record Word(int start, int end, String text) {}

  static List<Word> parse(String command) {
    List<Word> words = new ArrayList<>();
    int start = -1;
    char quote = 0;
    boolean escaped = false;
    var closing = new java.util.ArrayDeque<Character>();
    for (int i = 0; i < command.length(); i++) {
      char c = command.charAt(i);
      if (start < 0 && Character.isWhitespace(c)) continue;
      if (start < 0) start = i;
      if (quote != 0) {
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == quote) quote = 0;
      } else if (c == '"' || c == '\'') quote = c;
      else if (c == '{' || c == '[') closing.push(c == '{' ? '}' : ']');
      else if (c == '}' || c == ']') {
        if (closing.isEmpty() || closing.pop() != c)
          throw new IllegalArgumentException("[INVALID_ARGUMENT] 指令括号不匹配");
      } else if (Character.isWhitespace(c) && closing.isEmpty()) {
        words.add(new Word(start, i, command.substring(start, i)));
        start = -1;
      }
    }
    if (quote != 0 || !closing.isEmpty())
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 指令引号或括号未闭合");
    if (start >= 0) words.add(new Word(start, command.length(), command.substring(start)));
    return words;
  }

  static String replace(String original, List<Word> words, String[] values) {
    var out = new StringBuilder();
    int at = 0;
    for (int i = 0; i < words.size(); i++) {
      var word = words.get(i);
      out.append(original, at, word.start()).append(values[i]);
      at = word.end();
    }
    String result = out.append(original, at, original.length()).toString();
    if (result.length() > 256)
      throw new IllegalArgumentException("[COMMAND_LENGTH] 展开坐标后超过256字符协议上限");
    return result;
  }

  static boolean broadSelector(String command) {
    char quote = 0;
    boolean escaped = false;
    for (int i = 0; i < command.length(); i++) {
      char c = command.charAt(i);
      if (quote != 0) {
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == quote) quote = 0;
      } else if ((c == '"' || c == '\'')
          && (i == 0
              || Character.isWhitespace(command.charAt(i - 1))
              || ":[{,".indexOf(command.charAt(i - 1)) >= 0)) quote = c;
      else if (c == '@'
          && i + 1 < command.length()
          && "aerp".indexOf(command.charAt(i + 1)) >= 0
          && (i == 0 || Character.isWhitespace(command.charAt(i - 1)))
          && (i + 2 == command.length()
              || command.charAt(i + 2) == '['
              || Character.isWhitespace(command.charAt(i + 2)))) return true;
    }
    return false;
  }

  private CommandWords() {}
}
