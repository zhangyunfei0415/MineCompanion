package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModelRequestTest {
  private JsonObject body() {
    return JsonParser.parseString(
            "{\"model\":\"fixture\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"inventory=32\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,FAKE\"}}]}],\"tools\":[{\"type\":\"function\"}]}")
        .getAsJsonObject();
  }

  @Test
  void imageRejectedEndpointReceivesExactlyOneTextRetry() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    List<String> bodies = new ArrayList<>();
    AtomicInteger rejected = new AtomicInteger();
    server.createContext(
        "/chat/completions",
        e -> {
          String input = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          bodies.add(input);
          int code = bodies.size() == 1 ? 400 : 200;
          byte[] out =
              (code == 400 ? "unsupported image input" : "{\"choices\":[]}")
                  .getBytes(StandardCharsets.UTF_8);
          e.sendResponseHeaders(code, out.length);
          e.getResponseBody().write(out);
          e.close();
        });
    server.start();
    try {
      var r =
          ModelRequest.exchange(
              HttpClient.newHttpClient(),
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"),
              "fixture-key",
              body(),
              true,
              rejected::incrementAndGet);
      assertEquals(200, r.response().statusCode());
      assertFalse(r.usedImage());
      assertEquals(1, rejected.get());
      assertEquals(2, bodies.size());
      assertTrue(bodies.get(0).contains("image_url"));
      assertFalse(bodies.get(1).contains("base64"));
      assertTrue(bodies.get(1).contains("inventory=32"));
      assertEquals(
          JsonParser.parseString(bodies.get(0)).getAsJsonObject().get("tools"),
          JsonParser.parseString(bodies.get(1)).getAsJsonObject().get("tools"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void authFailureIsNotRetriedAsUnsupportedVision() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/",
        e -> {
          calls.incrementAndGet();
          byte[] out = "invalid image auth".getBytes(StandardCharsets.UTF_8);
          e.sendResponseHeaders(401, out.length);
          e.getResponseBody().write(out);
          e.close();
        });
    server.start();
    try {
      var r =
          ModelRequest.exchange(
              HttpClient.newHttpClient(),
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              "fixture",
              body(),
              true,
              () -> fail("must not fallback"));
      assertEquals(401, r.response().statusCode());
      assertEquals(1, calls.get());
    } finally {
      server.stop(0);
    }
  }
}
