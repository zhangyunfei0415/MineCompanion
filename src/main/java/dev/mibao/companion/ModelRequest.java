package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** At most one image-to-text retry, preserving tools and the structured observation. */
public final class ModelRequest {
  public record Attempt(int status, long latency, UsageStats.Counts usage) {}

  public record Result(HttpResponse<String> response, boolean usedImage, List<Attempt> attempts) {}

  public static Result exchange(
      HttpClient client, URI uri, String key, JsonObject body, boolean image, Runnable rejected)
      throws Exception {
    return exchange(client, uri, key, body, image, rejected, "CHAT");
  }

  public static Result exchange(
      HttpClient client,
      URI uri,
      String key,
      JsonObject body,
      boolean image,
      Runnable rejected,
      String protocol)
      throws Exception {
    return exchange(client, uri, key, body, image, rejected, protocol, false, p -> {});
  }

  public static Result exchange(
      HttpClient client,
      URI uri,
      String key,
      JsonObject body,
      boolean image,
      Runnable rejected,
      String protocol,
      boolean streaming,
      Consumer<ProviderStream.Preview> preview)
      throws Exception {
    ApiWire.validate(uri);
    var attempts = new ArrayList<Attempt>();
    HttpResponse<String> r = send(client, uri, key, body, protocol, attempts, streaming, preview);
    for (int retry = 0;
        r.statusCode() == 429 && retry < 2 && !RateLimitPolicy.quota(r.body());
        retry++) {
      long delay =
          RateLimitPolicy.delayMillis(
              r.headers().firstValue("Retry-After").orElse(""), retry, System.currentTimeMillis());
      if (delay < 0) break;
      preview.accept(new ProviderStream.Preview("", "模型限流，等待" + (delay / 1000) + "秒后重试；游戏动作不重放"));
      RuntimeStats.add("rate_limit_retries");
      Thread.sleep(delay);
      r = send(client, uri, key, body, protocol, attempts, streaming, preview);
    }
    if (image && VisionPolicy.unsupported(r.statusCode(), r.body())) {
      rejected.run();
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      return new Result(
          send(
              client,
              uri,
              key,
              ProviderWire.textOnly(body),
              protocol,
              attempts,
              streaming,
              preview),
          false,
          List.copyOf(attempts));
    }
    return new Result(r, image, List.copyOf(attempts));
  }

  private static final ExecutorService READERS =
      Executors.newFixedThreadPool(
          2,
          r -> {
            var t = new Thread(r, "companion-api-stream-reader");
            t.setDaemon(true);
            return t;
          });

  private static HttpResponse<String> send(
      HttpClient client,
      URI uri,
      String key,
      JsonObject body,
      String protocol,
      List<Attempt> attempts,
      boolean streaming,
      Consumer<ProviderStream.Preview> preview)
      throws Exception {
    if (streaming) {
      uri = ProviderStream.endpoint(protocol, uri);
      body = ProviderStream.request(protocol, body);
    }
    var builder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(100))
            .header("Content-Type", "application/json");
    ProviderWire.headers(protocol, key).forEach(builder::header);
    if (streaming) builder.header("Accept", "text/event-stream");
    HttpRequest req = builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    long start = System.nanoTime();
    var raw = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
    String payload;
    var reader = READERS.submit(() -> readPayload(raw, protocol, streaming, preview));
    try {
      long remaining = TimeUnit.SECONDS.toNanos(100) - (System.nanoTime() - start);
      if (remaining <= 0) throw new TimeoutException();
      payload = reader.get(remaining, TimeUnit.NANOSECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Exception cause) throw cause;
      throw new IOException("API流读取失败", e.getCause());
    } catch (TimeoutException e) {
      throw new java.net.http.HttpTimeoutException("API流超时");
    } finally {
      reader.cancel(true);
      raw.body().close();
    }
    var response = new TextResponse(raw, payload);
    JsonObject json = new JsonObject();
    try {
      json = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
    } catch (Exception ignored) {
    }
    attempts.add(
        new Attempt(
            response.statusCode(),
            (System.nanoTime() - start) / 1_000_000,
            UsageStats.parse(protocol, json)));
    return response;
  }

  private static String readPayload(
      HttpResponse<InputStream> raw,
      String protocol,
      boolean streaming,
      Consumer<ProviderStream.Preview> preview)
      throws Exception {
    if (streaming
        && raw.statusCode() == 200
        && raw.headers()
            .firstValue("content-type")
            .orElse("")
            .toLowerCase(Locale.ROOT)
            .contains("text/event-stream")) {
      var assembler = new ProviderStream(protocol, preview);
      SseReader.read(raw.body(), assembler::accept);
      return assembler.finish().toString();
    }
    byte[] bytes = raw.body().readNBytes(4_000_001);
    if (bytes.length > 4_000_000) throw new IOException("API回复超过预算");
    if (streaming && raw.statusCode() == 200)
      preview.accept(new ProviderStream.Preview("", "接口返回整段回复 · 未使用流式"));
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private record TextResponse(HttpResponse<InputStream> source, String body)
      implements HttpResponse<String> {
    public int statusCode() {
      return source.statusCode();
    }

    public HttpRequest request() {
      return source.request();
    }

    public Optional<HttpResponse<String>> previousResponse() {
      return Optional.empty();
    }

    public HttpHeaders headers() {
      return source.headers();
    }

    public Optional<javax.net.ssl.SSLSession> sslSession() {
      return source.sslSession();
    }

    public URI uri() {
      return source.uri();
    }

    public HttpClient.Version version() {
      return source.version();
    }
  }
}
