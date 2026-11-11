package dev.mibao.companion;

import java.util.*;
import java.util.regex.*;

/** Single-line parameters cannot introduce new commands or control characters. */
public final class CommandTemplate {
  private static final Pattern PARAM = Pattern.compile("\\$\\{([a-zA-Z][a-zA-Z0-9_]{0,31})}");

  public static String expand(String template, Map<String, String> values) {
    if (template == null
        || template.isBlank()
        || template.length() > 1024
        || template.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("[INVALID_TEMPLATE] 单行模板最长1024字符");
    var m = PARAM.matcher(template);
    var out = new StringBuffer();
    while (m.find()) {
      String v = values.get(m.group(1));
      if (v == null
          || v.isBlank()
          || v.length() > 128
          || v.chars().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("[MISSING_BINDING] 缺少/无效参数：" + m.group(1));
      m.appendReplacement(out, Matcher.quoteReplacement(v));
    }
    m.appendTail(out);
    if (out.indexOf("${") >= 0 || out.length() > 1024)
      throw new IllegalArgumentException("[INVALID_TEMPLATE] 未解析的模板参数或长度超限");
    return out.toString();
  }

  private CommandTemplate() {}
}
