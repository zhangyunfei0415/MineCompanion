package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

class RateLimitPolicyTest {
  @Test
  void absentAndMalformedHeadersUseBoundedBackoff() {
    assertEquals(5000, RateLimitPolicy.delayMillis("", 0, 0));
    assertEquals(10000, RateLimitPolicy.delayMillis("invalid", 1, 0));
  }

  @Test
  void serverDelayIsRespectedAndLongDelayDoesNotTriggerAnEarlyRetry() {
    assertEquals(2000, RateLimitPolicy.delayMillis("2", 0, 0));
    assertEquals(1000, RateLimitPolicy.delayMillis("-1", 0, 0));
    assertEquals(-1, RateLimitPolicy.delayMillis("120", 0, 0));
  }

  @Test
  void standardHttpDateUsesTheActualWait() {
    long now = Instant.parse("2026-11-10T00:00:00Z").toEpochMilli();
    String header =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(now + 30000), ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME);
    assertEquals(30000, RateLimitPolicy.delayMillis(header, 0, now));
  }

  @Test
  void quotaExhaustionIsDifferentFromTemporaryChannelLimit() {
    assertTrue(RateLimitPolicy.quota("{\"code\":\"insufficient_quota\"}"));
    assertFalse(RateLimitPolicy.quota("Channel rate limit reached"));
  }
}
