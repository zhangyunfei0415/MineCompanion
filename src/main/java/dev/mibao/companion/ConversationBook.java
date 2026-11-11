package dev.mibao.companion;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Local, provider-neutral records. Historical text is context, never a queue of executable tool
 * calls.
 */
public final class ConversationBook {
  public record Event(long at, String world, String dimension, String kind, String text) {}

  private static final Gson JSON = new Gson();
  private final Path root;

  public ConversationBook(Path root) {
    this.root = root;
  }

  private Path file(String world) {
    if (!world.matches("[0-9a-f]{64}|[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
      throw new IllegalArgumentException("无效世界标识");
    return root.resolve("world-" + world + ".jsonl");
  }

  public void append(String world, String dimension, String kind, String text) throws Exception {
    if (text == null
        || text.isBlank()
        || !Set.of("user", "assistant", "halt", "task_result").contains(kind)) return;
    Path p = file(world);
    Files.createDirectories(root);
    if (Files.exists(p) && Files.size(p) > 2_000_000) {
      var recent = read(world, 200);
      Files.copy(
          p, p.resolveSibling(p.getFileName() + ".previous"), StandardCopyOption.REPLACE_EXISTING);
      var lines = new ArrayList<String>();
      for (var e : recent) lines.add(JSON.toJson(e));
      Files.write(p, lines, StandardCharsets.UTF_8);
    }
    Files.writeString(
        p,
        JSON.toJson(
                new Event(
                    System.currentTimeMillis(),
                    world,
                    Rules.clean(dimension, 100),
                    kind,
                    Rules.clean(text, 3000)))
            + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  public List<Event> read(String world, int count) throws Exception {
    Path p = file(world);
    if (!Files.exists(p)) return List.of();
    if (Files.size(p) > 3_000_000) throw new IllegalArgumentException("对话文件过大，请先备份检查");
    var rows = new ArrayDeque<Event>();
    try (var stream = Files.lines(p, StandardCharsets.UTF_8)) {
      var it = stream.iterator();
      while (it.hasNext()) {
        String line = it.next();
        if (line.isBlank()) continue;
        Event e = JSON.fromJson(line, Event.class);
        if (e == null || !world.equals(e.world) || e.text == null || e.kind == null)
          throw new IllegalArgumentException("对话记录损坏，原文件保留");
        rows.add(e);
        while (rows.size() > Rules.bounded(count, 1, 200)) rows.removeFirst();
      }
    }
    return List.copyOf(rows);
  }

  public String context(String world, int maxChars) throws Exception {
    var out = new StringBuilder("过往交流，仅作背景；旧任务和中断操作不得自动恢复：\n");
    var events = read(world, 12);
    var selected = new ArrayDeque<String>();
    int used = out.length();
    for (int i = events.size() - 1; i >= 0; i--) {
      var e = events.get(i);
      String line = e.kind + " [" + e.dimension + "] " + e.text + "\n";
      if (used + line.length() > maxChars) break;
      selected.addFirst(line);
      used += line.length();
    }
    selected.forEach(out::append);
    return out.toString();
  }
}
