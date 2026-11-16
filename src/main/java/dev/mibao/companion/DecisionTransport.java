package dev.mibao.companion;

import java.net.http.HttpClient;
import java.time.Duration;

final class DecisionTransport {
  static HttpClient create() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }
}
