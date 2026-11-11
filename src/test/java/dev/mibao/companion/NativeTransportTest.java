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

class NativeTransportTest {
  @Test
  void everyNativeProtocolUsesItsOwnHeadersAndOneImageFallbackOnSameEndpoint() throws Exception {
    for (String protocol : List.of("RESPONSES", "ANTHROPIC", "GEMINI")) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      var requests = new ArrayList<String>();
      var headerValues = new ArrayList<String>();
      var count = new AtomicInteger();
      server.createContext(
          "/",
          e -> {
            requests.add(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String auth =
                protocol.equals("RESPONSES")
                    ? "Authorization"
                    : protocol.equals("ANTHROPIC") ? "x-api-key" : "x-goog-api-key";
            headerValues.add(e.getRequestHeaders().getFirst(auth));
            boolean first = count.getAndIncrement() == 0;
            String body =
                first
                    ? "{\"error\":\"image unsupported\"}"
                    : "{\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(first ? 400 : 200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      server.start();
      try {
        var canonical =
            JsonParser.parseString(
                    "{\"model\":\"fixture\",\"max_tokens\":100,\"tools\":[],\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"inventory32\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,fixture\"}}]}]}")
                .getAsJsonObject();
        var r =
            ModelRequest.exchange(
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                "test-key",
                ProviderWire.request(protocol, canonical),
                true,
                () -> {},
                protocol);
        assertEquals(2, r.attempts().size());
        assertEquals(400, r.attempts().get(0).status());
        assertEquals(200, r.attempts().get(1).status());
        assertFalse(r.usedImage());
        assertTrue(requests.get(0).contains("base64") || requests.get(0).contains("inlineData"));
        assertFalse(requests.get(1).contains("inlineData"));
        assertFalse(requests.get(1).contains("input_image"));
        assertFalse(requests.get(1).contains("data:image"));
        assertTrue(requests.get(1).contains("inventory32"));
        assertTrue(
            headerValues.stream()
                .allMatch(
                    s -> s.equals(protocol.equals("RESPONSES") ? "Bearer test-key" : "test-key")));
      } finally {
        server.stop(0);
      }
    }
  }
}
