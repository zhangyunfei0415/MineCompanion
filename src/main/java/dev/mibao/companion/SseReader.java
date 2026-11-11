package dev.mibao.companion;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** UTF-8 decoder owns byte boundaries; SSE owns lines and blank-line dispatch. */
public final class SseReader {
  public record Event(String type, String data) {}

  public static void read(InputStream input, Consumer<Event> consumer) throws IOException {
    var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    String type = "message";
    var data = new StringBuilder();
    int total = 0;
    boolean first = true;
    String line;
    while ((line = reader.readLine()) != null) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("流式请求已取消");
      if (first) {
        first = false;
        if (line.startsWith("\ufeff")) line = line.substring(1);
      }
      total += line.length();
      if (total > 4_000_000 || line.length() > 1_000_000) throw new IOException("流式回复超过预算");
      if (line.isEmpty()) {
        if (!data.isEmpty()) {
          consumer.accept(new Event(type, data.substring(0, data.length() - 1)));
          data.setLength(0);
        }
        type = "message";
        continue;
      }
      if (line.startsWith(":")) continue;
      int colon = line.indexOf(':');
      String field = colon < 0 ? line : line.substring(0, colon),
          value = colon < 0 ? "" : line.substring(colon + 1);
      if (value.startsWith(" ")) value = value.substring(1);
      if (field.equals("data")) data.append(value).append('\n');
      else if (field.equals("event")) type = value;
    }
    // EOF is not an SSE event delimiter. A truncated final frame is never dispatched.
  }

  private SseReader() {}
}
