package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class BlueprintFormatTest {
  @Test
  void parsesVanillaCreateStructureNbtAndRotatesCleanly() throws Exception {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("DataVersion", 3465);
    root.put("size", List.of(3, 2, 2));
    root.put(
        "palette",
        List.of(
            Map.of("Name", "minecraft:air"),
            Map.of("Name", "minecraft:cobblestone"),
            Map.of(
                "Name",
                "minecraft:oak_stairs",
                "Properties",
                Map.of("facing", "north", "half", "bottom"))));
    root.put(
        "blocks",
        List.of(
            Map.of("pos", List.of(0, 0, 0), "state", 1),
            Map.of("pos", List.of(2, 0, 0), "state", 1),
            Map.of("pos", List.of(1, 1, 0), "state", 2)));
    byte[] bytes = BlueprintFormat.writeGzipNbt("", root);

    var spec = BlueprintFormat.parse("demo.nbt", bytes);
    assertEquals("nbt", spec.format);
    assertEquals(3, spec.sizeX);
    assertEquals(2, spec.sizeY);
    assertEquals(2, spec.sizeZ);
    assertEquals(3, spec.voxels.size());

    var rotated = spec.transform(90, "none");
    assertEquals(2, rotated.sizeX);
    assertEquals(3, rotated.sizeZ);
    var stairs = rotated.voxels.stream().filter(v -> v.blockId().equals("minecraft:oak_stairs")).findFirst().orElseThrow();
    assertEquals("east", stairs.properties().get("facing"));
    assertEquals(1, stairs.x());
    assertEquals(1, stairs.z());
  }

  @Test
  void parsesLitematicWithNegativeSizeAndCrossLongPackedBits() throws Exception {
    // 5 palette entries -> 3 bits/entry, crosses 64-bit word boundary frequently
    List<Map<String, Object>> palette =
        List.of(
            Map.of("Name", "minecraft:air"),
            Map.of("Name", "minecraft:stone"),
            Map.of("Name", "minecraft:oak_planks"),
            Map.of("Name", "minecraft:glass"),
            Map.of("Name", "minecraft:chest"));
    int sx = 5, sy = 2, sz = 3; // 30 blocks -> 90 bits -> 2 longs
    int[] indices = new int[sx * sy * sz];
    indices[0] = 1; // (0,0,0)
    indices[21] = 2; // near bit 63 boundary: index 21*3=63..65 spans long[0] and long[1]!
    indices[29] = 4; // corner
    long[] packed = packLitematicaBits(indices, 3);

    Map<String, Object> region = new LinkedHashMap<>();
    region.put("Position", Map.of("x", 10, "y", 64, "z", -20));
    region.put("Size", Map.of("x", -sx, "y", sy, "z", -sz));
    region.put("BlockStatePalette", palette);
    region.put("BlockStates", packed);

    Map<String, Object> root = new LinkedHashMap<>();
    root.put("Metadata", Map.of("Name", "cross_boundary_test"));
    root.put("Regions", Map.of("Main", region));

    byte[] bytes = BlueprintFormat.writeGzipNbt("", root);
    var spec = BlueprintFormat.parse("tower.litematic", bytes);
    assertEquals("litematic", spec.format);
    assertEquals("cross_boundary_test", spec.name);
    assertEquals(5, spec.sizeX);
    assertEquals(2, spec.sizeY);
    assertEquals(3, spec.sizeZ);
    assertEquals(3, spec.voxels.size());
    assertEquals(1, spec.blockCounts.get("minecraft:stone"));
    assertEquals(1, spec.blockCounts.get("minecraft:oak_planks"));
    assertEquals(1, spec.blockCounts.get("minecraft:chest"));
  }

  private static long[] packLitematicaBits(int[] values, int bits) {
    int totalBits = values.length * bits;
    long[] out = new long[(totalBits + 63) / 64];
    long mask = (1L << bits) - 1L;
    for (int i = 0; i < values.length; i++) {
      long v = values[i] & mask;
      long start = (long) i * bits;
      int w0 = (int) (start >>> 6);
      int w1 = (int) (((long) (i + 1) * bits - 1) >>> 6);
      int off = (int) (start & 63L);
      out[w0] |= (v << off);
      if (w1 != w0) {
        out[w1] |= (v >>> (64 - off));
      }
    }
    return out;
  }
}
