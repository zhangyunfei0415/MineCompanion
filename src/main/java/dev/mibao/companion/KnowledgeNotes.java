package dev.mibao.companion;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Explicitly imported mod manuals and workflow notes; no arbitrary-path access or executable
 * instructions.
 */
public final class KnowledgeNotes {
  private static Path folder() {
    return Store.ROOT.resolve("companion/knowledge");
  }

  public static void put(String id, String source, String text) throws Exception {
    if (!id.matches("[a-z0-9_-]{1,48}"))
      throw new IllegalArgumentException("资料名仅允许小写字母、数字、下划线和连字符，最长48");
    if (text.isBlank()) throw new IllegalArgumentException("资料内容为空");
    if (!Store.claimControl()) throw new IllegalStateException("另一窗口持有控制权");
    Files.createDirectories(folder());
    Path p = folder().resolve(id + ".json");
    var o = new JsonObject();
    o.addProperty("id", id);
    o.addProperty("source", Rules.clean(source, 500));
    o.addProperty("text", Rules.clean(text, 12000));
    o.addProperty("updated", java.time.Instant.now().toString());
    o.addProperty("status", "reference_only_unverified");
    if (Files.exists(p))
      Files.copy(p, p.resolveSibling(p.getFileName() + ".backup-" + System.currentTimeMillis()));
    Path tmp = p.resolveSibling(id + ".tmp");
    Files.writeString(tmp, Store.GSON.toJson(o), StandardCharsets.UTF_8);
    Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
  }

  public static JsonArray list() {
    return search("", false);
  }

  public static JsonArray search(String query, boolean includeText) {
    var rows = new JsonArray();
    String q = Rules.clean(query, 100).toLowerCase(Locale.ROOT);
    try {
      var paths = new java.util.ArrayList<Path>();
      for (Path dir : List.of(folder(), SkillLibrary.root().resolve("knowledge")))
        if (Files.isDirectory(dir))
          try (var files = Files.list(dir)) {
            paths.addAll(
                files
                    .filter(p -> p.getFileName().toString().matches("[a-z0-9_-]{1,48}\\.json"))
                    .sorted()
                    .limit(128)
                    .toList());
          }
      for (var p : paths) {
        if (Files.size(p) > 60000) continue;
        var o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
        if (!o.toString().toLowerCase(Locale.ROOT).contains(q)) continue;
        var result = o.deepCopy();
        result.addProperty("status", "reference_only_unverified");
        if (!includeText) result.remove("text");
        else result.addProperty("text", Rules.clean(Agent.str(result, "text", ""), 4000));
        rows.add(result);
        if (rows.size() >= 8) break;
      }
    } catch (Exception e) {
      var error = new JsonObject();
      error.addProperty("error", "资料读取失败，原文件保留：" + Rules.clean(e.getMessage(), 120));
      rows.add(error);
    }
    return rows;
  }

  private KnowledgeNotes() {}
}
