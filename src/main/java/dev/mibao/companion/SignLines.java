package dev.mibao.companion;

import com.google.gson.*;

public final class SignLines {
  public static String[] normalize(JsonArray rows) {
    if (rows == null || rows.size() < 1 || rows.size() > 4)
      throw new IllegalArgumentException("[ARGUMENT_REQUIRED] lines需要1..4行字符串");
    String[] next = {"", "", "", ""};
    for (int i = 0; i < rows.size(); i++) {
      var row = rows.get(i);
      if (!row.isJsonPrimitive() || !row.getAsJsonPrimitive().isString())
        throw new IllegalArgumentException("[ARGUMENT_INVALID] 告示牌每行必须为字符串");
      next[i] = row.getAsString();
      if (next[i].length() > 80 || next[i].chars().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("[ARGUMENT_INVALID] 每行最多80字符且不含控制符");
    }
    return next;
  }

  private SignLines() {}
}
