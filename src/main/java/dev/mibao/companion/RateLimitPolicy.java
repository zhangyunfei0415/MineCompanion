package dev.mibao.companion;

import java.time.*;
import java.time.format.DateTimeFormatter;

public final class RateLimitPolicy {
  public static long delayMillis(String header, int attempt, long now) {
    long millis = 5000L * (1L << Math.min(3, Math.max(0, attempt)));
    if (header != null && !header.isBlank()) {
      try {
        millis = Math.multiplyExact(Long.parseLong(header.strip()), 1000L);
      } catch (RuntimeException e) {
        try {
          millis =
              ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                      .toInstant()
                      .toEpochMilli()
                  - now;
        } catch (RuntimeException ignored) {
        }
      }
    }
    // Do not violate a long server backoff by retrying earlier; return -1 and surface it instead.
    return millis > 60000 ? -1 : Math.max(1000, millis);
  }

  public static boolean quota(String body) {
    String text = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
    return text.contains("insufficient_quota")
        || text.contains("freeusagelimit")
        || text.contains("quota exceeded")
        || text.contains("余额不足");
  }

  private RateLimitPolicy() {}
}
