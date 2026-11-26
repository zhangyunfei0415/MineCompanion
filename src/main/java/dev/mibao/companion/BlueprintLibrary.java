package dev.mibao.companion;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * Client-side schematic discovery, path-traversal-safe loader, and BOM inventory/WorldMemory
 * comparator for .litematic and Vanilla/Create .nbt schematics.
 */
public final class BlueprintLibrary {
  public record Entry(String name, String source, Path path) {}

  public record LoadedBlueprint(Entry entry, BlueprintFormat.BlueprintSpec spec, String sha256) {}

  private BlueprintLibrary() {}

  public static List<Path> searchDirectories() {
    var mc = Minecraft.getInstance();
    Path gameDir = mc.gameDirectory.toPath();
    return List.of(
        gameDir.resolve("schematics"),
        gameDir.resolve("config/litematica"),
        gameDir.resolve("schematics/litematica"),
        Store.ROOT.resolve("blueprints"));
  }

  public static List<Entry> discover() {
    Map<String, Entry> found = new LinkedHashMap<>();
    for (Path dir : searchDirectories()) {
      if (!Files.isDirectory(dir)) continue;
      try (var walk = Files.walk(dir, 3)) {
        walk.filter(Files::isRegularFile)
            .filter(
                p -> {
                  String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                  return n.endsWith(".nbt") || n.endsWith(".litematic");
                })
            .sorted()
            .forEach(
                p -> {
                  String rel = dir.relativize(p).toString().replace('\\', '/');
                  found.putIfAbsent(rel, new Entry(rel, dir.getFileName().toString(), p));
                });
      } catch (IOException ignored) {
      }
    }
    return new ArrayList<>(found.values());
  }

  public static String fileSha256(Path path) throws IOException {
    try {
      var md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] bytes = Files.readAllBytes(path);
      byte[] digest = md.digest(bytes);
      var sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02X", b));
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static BlueprintFormat.BlueprintSpec load(
      String rawName, int rotationDeg, String mirror) throws IOException {
    return loadWithMeta(rawName, rotationDeg, mirror).spec();
  }

  public static LoadedBlueprint loadWithMeta(String rawName, int rotationDeg, String mirror)
      throws IOException {
    if (rawName == null || rawName.isBlank())
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 请指定蓝图文件名 name");
    String clean = rawName.strip().replace('\\', '/');
    if (clean.contains("..") || clean.startsWith("/"))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 非法蓝图路径：" + rawName);
    List<Entry> all = discover();
    Entry matched = null;
    for (Entry e : all) {
      if (e.name().equalsIgnoreCase(clean)
          || e.path().getFileName().toString().equalsIgnoreCase(clean)) {
        matched = e;
        break;
      }
    }
    if (matched == null) {
      String stem = clean.toLowerCase(Locale.ROOT);
      for (Entry e : all) {
        String f = e.path().getFileName().toString().toLowerCase(Locale.ROOT);
        if (f.equals(stem + ".nbt") || f.equals(stem + ".litematic")) {
          matched = e;
          break;
        }
      }
    }
    if (matched == null)
      throw new IllegalArgumentException("[NOT_FOUND] 未找到蓝图：" + rawName + "（先用 blueprint_list 查看）");
    BlueprintFormat.BlueprintSpec spec = BlueprintFormat.load(matched.path());
    return new LoadedBlueprint(matched, spec.transform(rotationDeg, mirror), fileSha256(matched.path()));
  }

  public static JsonObject listJson() {
    var out = new JsonObject();
    var arr = new JsonArray();
    for (Entry e : discover()) {
      var item = new JsonObject();
      item.addProperty("name", e.name());
      item.addProperty("file_name", e.name());
      item.addProperty("dir", e.source());
      try {
        var spec = BlueprintFormat.load(e.path());
        item.addProperty("display_name", spec.name);
        item.addProperty("format", spec.format);
        item.addProperty("size_x", spec.sizeX);
        item.addProperty("size_y", spec.sizeY);
        item.addProperty("size_z", spec.sizeZ);
        item.addProperty("non_air_blocks", spec.voxels.size());
        item.addProperty("blueprint_sha256", fileSha256(e.path()));
      } catch (Exception ex) {
        item.addProperty("error", Rules.clean(ex.getMessage(), 120));
      }
      arr.add(item);
    }
    out.addProperty("count", arr.size());
    out.add("blueprints", arr);
    var dirs = new JsonArray();
    for (Path d : searchDirectories()) dirs.add(d.toString().replace('\\', '/'));
    out.add("searched_dirs", dirs);
    return out;
  }

  public static JsonObject inspectJson(String name, int rotationDeg, String mirror)
      throws IOException {
    LoadedBlueprint loaded = loadWithMeta(name, rotationDeg, mirror);
    BlueprintFormat.BlueprintSpec spec = loaded.spec();
    Map<String, Integer> bagCounts = playerBagItemCounts();
    Map<String, Integer> baseCounts = baseChestItemCounts();
    List<BlueprintPlanner.BomRow> bom = BlueprintPlanner.diffBom(spec, bagCounts, baseCounts);

    var out = new JsonObject();
    out.addProperty("name", spec.name);
    out.addProperty("display_name", spec.name);
    out.addProperty("file_name", loaded.entry().name());
    out.addProperty("blueprint_sha256", loaded.sha256());
    out.addProperty("format", spec.format);
    out.addProperty("rotation", BlueprintFormat.normalizeRotation(rotationDeg));
    out.addProperty("mirror", BlueprintFormat.normalizeMirror(mirror));
    out.addProperty("size_x", spec.sizeX);
    out.addProperty("size_y", spec.sizeY);
    out.addProperty("size_z", spec.sizeZ);
    out.addProperty("non_air_blocks", spec.voxels.size());

    int totalMissing = 0;
    var materials = new JsonArray();
    for (BlueprintPlanner.BomRow r : bom) {
      totalMissing += r.missing();
      materials.add(r.toJson());
    }
    out.addProperty("total_missing_items", totalMissing);
    out.add("materials", materials);
    out.add("scaffold_estimate", BlueprintScaffoldPlanner.estimateForBlueprint(spec));
    out.addProperty(
        "note",
        "in_base_chests 来自 WorldMemory 已观测存储容器快照；建造施工使用真实背包物品与 PlaceJob");
    return out;
  }

  public static Map<String, Integer> playerBagItemCounts() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    var p = Minecraft.getInstance().player;
    if (p == null) return counts;
    for (int i = 0; i < 36; i++) {
      ItemStack s = p.getInventory().getItem(i);
      if (s.isEmpty()) continue;
      String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
      counts.merge(id, s.getCount(), Integer::sum);
    }
    return counts;
  }

  public static Map<String, Integer> baseChestItemCounts() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    var mc = Minecraft.getInstance();
    if (mc.level == null || Store.data == null || Store.data.world == null) return counts;
    String dim = mc.level.dimension().location().toString();
    for (WorldMemory.Point pt : Store.data.world.objects.values()) {
      if (pt == null
          || !pt.present
          || !dim.equals(pt.dimension)
          || !"storage".equals(pt.category)
          || pt.inventory == null
          || pt.inventory.items == null) continue;
      for (WorldMemory.StackInfo st : pt.inventory.items) {
        if (st == null || st.id == null || st.count <= 0) continue;
        counts.merge(st.id, st.count, Integer::sum);
      }
    }
    return counts;
  }
}
