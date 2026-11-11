package dev.mibao.companion;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Local JSONL with a hard 32 MiB budget. Callers provide observations, never settings/HTTP headers.
 */
public final class HarnessRecorder implements AutoCloseable {
  private final Path file;
  private long bytes;
  private int rows;

  public HarnessRecorder(Path file) throws Exception {
    this.file = file;
    Files.createDirectories(file.getParent());
    Files.createFile(file);
  }

  public void add(JsonObject event) throws Exception {
    byte[] line = (event.toString() + "\n").getBytes(StandardCharsets.UTF_8);
    if (rows >= 8192 || bytes + line.length > 32 * 1024 * 1024)
      throw new IllegalStateException("记录达到8192事件/32MiB上限，已停止记录");
    Files.write(file, line, StandardOpenOption.APPEND);
    bytes += line.length;
    rows++;
  }

  public Path file() {
    return file;
  }

  public int rows() {
    return rows;
  }

  public void close() {}
}
