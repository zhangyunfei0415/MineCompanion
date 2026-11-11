package dev.mibao.companion;

import java.util.*;

/** Persistent, bounded evidence about observed blocks; never treats absence of a chunk as air. */
public final class WorldMemory {
  public static final int MAX_OBJECTS = 2048;
  public Map<String, Point> objects = new LinkedHashMap<>();

  public static final class Point {
    public String key,
        dimension,
        block,
        state,
        name,
        category,
        label = "",
        source = "loaded_client_block";
    public int x, y, z;
    public long firstSeen, lastSeen, changed, inventoryInvalidated;
    public String invalidReason = "";
    public String signText = "";
    public boolean present = true;
    public Inventory inventory;
  }

  public static final class StackInfo {
    public String id, name, fingerprint, nbt = "";
    public int count, slot;
  }

  public static final class Inventory {
    public String menuType, source = "opened_client_menu";
    public long observed;
    public int slotCount;
    public boolean truncated;
    public boolean contentsConfirmed;
    public String semantics = "unclassified_visible_slots";
    public int menuId, serverState;
    public long syncRevision, syncedAt;
    public List<StackInfo> items = new ArrayList<>();
  }

  public static String key(String dimension, int x, int y, int z) {
    return dimension + "@" + x + "," + y + "," + z;
  }

  public Point get(String key) {
    return objects.get(key);
  }

  public void observe(
      String dim,
      int x,
      int y,
      int z,
      String block,
      String state,
      String name,
      String category,
      long now) {
    String key = key(dim, x, y, z);
    Point p = objects.get(key);
    if (category.isBlank()) {
      if (p != null) {
        if (p.present) {
          p.changed = now;
          invalidate(p, "observed_removed_or_replaced", now);
        }
        p.present = false;
        p.lastSeen = now;
      }
      return;
    }
    if (p == null) {
      p = new Point();
      p.key = key;
      p.dimension = dim;
      p.x = x;
      p.y = y;
      p.z = z;
      p.firstSeen = now;
      p.changed = now;
    } else if (!p.present || !Objects.equals(p.block, block)) {
      p.changed = now;
      invalidate(p, "observed_block_identity_changed", now);
    } else if (!Objects.equals(p.state, state)) {
      p.changed = now;
      if (category.equals("storage") && !block.equals("minecraft:barrel"))
        invalidate(p, "storage_layout_changed", now);
    }
    p.block = block;
    p.state = state;
    p.name = name;
    p.category = category;
    p.lastSeen = now;
    p.present = true;
    objects.remove(key);
    objects.put(key, p);
    trim();
  }

  public void invalidate(Point p, String reason, long now) {
    p.inventory = null;
    p.inventoryInvalidated = now;
    p.invalidReason = reason;
  }

  public void inventory(String key, Inventory inventory) {
    Point p = objects.get(key);
    if (p == null || !p.present)
      throw new IllegalArgumentException("Cannot attach inventory to absent object");
    p.inventory = inventory;
    p.invalidReason = "";
  }

  public List<Point> query(
      String dimension, int x, int y, int z, String query, String category, int limit, int radius) {
    String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    String cat = category == null ? "" : category;
    return objects.values().stream()
        .filter(p -> p != null && p.present && dimension.equals(p.dimension))
        .filter(p -> cat.isBlank() || cat.equals("all") || cat.equals(p.category))
        .filter(
            p ->
                q.isBlank()
                    || (p.block
                            + " "
                            + p.name
                            + " "
                            + p.label
                            + " "
                            + p.category
                            + " "
                            + p.key
                            + " "
                            + p.signText)
                        .toLowerCase(Locale.ROOT)
                        .contains(q)
                    || p.inventory != null
                        && p.inventory.items != null
                        && p.inventory.items.stream()
                            .anyMatch(
                                s -> (s.id + " " + s.name).toLowerCase(Locale.ROOT).contains(q)))
        .filter(p -> radius <= 0 || distanceSquared(p, x, y, z) <= (double) radius * radius)
        .sorted(Comparator.comparingDouble(p -> distanceSquared(p, x, y, z)))
        .limit(Rules.bounded(limit, 1, MAX_OBJECTS))
        .toList();
  }

  public static double distanceSquared(Point p, int x, int y, int z) {
    return Math.pow((double) p.x - x, 2)
        + Math.pow((double) p.y - y, 2)
        + Math.pow((double) p.z - z, 2);
  }

  public void normalize() {
    if (objects == null) objects = new LinkedHashMap<>();
    objects
        .entrySet()
        .removeIf(
            e ->
                e.getValue() == null
                    || e.getValue().dimension == null
                    || e.getValue().block == null
                    || e.getValue().key == null);
    for (var p : objects.values()) {
      if (p.name == null) p.name = p.block;
      if (p.category == null) p.category = "block_entity";
      if (p.label == null) p.label = "";
      if (p.inventory != null && p.inventory.items == null) p.inventory = null;
    }
    trim();
  }

  private void trim() {
    while (objects.size() > MAX_OBJECTS) {
      String victim =
          objects.entrySet().stream()
              .filter(
                  e -> {
                    var p = e.getValue();
                    return (p.label == null || p.label.isBlank())
                        && p.inventory == null
                        && !Set.of("storage", "crafting", "furnace", "block_entity")
                            .contains(p.category);
                  })
              .map(Map.Entry::getKey)
              .findFirst()
              .orElse(objects.keySet().iterator().next());
      objects.remove(victim);
    }
  }
}
