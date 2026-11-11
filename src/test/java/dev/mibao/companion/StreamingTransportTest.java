package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class StreamingTransportTest {
  private static final String FIRST =
      "data: {\"choices\":[{\"delta\":{\"content\":\"正在检查\"}}]}\n\n";
  private static final String LAST =
      "data:"
          + " {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":3}}\n\n"
          + "data: [DONE]\n\n";

  private static URI uri(HttpServer s) {
    return URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/");
  }

  @Test
  void previewArrivesBeforeServerFinishesAndUsageIsOneAttempt() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var preview = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var seen = new AtomicReference<String>();
    server.createContext(
        "/",
        e -> {
          seen.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          e.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
          e.sendResponseHeaders(200, 0);
          try (var out = e.getResponseBody()) {
            out.write(FIRST.getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
              release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            out.write(LAST.getBytes(StandardCharsets.UTF_8));
          } finally {
            e.close();
          }
        });
    server.start();
    var executor = Executors.newSingleThreadExecutor();
    try {
      var pending =
          executor.submit(
              () ->
                  ModelRequest.exchange(
                      HttpClient.newHttpClient(),
                      uri(server),
                      "fixture-key",
                      new JsonObject(),
                      false,
                      () -> {},
                      "CHAT",
                      true,
                      p -> preview.countDown()));
      assertTrue(
          preview.await(4, TimeUnit.SECONDS),
          "must receive text while server still holds final frame");
      assertFalse(pending.isDone());
      release.countDown();
      var result = pending.get(5, TimeUnit.SECONDS);
      assertEquals(1, result.attempts().size());
      assertEquals(20L, result.attempts().get(0).usage().input());
      assertTrue(JsonParser.parseString(seen.get()).getAsJsonObject().get("stream").getAsBoolean());
      assertEquals(
          "正在检查",
          ProviderWire.response(
                  "CHAT", JsonParser.parseString(result.response().body()).getAsJsonObject())
              .get("content")
              .getAsString());
    } finally {
      release.countDown();
      executor.shutdownNow();
      server.stop(0);
    }
  }

  @Test
  void streamErrorDoesNotRetryOrReturnTools() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var requests = new AtomicInteger();
    server.createContext(
        "/",
        e -> {
          requests.incrementAndGet();
          e.getResponseHeaders().add("Content-Type", "text/event-stream");
          e.sendResponseHeaders(200, 0);
          e.getResponseBody()
              .write(
                  (FIRST + "event: error\ndata: {\"error\":{\"type\":\"overloaded\"}}\n\n")
                      .getBytes(StandardCharsets.UTF_8));
          e.close();
        });
    server.start();
    try {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ModelRequest.exchange(
                  HttpClient.newHttpClient(),
                  uri(server),
                  "fixture-key",
                  new JsonObject(),
                  false,
                  () -> {},
                  "CHAT",
                  true,
                  p -> {}));
      assertEquals(1, requests.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void bufferedGatewayIsReportedHonestly() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        e -> {
          byte[] b =
              "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"整段回复\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, b.length);
          e.getResponseBody().write(b);
          e.close();
        });
    server.start();
    try {
      var stage = new AtomicReference<String>();
      var result =
          ModelRequest.exchange(
              HttpClient.newHttpClient(),
              uri(server),
              "fixture-key",
              new JsonObject(),
              false,
              () -> {},
              "CHAT",
              true,
              p -> stage.set(p.stage()));
      assertTrue(stage.get().contains("未使用流式"));
      assertEquals(200, result.response().statusCode());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void cancellationClosesBlockedStreamWithoutAnotherRequest() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var preview = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var workerEnded = new CountDownLatch(1);
    var requests = new AtomicInteger();
    server.createContext(
        "/",
        e -> {
          requests.incrementAndGet();
          e.getResponseHeaders().add("Content-Type", "text/event-stream");
          e.sendResponseHeaders(200, 0);
          try {
            e.getResponseBody().write(FIRST.getBytes(StandardCharsets.UTF_8));
            e.getResponseBody().flush();
            release.await(5, TimeUnit.SECONDS);
          } catch (Exception ignored) {
          } finally {
            e.close();
          }
        });
    server.start();
    var executor = Executors.newSingleThreadExecutor();
    try {
      var f =
          executor.submit(
              () -> {
                try {
                  ModelRequest.exchange(
                      HttpClient.newHttpClient(),
                      uri(server),
                      "fixture-key",
                      new JsonObject(),
                      false,
                      () -> {},
                      "CHAT",
                      true,
                      p -> preview.countDown());
                } catch (Exception expected) {
                } finally {
                  workerEnded.countDown();
                }
              });
      assertTrue(preview.await(4, TimeUnit.SECONDS));
      f.cancel(true);
      assertTrue(workerEnded.await(3, TimeUnit.SECONDS), "cancel must close blocked body reader");
      assertEquals(1, requests.get());
    } finally {
      release.countDown();
      executor.shutdownNow();
      server.stop(0);
    }
  }
}
