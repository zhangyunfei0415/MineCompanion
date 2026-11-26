package dev.mibao.companion;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Pure-Java GZIP/uncompressed NBT reader/writer and dual schematic decoder for Litematica
 * ({@code .litematic}) and Vanilla Structure / Create ({@code .nbt}) files. Zero
 * {@code net.minecraft.*} imports so tests run cleanly without Minecraft runtime.
 */
public final class BlueprintFormat {
  public record Voxel(int x, int y, int z, String blockId, Map<String, String> properties) {}

  public static final class BlueprintSpec {
    public final String name;
    public final String format;
    public final int sizeX, sizeY, sizeZ;
    public final List<Voxel> voxels;
    public final Map<String, Integer> blockCounts;

    public BlueprintSpec(
        String name,
        String format,
        int sizeX,
        int sizeY,
        int sizeZ,
        List<Voxel> voxels,
        Map<String, Integer> blockCounts) {
      this.name = name;
      this.format = format;
      this.sizeX = Math.max(1, sizeX);
      this.sizeY = Math.max(1, sizeY);
      this.sizeZ = Math.max(1, sizeZ);
      this.voxels = Collections.unmodifiableList(new ArrayList<>(voxels));
      this.blockCounts = Collections.unmodifiableMap(new LinkedHashMap<>(blockCounts));
    }

    public BlueprintSpec transform(int rotationDeg, String mirrorMode) {
      int rot = normalizeRotation(rotationDeg);
      String mir = normalizeMirror(mirrorMode);
      if (rot == 0 && mir.equals("none")) return this;
      int w = sizeX, d = sizeZ;
      List<Voxel> out = new ArrayList<>(voxels.size());
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (Voxel v : voxels) {
        int x = v.x(), z = v.z();
        if (mir.equals("left_right")) z = d - 1 - z;
        else if (mir.equals("front_back")) x = w - 1 - x;
        int rx, rz;
        switch (rot) {
          case 90 -> {
            rx = d - 1 - z;
            rz = x;
          }
          case 180 -> {
            rx = w - 1 - x;
            rz = d - 1 - z;
          }
          case 270 -> {
            rx = z;
            rz = w - 1 - x;
          }
          default -> {
            rx = x;
            rz = z;
          }
        }
        Map<String, String> props = transformProperties(v.properties(), rot, mir);
        out.add(new Voxel(rx, v.y(), rz, v.blockId(), props));
        counts.merge(v.blockId(), 1, Integer::sum);
      }
      int outX = (rot == 90 || rot == 270) ? d : w;
      int outZ = (rot == 90 || rot == 270) ? w : d;
      return new BlueprintSpec(name, format, outX, sizeY, outZ, out, counts);
    }
  }

  private BlueprintFormat() {}

  public static int normalizeRotation(int deg) {
    int m = ((deg % 360) + 360) % 360;
    return switch (m) {
      case 0, 90, 180, 270 -> m;
      default -> throw new IllegalArgumentException(
          "[INVALID_ARGUMENT] rotation须为 0/90/180/270");
    };
  }

  public static String normalizeMirror(String mirror) {
    if (mirror == null || mirror.isBlank()) return "none";
    return switch (mirror.strip().toLowerCase(Locale.ROOT)) {
      case "none", "false" -> "none";
      case "left_right", "z", "lr" -> "left_right";
      case "front_back", "x", "fb" -> "front_back";
      default -> throw new IllegalArgumentException(
          "[INVALID_ARGUMENT] mirror须为 none/left_right/front_back");
    };
  }

  public static BlueprintSpec load(Path file) throws IOException {
    byte[] raw = Files.readAllBytes(file);
    String filename = file.getFileName().toString();
    return parse(filename, raw);
  }

  public static BlueprintSpec parse(String filename, byte[] rawBytes) throws IOException {
    if (rawBytes == null || rawBytes.length < 4)
      throw new IllegalArgumentException("[BLUEPRINT_INVALID] 蓝图文件为空或过小");
    Map<String, Object> root = readNbt(rawBytes);
    String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".litematic") || root.containsKey("Regions")) {
      return parseLitematic(filename, root);
    }
    if (lower.endsWith(".nbt") || (root.containsKey("blocks") && root.containsKey("size"))) {
      return parseStructureNbt(filename, root);
    }
    throw new IllegalArgumentException("[BLUEPRINT_UNSUPPORTED] 不支持的蓝图格式（仅支持 .litematic 与 .nbt）");
  }

  public static boolean isAirBlock(String blockId) {
    if (blockId == null || blockId.isBlank()) return true;
    return blockId.equals("minecraft:air")
        || blockId.equals("minecraft:cave_air")
        || blockId.equals("minecraft:void_air")
        || blockId.equals("litematica:air");
  }

  private static BlueprintSpec parseLitematic(String filename, Map<String, Object> root) {
    Map<String, Object> regions = asCompound(root.get("Regions"));
    if (regions == null || regions.isEmpty())
      throw new IllegalArgumentException("[BLUEPRINT_INVALID] Litematica 缺少 Regions 数据");
    String name = filename;
    Map<String, Object> meta = asCompound(root.get("Metadata"));
    if (meta != null && meta.get("Name") instanceof String s && !s.isBlank()) name = s;

    record RawRegion(
        int minX,
        int minY,
        int minZ,
        int absX,
        int absY,
        int absZ,
        List<Map<String, Object>> palette,
        long[] blockStates) {}

    List<RawRegion> parsedRegions = new ArrayList<>();
    int gMinX = Integer.MAX_VALUE, gMinY = Integer.MAX_VALUE, gMinZ = Integer.MAX_VALUE;
    int gMaxX = Integer.MIN_VALUE, gMaxY = Integer.MIN_VALUE, gMaxZ = Integer.MIN_VALUE;

    for (Object val : regions.values()) {
      Map<String, Object> reg = asCompound(val);
      if (reg == null) continue;
      Map<String, Object> pos = asCompound(reg.get("Position"));
      Map<String, Object> size = asCompound(reg.get("Size"));
      if (pos == null || size == null) continue;
      int px = asInt(pos.get("x"), 0);
      int py = asInt(pos.get("y"), 0);
      int pz = asInt(pos.get("z"), 0);
      int sx = asInt(size.get("x"), 0);
      int sy = asInt(size.get("y"), 0);
      int sz = asInt(size.get("z"), 0);
      int absX = Math.abs(sx);
      int absY = Math.abs(sy);
      int absZ = Math.abs(sz);
      if (absX == 0 || absY == 0 || absZ == 0) continue;
      int minX = px + (sx < 0 ? sx + 1 : 0);
      int minY = py + (sy < 0 ? sy + 1 : 0);
      int minZ = pz + (sz < 0 ? sz + 1 : 0);
      List<Map<String, Object>> palette = asCompoundList(reg.get("BlockStatePalette"));
      long[] states = reg.get("BlockStates") instanceof long[] la ? la : new long[0];
      if (palette.isEmpty()) continue;
      parsedRegions.add(new RawRegion(minX, minY, minZ, absX, absY, absZ, palette, states));
      gMinX = Math.min(gMinX, minX);
      gMinY = Math.min(gMinY, minY);
      gMinZ = Math.min(gMinZ, minZ);
      gMaxX = Math.max(gMaxX, minX + absX);
      gMaxY = Math.max(gMaxY, minY + absY);
      gMaxZ = Math.max(gMaxZ, minZ + absZ);
    }

    if (parsedRegions.isEmpty())
      throw new IllegalArgumentException("[BLUEPRINT_INVALID] Litematica 没有有效选区");

    List<Voxel> voxels = new ArrayList<>();
    Map<String, Integer> counts = new LinkedHashMap<>();
    Set<Long> seenPositions = new HashSet<>();

    for (RawRegion r : parsedRegions) {
      int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, r.palette().size() - 1)));
      long mask = (1L << bits) - 1L;
      long[] bs = r.blockStates();
      for (int y = 0; y < r.absY(); y++) {
        for (int z = 0; z < r.absZ(); z++) {
          for (int x = 0; x < r.absX(); x++) {
            long index = ((long) y * r.absZ() + z) * r.absX() + x;
            long startOffset = index * bits;
            int startArrIndex = (int) (startOffset >>> 6);
            int endArrIndex = (int) (((index + 1L) * bits - 1L) >>> 6);
            int startBitOffset = (int) (startOffset & 63L);
            if (startArrIndex >= bs.length) continue;
            long paletteIdx;
            if (startArrIndex == endArrIndex || endArrIndex >= bs.length) {
              paletteIdx = (bs[startArrIndex] >>> startBitOffset) & mask;
            } else {
              int endBitOffset = 64 - startBitOffset;
              paletteIdx =
                  ((bs[startArrIndex] >>> startBitOffset) | (bs[endArrIndex] << endBitOffset))
                      & mask;
            }
            int pIdx = (int) paletteIdx;
            if (pIdx < 0 || pIdx >= r.palette().size()) continue;
            Map<String, Object> entry = r.palette().get(pIdx);
            String blockId = asString(entry.get("Name"), "minecraft:air");
            if (isAirBlock(blockId)) continue;
            int gx = r.minX() + x - gMinX;
            int gy = r.minY() + y - gMinY;
            int gz = r.minZ() + z - gMinZ;
            long packedKey = (((long) gx & 0xFFFFFL) << 40) | (((long) gy & 0xFFFFFL) << 20) | ((long) gz & 0xFFFFFL);
            if (!seenPositions.add(packedKey)) continue;
            Map<String, String> props = readProperties(asCompound(entry.get("Properties")));
            voxels.add(new Voxel(gx, gy, gz, blockId, props));
            counts.merge(blockId, 1, Integer::sum);
          }
        }
      }
    }

    return new BlueprintSpec(
        name, "litematic", gMaxX - gMinX, gMaxY - gMinY, gMaxZ - gMinZ, voxels, counts);
  }

  private static BlueprintSpec parseStructureNbt(String filename, Map<String, Object> root) {
    List<Integer> size = asIntList(root.get("size"));
    if (size.size() < 3)
      throw new IllegalArgumentException("[BLUEPRINT_INVALID] .nbt 缺少 size 三元组");
    int sx = Math.max(1, size.get(0));
    int sy = Math.max(1, size.get(1));
    int sz = Math.max(1, size.get(2));
    List<Map<String, Object>> palette = asCompoundList(root.get("palette"));
    if (palette.isEmpty() && root.get("palettes") instanceof List<?> pals && !pals.isEmpty()) {
      palette = asCompoundList(pals.get(0));
    }
    if (palette.isEmpty())
      throw new IllegalArgumentException("[BLUEPRINT_INVALID] .nbt 缺少 palette 调色板");
    List<Map<String, Object>> blocks = asCompoundList(root.get("blocks"));
    List<Voxel> voxels = new ArrayList<>();
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Map<String, Object> b : blocks) {
      List<Integer> pos = asIntList(b.get("pos"));
      int stateIdx = asInt(b.get("state"), -1);
      if (pos.size() < 3 || stateIdx < 0 || stateIdx >= palette.size()) continue;
      Map<String, Object> entry = palette.get(stateIdx);
      String blockId = asString(entry.get("Name"), "minecraft:air");
      if (isAirBlock(blockId)) continue;
      Map<String, String> props = readProperties(asCompound(entry.get("Properties")));
      voxels.add(new Voxel(pos.get(0), pos.get(1), pos.get(2), blockId, props));
      counts.merge(blockId, 1, Integer::sum);
    }
    return new BlueprintSpec(filename, "nbt", sx, sy, sz, voxels, counts);
  }

  private static Map<String, String> readProperties(Map<String, Object> raw) {
    if (raw == null || raw.isEmpty()) return Map.of();
    Map<String, String> out = new LinkedHashMap<>();
    for (var e : raw.entrySet()) {
      if (e.getValue() != null) out.put(e.getKey(), String.valueOf(e.getValue()));
    }
    return Collections.unmodifiableMap(out);
  }

  private static Map<String, String> transformProperties(
      Map<String, String> props, int rotDeg, String mirror) {
    if (props == null || props.isEmpty()) return Map.of();
    Map<String, String> out = new LinkedHashMap<>(props);
    String facing = out.get("facing");
    if (facing != null) {
      String f = facing;
      if (mirror.equals("left_right")) {
        if (f.equals("north")) f = "south";
        else if (f.equals("south")) f = "north";
      } else if (mirror.equals("front_back")) {
        if (f.equals("east")) f = "west";
        else if (f.equals("west")) f = "east";
      }
      f = rotateHorizontal(f, rotDeg);
      out.put("facing", f);
    }
    String axis = out.get("axis");
    if ((rotDeg == 90 || rotDeg == 270) && axis != null) {
      if (axis.equals("x")) out.put("axis", "z");
      else if (axis.equals("z")) out.put("axis", "x");
    }
    return Collections.unmodifiableMap(out);
  }

  private static String rotateHorizontal(String facing, int rotDeg) {
    List<String> ring = List.of("north", "east", "south", "west");
    int idx = ring.indexOf(facing);
    if (idx < 0) return facing;
    return ring.get((idx + (rotDeg / 90)) % 4);
  }

  // ---- Pure Java GZIP/Raw NBT Reader & Writer ----

  public static Map<String, Object> readNbt(byte[] rawBytes) throws IOException {
    InputStream base = new ByteArrayInputStream(rawBytes);
    if (rawBytes.length >= 2 && (rawBytes[0] & 0xFF) == 0x1F && (rawBytes[1] & 0xFF) == 0x8B) {
      base = new GZIPInputStream(base);
    } else if (rawBytes.length >= 2 && (rawBytes[0] & 0xFF) == 0x78) {
      base = new InflaterInputStream(base);
    }
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(base))) {
      int rootType = in.readUnsignedByte();
      if (rootType != 10)
        throw new IOException("Expected root TAG_Compound(10), got " + rootType);
      readString(in); // root tag name
      return readCompound(in, 0);
    }
  }

  public static byte[] writeGzipNbt(String rootName, Map<String, Object> compound) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(baos);
        DataOutputStream out = new DataOutputStream(gz)) {
      out.writeByte(10);
      writeString(out, rootName == null ? "" : rootName);
      writeCompound(out, compound);
    }
    return baos.toByteArray();
  }

  /** Helper tag wrapper for deterministic testing/serialization of TAG_List element types or ints/longs. */
  public record NbtIntArray(int[] values) {}

  public record NbtByteArray(byte[] values) {}

  private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
    if (depth > 64) throw new IOException("NBT nesting depth > 64");
    return switch (type) {
      case 1 -> in.readByte();
      case 2 -> in.readShort();
      case 3 -> in.readInt();
      case 4 -> in.readLong();
      case 5 -> in.readFloat();
      case 6 -> in.readDouble();
      case 7 -> {
        int len = in.readInt();
        byte[] b = new byte[Math.max(0, len)];
        in.readFully(b);
        yield new NbtByteArray(b);
      }
      case 8 -> readString(in);
      case 9 -> {
        int elemType = in.readUnsignedByte();
        int count = Math.max(0, in.readInt());
        List<Object> list = new ArrayList<>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) {
          list.add(elemType == 0 ? null : readPayload(in, elemType, depth + 1));
        }
        yield list;
      }
      case 10 -> readCompound(in, depth + 1);
      case 11 -> {
        int len = Math.max(0, in.readInt());
        int[] arr = new int[len];
        for (int i = 0; i < len; i++) arr[i] = in.readInt();
        yield new NbtIntArray(arr);
      }
      case 12 -> {
        int len = Math.max(0, in.readInt());
        long[] arr = new long[len];
        for (int i = 0; i < len; i++) arr[i] = in.readLong();
        yield arr;
      }
      default -> throw new IOException("Unsupported NBT tag type: " + type);
    };
  }

  private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
    if (depth > 64) throw new IOException("NBT nesting depth > 64");
    Map<String, Object> map = new LinkedHashMap<>();
    while (true) {
      int type = in.readUnsignedByte();
      if (type == 0) break;
      String key = readString(in);
      Object val = readPayload(in, type, depth);
      map.put(key, val);
    }
    return map;
  }

  private static String readString(DataInputStream in) throws IOException {
    int len = in.readUnsignedShort();
    byte[] bytes = new byte[len];
    in.readFully(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static void writeString(DataOutputStream out, String s) throws IOException {
    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
    out.writeShort(bytes.length);
    out.write(bytes);
  }

  @SuppressWarnings("unchecked")
  private static void writeCompound(DataOutputStream out, Map<String, Object> map)
      throws IOException {
    for (var e : map.entrySet()) {
      Object v = e.getValue();
      int type = tagTypeOf(v);
      out.writeByte(type);
      writeString(out, e.getKey());
      writePayload(out, type, v);
    }
    out.writeByte(0);
  }

  @SuppressWarnings("unchecked")
  private static void writePayload(DataOutputStream out, int type, Object v) throws IOException {
    switch (type) {
      case 1 -> out.writeByte(((Byte) v));
      case 2 -> out.writeShort(((Short) v));
      case 3 -> out.writeInt(((Integer) v));
      case 4 -> out.writeLong(((Long) v));
      case 5 -> out.writeFloat(((Float) v));
      case 6 -> out.writeDouble(((Double) v));
      case 7 -> {
        byte[] b = ((NbtByteArray) v).values();
        out.writeInt(b.length);
        out.write(b);
      }
      case 8 -> writeString(out, (String) v);
      case 9 -> {
        List<Object> list = (List<Object>) v;
        if (list.isEmpty()) {
          out.writeByte(0);
          out.writeInt(0);
        } else {
          int elemType = tagTypeOf(list.get(0));
          out.writeByte(elemType);
          out.writeInt(list.size());
          for (Object item : list) writePayload(out, elemType, item);
        }
      }
      case 10 -> writeCompound(out, (Map<String, Object>) v);
      case 11 -> {
        int[] a = ((NbtIntArray) v).values();
        out.writeInt(a.length);
        for (int i : a) out.writeInt(i);
      }
      case 12 -> {
        long[] a = (long[]) v;
        out.writeInt(a.length);
        for (long l : a) out.writeLong(l);
      }
      default -> throw new IOException("Cannot write NBT type: " + type);
    }
  }

  private static int tagTypeOf(Object v) {
    if (v instanceof Byte) return 1;
    if (v instanceof Short) return 2;
    if (v instanceof Integer) return 3;
    if (v instanceof Long) return 4;
    if (v instanceof Float) return 5;
    if (v instanceof Double) return 6;
    if (v instanceof NbtByteArray) return 7;
    if (v instanceof String) return 8;
    if (v instanceof List<?>) return 9;
    if (v instanceof Map<?, ?>) return 10;
    if (v instanceof NbtIntArray) return 11;
    if (v instanceof long[]) return 12;
    throw new IllegalArgumentException("Unsupported NBT value: " + (v == null ? "null" : v.getClass()));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asCompound(Object o) {
    return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
  }

  private static List<Map<String, Object>> asCompoundList(Object o) {
    if (!(o instanceof List<?> list)) return List.of();
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object el : list) {
      Map<String, Object> c = asCompound(el);
      if (c != null) out.add(c);
    }
    return out;
  }

  private static List<Integer> asIntList(Object o) {
    if (!(o instanceof List<?> list)) return List.of();
    List<Integer> out = new ArrayList<>();
    for (Object el : list) {
      if (el instanceof Number n) out.add(n.intValue());
    }
    return out;
  }

  private static int asInt(Object o, int def) {
    return o instanceof Number n ? n.intValue() : def;
  }

  private static String asString(Object o, String def) {
    return o instanceof String s ? s : def;
  }
}
