package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/**
 * Pure-Java bounded ring-buffer journal recording confirmed block breaks/placements by the agent
 * for client-only honest review and physical rollback. Zero {@code net.minecraft.*} imports.
 */
public final class RollbackJournal {
  public static final int MAX_ENTRIES = 500;

  public enum Action {
    BREAK,
    PLACE
  }

  public static final class Entry {
    public long id;
    public long ts;
    public String dim = "";
    public int x, y, z;
    public String action = "BREAK";
    public String beforeBlock = "";
    public String afterBlock = "";
    public Map<String, String> beforeProps = new LinkedHashMap<>();
    public boolean hasBlockEntity;
    public String tool = "";
    public String taskId = "";
    public String note = "";

    public JsonObject toJson() {
      var o = new JsonObject();
      o.addProperty("id", id);
      o.addProperty("ts", ts);
      o.addProperty("dim", dim);
      o.addProperty("x", x);
      o.addProperty("y", y);
      o.addProperty("z", z);
      o.addProperty("action", action);
      o.addProperty("before_block", beforeBlock);
      o.addProperty("after_block", afterBlock);
      if (beforeProps != null && !beforeProps.isEmpty()) {
        var p = new JsonObject();
        for (var e : beforeProps.entrySet()) p.addProperty(e.getKey(), e.getValue());
        o.add("before_props", p);
      }
      if (hasBlockEntity) o.addProperty("has_block_entity", true);
      if (tool != null && !tool.isBlank()) o.addProperty("tool", tool);
      if (taskId != null && !taskId.isBlank()) o.addProperty("task_id", taskId);
      if (note != null && !note.isBlank()) o.addProperty("note", note);
      return o;
    }
  }

  public static final class Log {
    public long nextId = 1;
    public List<Entry> entries = new ArrayList<>();

    public void normalize() {
      if (entries == null) entries = new ArrayList<>();
      entries.removeIf(e -> e == null || e.dim == null || e.beforeBlock == null || e.afterBlock == null);
      for (Entry e : entries) {
        if (e.beforeProps == null) e.beforeProps = new LinkedHashMap<>();
        if (e.tool == null) e.tool = "";
        if (e.taskId == null) e.taskId = "";
        if (e.note == null) e.note = "";
        if (e.id >= nextId) nextId = e.id + 1;
      }
      if (entries.size() > MAX_ENTRIES) {
        entries = new ArrayList<>(entries.subList(entries.size() - MAX_ENTRIES, entries.size()));
      }
    }
  }

  private RollbackJournal() {}

  public static Entry record(
      Log log,
      String dim,
      int x,
      int y,
      int z,
      Action action,
      String beforeBlock,
      String afterBlock,
      Map<String, String> beforeProps,
      boolean hasBlockEntity,
      String tool,
      String taskId,
      String note,
      long nowMs) {
    if (log == null || action == null || dim == null || dim.isBlank()) return null;
    log.normalize();
    Entry e = new Entry();
    e.id = log.nextId++;
    e.ts = nowMs;
    e.dim = dim;
    e.x = x;
    e.y = y;
    e.z = z;
    e.action = action.name();
    e.beforeBlock = beforeBlock == null ? "minecraft:air" : beforeBlock;
    e.afterBlock = afterBlock == null ? "minecraft:air" : afterBlock;
    if (beforeProps != null && !beforeProps.isEmpty()) e.beforeProps.putAll(beforeProps);
    e.hasBlockEntity = hasBlockEntity;
    e.tool = Rules.clean(tool, 48);
    e.taskId = Rules.clean(taskId, 64);
    e.note = Rules.clean(note, 120);
    log.entries.add(e);
    while (log.entries.size() > MAX_ENTRIES) {
      log.entries.remove(0);
    }
    return e;
  }

  public static List<Entry> list(Log log, String dim, int limit, String taskId) {
    if (log == null) return List.of();
    log.normalize();
    int cap = Rules.bounded(limit <= 0 ? 20 : limit, 1, 200);
    List<Entry> out = new ArrayList<>();
    for (int i = log.entries.size() - 1; i >= 0 && out.size() < cap; i--) {
      Entry e = log.entries.get(i);
      if (dim != null && !dim.isBlank() && !dim.equals(e.dim)) continue;
      if (taskId != null && !taskId.isBlank() && !taskId.equals(e.taskId)) continue;
      out.add(e);
    }
    return out;
  }

  public static List<Entry> selectForRestore(
      Log log, String dim, int count, Collection<Long> ids, String taskId) {
    if (log == null) return List.of();
    log.normalize();
    if (ids != null && !ids.isEmpty()) {
      Set<Long> targetIds = new HashSet<>(ids);
      List<Entry> out = new ArrayList<>();
      for (int i = log.entries.size() - 1; i >= 0; i--) {
        Entry e = log.entries.get(i);
        if ((dim == null || dim.isBlank() || dim.equals(e.dim)) && targetIds.contains(e.id)) {
          out.add(e);
        }
      }
      return out;
    }
    return list(log, dim, Rules.bounded(count <= 0 ? 10 : count, 1, 64), taskId);
  }

  public static void removeRestored(Log log, Collection<Long> ids) {
    if (log == null || ids == null || ids.isEmpty()) return;
    log.normalize();
    Set<Long> set = new HashSet<>(ids);
    log.entries.removeIf(e -> set.contains(e.id));
  }

  public static int clear(Log log, String dim) {
    if (log == null) return 0;
    log.normalize();
    int before = log.entries.size();
    if (dim == null || dim.isBlank()) log.entries.clear();
    else log.entries.removeIf(e -> dim.equals(e.dim));
    return before - log.entries.size();
  }

  public static int countRetained(Log log, String dim, String taskId) {
    if (log == null) return 0;
    log.normalize();
    int count = 0;
    for (Entry e : log.entries) {
      if (dim != null && !dim.isBlank() && !dim.equals(e.dim)) continue;
      if (taskId != null && !taskId.isBlank() && !taskId.equals(e.taskId)) continue;
      count++;
    }
    return count;
  }
}
