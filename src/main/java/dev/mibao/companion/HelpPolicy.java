package dev.mibao.companion;

/** Muting help changes presentation, never permissions, receipts or stop controls. */
public final class HelpPolicy {
  public static boolean isHelp(String text) {
    if (text == null || text.contains("审批") || text.contains("授权") || text.contains("权限"))
      return false;
    return text.contains("求助")
        || text.contains("请协助")
        || text.contains("请补给")
        || text.startsWith("恢复待处理：");
  }

  public static boolean suppress(boolean notifications, boolean reply, String text) {
    return !notifications && !reply && isHelp(text);
  }
}
