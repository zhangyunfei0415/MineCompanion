package dev.mibao.companion;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Stateless MCP HTTP transport, with no Minecraft dependency so real HTTP can be tested offline.
 */
public final class HarnessHttp implements AutoCloseable {
  private final HttpServer server;
  private final ExecutorService pool;
  private final ExecutorService responsePool;
  private final Supplier<String> token;
  private final Function<JsonObject, CompletableFuture<JsonObject>> dispatch;

  public HarnessHttp(
      int port,
      Supplier<String> token,
      Function<JsonObject, CompletableFuture<JsonObject>> dispatch)
      throws Exception {
    this.token = token;
    this.dispatch = dispatch;
    server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
    pool =
        new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16),
            r -> {
              var t = new Thread(r, "Companion-Harness-HTTP");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    responsePool =
        new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),
            r -> {
              var t = new Thread(r, "Companion-Harness-AsyncReply");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    server.createContext("/mcp", this::handle);
    server.setExecutor(pool);
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private void handle(HttpExchange ex) {
    try {
      String auth = ex.getRequestHeaders().getFirst("Authorization"),
          host = ex.getRequestHeaders().getFirst("Host");
      if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()
          || ex.getRequestHeaders().containsKey("Origin")
          || host == null
          || !(host.equals("127.0.0.1:" + port()) || host.equals("localhost:" + port()))) {
        empty(ex, 403);
        return;
      }
      if (auth == null
          || !MessageDigest.isEqual(
              auth.getBytes(StandardCharsets.UTF_8),
              ("Bearer " + token.get()).getBytes(StandardCharsets.UTF_8))) {
        empty(ex, 401);
        return;
      }
      if (!ex.getRequestURI().getPath().equals("/mcp")) {
        empty(ex, 404);
        return;
      }
      if (!ex.getRequestMethod().equals("POST")) {
        empty(ex, 405);
        return;
      }
      String content = ex.getRequestHeaders().getFirst("Content-Type");
      if (content == null
          || !content.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
        empty(ex, 415);
        return;
      }
      byte[] bytes = ex.getRequestBody().readNBytes(32769);
      if (bytes.length > 32768) {
        empty(ex, 413);
        return;
      }
      JsonObject req;
      try {
        req = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
      } catch (Exception malformed) {
        empty(ex, 400);
        return;
      }
      if (!req.has("method")
          || !req.has("jsonrpc")
          || !req.get("jsonrpc").getAsString().equals("2.0")) {
        empty(ex, 400);
        return;
      }
      if (!req.has("id")) {
        empty(ex, 202);
        return;
      }
      boolean asyncWait = isAsyncWaitCall(req);
      if (asyncWait) {
        final JsonElement reqId = req.get("id");
        CompletableFuture<JsonObject> future;
        try {
          future = dispatch.apply(req);
        } catch (Exception failed) {
          finishAsync(ex, reqId, null, failed);
          return;
        }
        future.whenCompleteAsync(
            (res, err) -> finishAsync(ex, reqId, res, err), responsePool);
        return;
      }
      var response = new JsonObject();
      response.addProperty("jsonrpc", "2.0");
      response.add("id", req.get("id"));
      var future = dispatch.apply(req);
      try {
        response.add("result", future.get(8, TimeUnit.SECONDS));
      } catch (TimeoutException timeout) {
        future.cancel(false);
        var err = new JsonObject();
        err.addProperty("code", -32001);
        err.addProperty("message", "主线程响应超时；动作可能已接受，请使用相同request_id查回执，禁止换ID重试");
        response.add("error", err);
      } catch (Exception failed) {
        var err = new JsonObject();
        err.addProperty("code", -32602);
        err.addProperty(
            "message",
            failed.getCause() == null ? failed.getMessage() : failed.getCause().getMessage());
        response.add("error", err);
      }
      writeJson(ex, response);
    } catch (Exception failed) {
      try {
        empty(ex, 500);
      } catch (Exception ignored) {
      }
    }
  }

  private static boolean isAsyncWaitCall(JsonObject req) {
    if (!"tools/call".equals(req.get("method").getAsString())) return false;
    if (!req.has("params") || !req.get("params").isJsonObject()) return false;
    JsonObject params = req.getAsJsonObject("params");
    return params.has("name")
        && params.get("name").isJsonPrimitive()
        && "wait_events".equals(params.get("name").getAsString());
  }

  private static void finishAsync(
      HttpExchange ex, JsonElement reqId, JsonObject res, Throwable err) {
    try {
      var response = new JsonObject();
      response.addProperty("jsonrpc", "2.0");
      response.add("id", reqId);
      if (err != null) {
        Throwable root = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        var errObj = new JsonObject();
        errObj.addProperty("code", -32602);
        errObj.addProperty("message", String.valueOf(root.getMessage()));
        response.add("error", errObj);
      } else {
        response.add("result", res == null ? new JsonObject() : res);
      }
      writeJson(ex, response);
    } catch (Exception ignored) {
      try {
        ex.close();
      } catch (Exception ignored2) {
      }
    }
  }

  private static void writeJson(HttpExchange ex, JsonObject response) throws Exception {
    byte[] out = response.toString().getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.sendResponseHeaders(200, out.length);
    ex.getResponseBody().write(out);
    ex.close();
  }

  private static void empty(HttpExchange ex, int status) throws Exception {
    ex.sendResponseHeaders(status, -1);
    ex.close();
  }

  @Override
  public void close() {
    server.stop(0);
    pool.shutdownNow();
    responsePool.shutdownNow();
  }
}
