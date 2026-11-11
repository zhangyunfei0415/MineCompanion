package dev.mibao.companion;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Portable experience contains no map bindings; world evidence remains in Store.Data. */
public final class Learning {
  public static class ContextOutcome {
    public int verified, partial, failed;
    public String last = "", build = "", condition = "";
  }

  public static class Outcome {
    public int verified, partial, failed, stepsCompleted;
    public String last = "";
    public Map<String, ContextOutcome> contexts = new LinkedHashMap<>();
  }

  public static class InterfaceNote {
    public String confirmed = "";
    public long updated;
  }

  public static class Book {
    public int schema = 1;
    public Map<String, Outcome> outcomes = new LinkedHashMap<>();
    public Map<String, InterfaceNote> interfaces = new LinkedHashMap<>();
  }

  public static Book book = new Book();
  private static boolean readable = true;

  public static void ensureWritable() {
    if (!readable) throw new IllegalStateException("原经验文件无法读取；请先保留并修复文件，本次不会覆盖");
  }

  public static void validate(Book b) {
    if (b == null
        || b.schema != 1
        || b.outcomes == null
        || b.interfaces == null
        || b.outcomes.size() > 512
        || b.interfaces.size() > 256) throw new IllegalArgumentException("经验格式/容量不正确");
    for (var e : b.outcomes.entrySet())
      if (e.getKey().length() > 256
          || e.getValue() == null
          || e.getValue().verified < 0
          || e.getValue().failed < 0
          || e.getValue().partial < 0
          || e.getValue().stepsCompleted < 0) throw new IllegalArgumentException("经验计数无效");
    for (var o : b.outcomes.values()) {
      if (o.contexts == null) o.contexts = new LinkedHashMap<>();
      if (o.contexts.size() > 8) throw new IllegalArgumentException("环境经验超过8项");
      for (var e : o.contexts.entrySet()) {
        var c = e.getValue();
        if (!e.getKey().equals("unknown") && !e.getKey().matches("[0-9a-f]{64}")
            || c == null
            || c.verified < 0
            || c.partial < 0
            || c.failed < 0) throw new IllegalArgumentException("环境经验格式错误");
        c.condition = Rules.clean(c.condition, 220);
      }
    }
    for (var e : b.interfaces.entrySet()) {
      if (!e.getKey().matches("[0-9a-f]{64}") || e.getValue() == null)
        throw new IllegalArgumentException("界面签名无效");
      var n = e.getValue();
      n.confirmed = Rules.clean(n.confirmed, 1500);
    }
  }

  public static void init() {
    try {
      Path p = Store.ROOT.resolve("skill-experience.json");
      if (Files.exists(p)) {
        Book b = Store.GSON.fromJson(Files.readString(p), Book.class);
        validate(b);
        book = b;
      }
    } catch (Exception e) {
      readable = false;
      Companion.log("技能经验文件读取失败，原文件保留，本次不覆盖。");
    }
  }

  public static Path backup() throws Exception {
    Path p = Store.ROOT.resolve("experience-backup-" + System.currentTimeMillis() + ".json");
    Files.createDirectories(Store.ROOT);
    Files.writeString(p, Store.GSON.toJson(book), StandardCharsets.UTF_8);
    return p;
  }

  public static void restore(String text) throws Exception {
    Book b = Store.GSON.fromJson(text, Book.class);
    validate(b);
    if (!readable) throw new IllegalStateException("原经验文件无法读取，请先备份并修复原文件");
    backup();
    book = b;
    Store.save();
  }

  public static void record(String id, String version, String result) {
    String key = id + "@" + version;
    if (book.outcomes.size() >= 512 && !book.outcomes.containsKey(key))
      book.outcomes.remove(book.outcomes.keySet().iterator().next());
    Outcome o = book.outcomes.computeIfAbsent(key, k -> new Outcome());
    if (result.startsWith("成功：")) o.verified++;
    else if (result.startsWith("部分完成")) o.partial++;
    else o.failed++;
    o.last = java.time.Instant.now().toString();
    if (o.contexts == null) o.contexts = new LinkedHashMap<>();
    String context = EnvironmentKey.current();
    if (o.contexts.size() >= 8 && !o.contexts.containsKey(context))
      o.contexts.remove(o.contexts.keySet().iterator().next());
    var local = o.contexts.computeIfAbsent(context, k -> new ContextOutcome());
    if (result.startsWith("成功：")) local.verified++;
    else if (result.startsWith("部分完成")) local.partial++;
    else local.failed++;
    local.last = o.last;
    local.build = BuildInfo.ID;
    local.condition =
        result.startsWith("成功：")
            ? "verified_outcome"
            : result.startsWith("部分完成") ? "partial_outcome" : "failed_outcome";
    Store.save();
  }

  public static void procedure(String name, String definition, boolean success) {
    String key =
        "procedure:"
            + name
            + "@"
            + UUID.nameUUIDFromBytes(definition.getBytes(StandardCharsets.UTF_8));
    if (book.outcomes.size() >= 512 && !book.outcomes.containsKey(key))
      book.outcomes.remove(book.outcomes.keySet().iterator().next());
    var o = book.outcomes.computeIfAbsent(key, k -> new Outcome());
    if (success) o.stepsCompleted++;
    else o.failed++;
    o.last = java.time.Instant.now().toString();
  }

  public static void proposal(String signature, String text) {
    if (signature.isBlank()) return;
    String key = VisualAssist.worldKey(signature);
    var notes = Store.data.visualNotes;
    if (notes.size() >= 256 && !notes.containsKey(key))
      notes.remove(notes.keySet().iterator().next());
    Store.VisualNote n = new Store.VisualNote();
    n.text = Rules.clean(text, 2500);
    n.observedAt = System.currentTimeMillis();
    notes.put(key, n);
  }

  public static void save() throws Exception {
    if (!readable) return;
    Path p = Store.ROOT.resolve("skill-experience.json"),
        tmp = p.resolveSibling("skill-experience.json.tmp");
    Files.writeString(tmp, Store.GSON.toJson(book), StandardCharsets.UTF_8);
    try {
      Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
