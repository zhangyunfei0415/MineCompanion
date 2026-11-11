package dev.mibao.companion;

/** Zero means explicitly held until stopped; positive values are renewable idle timeouts. */
public final class HarnessLeasePolicy {
  public static final int MAX_SECONDS = 30 * 24 * 3600;

  public static int normalize(int seconds) {
    return seconds == 0 ? 0 : Math.max(60, Math.min(MAX_SECONDS, seconds));
  }

  public static int customMinutes(String value) {
    try {
      int minutes = Integer.parseInt(value.trim());
      if (minutes < 1 || minutes > 43200) throw new NumberFormatException();
      return minutes * 60;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("自定义租约须为1..43200分钟（最多30天）；无限期请选择手动停止");
    }
  }

  public static String label(int seconds) {
    int n = normalize(seconds);
    return n == 0
        ? "直到手动停止"
        : n % 3600 == 0 ? n / 3600 + "小时" : n % 60 == 0 ? n / 60 + "分钟" : n + "秒";
  }
}
