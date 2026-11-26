package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.*;

/**
 * Pure-Java blueprint planning: block-to-item mapping, BOM shortage diff against bag + WorldMemory
 * chests, PlacementIntent property filtering, and layer-ordered build queue. Zero
 * {@code net.minecraft.*} imports.
 */
public final class BlueprintPlanner {
  public record BuildStep(
      int wx,
      int wy,
      int wz,
      String blockId,
      String itemId,
      JsonObject placementState,
      boolean attached) {}

  public enum MatchStatus {
    MATCHED,
    STATE_MISMATCH,
    MISSING,
    BLOCKED_EXISTING
  }

  private record GridPos(int x, int y, int z) {}

  public record BomRow(String itemId, int need, int inBag, int inBaseChests, int missing) {
    public JsonObject toJson() {
      var o = new JsonObject();
      o.addProperty("item", itemId);
      o.addProperty("need", need);
      o.addProperty("in_bag", inBag);
      o.addProperty("in_base_chests", inBaseChests);
      o.addProperty("missing", missing);
      return o;
    }
  }

  private static final Set<String> UNSUPPORTED_DIRECT =
      Set.of(
          "minecraft:water",
          "minecraft:lava",
          "minecraft:fire",
          "minecraft:soul_fire",
          "minecraft:nether_portal",
          "minecraft:end_portal",
          "minecraft:end_gateway",
          "minecraft:piston_head",
          "minecraft:moving_piston",
          "minecraft:bubble_column");

  private static final Set<String> PLACEMENT_PROPS = Set.of("facing", "axis", "half");

  private BlueprintPlanner() {}

  public static boolean isUnsupportedDirect(String blockId, Map<String, String> props) {
    if (blockId == null || blockId.isBlank() || UNSUPPORTED_DIRECT.contains(blockId)) return true;
    if (props != null) {
      if ("upper".equals(props.get("half"))) return true;
      if ("head".equals(props.get("part"))) return true;
    }
    return false;
  }

  public static boolean isAttachedDecoration(String blockId) {
    if (blockId == null) return false;
    return blockId.contains("torch")
        || blockId.endsWith("_button")
        || blockId.equals("minecraft:lever")
        || blockId.endsWith("_pressure_plate")
        || blockId.endsWith("_sign")
        || blockId.endsWith("_hanging_sign")
        || blockId.endsWith("rail")
        || blockId.endsWith("_carpet")
        || blockId.equals("minecraft:ladder")
        || blockId.equals("minecraft:lantern")
        || blockId.equals("minecraft:soul_lantern");
  }

  public static String toPlaceItemId(String blockId, Map<String, String> props) {
    if (blockId == null || blockId.isBlank()) return "";
    if (blockId.equals("minecraft:wall_torch")) return "minecraft:torch";
    if (blockId.equals("minecraft:soul_wall_torch")) return "minecraft:soul_torch";
    if (blockId.equals("minecraft:redstone_wall_torch")) return "minecraft:redstone_torch";
    if (blockId.equals("minecraft:redstone_wire")) return "minecraft:redstone";
    if (blockId.endsWith("_wall_sign")) return blockId.replace("_wall_sign", "_sign");
    if (blockId.endsWith("_wall_hanging_sign"))
      return blockId.replace("_wall_hanging_sign", "_hanging_sign");
    return blockId;
  }

  public static int itemCountForVoxel(String blockId, Map<String, String> props) {
    if (props != null && "double".equals(props.get("type"))) return 2;
    return 1;
  }

  /**
   * Filters schematic state properties to only placement-intent attributes supported cleanly by
   * PlacementIntent (stripping runtime-derived states like waterlogged/powered/lit/shape/type=double).
   */
  public static JsonObject filterPlacementProperties(Map<String, String> props) {
    JsonObject out = new JsonObject();
    if (props == null || props.isEmpty()) return out;
    for (String key : PLACEMENT_PROPS) {
      String val = props.get(key);
      if (val != null && !val.isBlank()) {
        if (key.equals("half") && (val.equals("upper") || val.equals("lower"))) continue;
        out.addProperty(key, val);
      }
    }
    String type = props.get("type");
    if ("top".equals(type) || "bottom".equals(type)) {
      out.addProperty("type", type);
    }
    return out;
  }

  /**
   * True when the block cannot be placed by clicking the UP face of the block directly below it
   * (horizontal logs, top-half slabs/stairs).
   */
  public static boolean forbidsUpFace(BuildStep step) {
    if (step == null || step.placementState() == null) return false;
    JsonObject st = step.placementState();
    if (st.has("axis")) {
      String axis = st.get("axis").getAsString();
      if ("x".equals(axis) || "z".equals(axis)) return true;
    }
    String half =
        st.has("half")
            ? st.get("half").getAsString()
            : (st.has("type") ? st.get("type").getAsString() : "");
    return "top".equals(half);
  }

  /**
   * Returns which horizontal side ("north"|"south"|"east"|"west") of this target block the player
   * must stand on so their look direction produces the required block facing, or "" if
   * unconstrained.
   */
  public static String requiredStanceSide(BuildStep step) {
    if (step == null || step.placementState() == null || !step.placementState().has("facing"))
      return "";
    String facing = step.placementState().get("facing").getAsString();
    if (!Set.of("north", "south", "east", "west").contains(facing)) return "";
    String id = step.blockId() == null ? "" : step.blockId();
    // Stairs, doors, beds, and fence gates face AWAY from the player (same direction as look).
    boolean lookEqualsFacing =
        id.endsWith("_stairs")
            || id.endsWith("_door")
            || id.endsWith("_bed")
            || id.endsWith("_fence_gate")
            || id.contains("stairs")
            || id.contains("_gate");
    return lookEqualsFacing ? oppositeSide(facing) : facing;
  }

  private static String oppositeSide(String side) {
    return switch (side) {
      case "north" -> "south";
      case "south" -> "north";
      case "east" -> "west";
      case "west" -> "east";
      default -> "";
    };
  }

  /** Checks whether an existing block ID and properties match the planned placement attributes. */
  public static boolean stateMatches(
      String actualBlockId, Map<String, String> actualProps, BuildStep step) {
    if (step == null || actualBlockId == null || !actualBlockId.equals(step.blockId())) return false;
    JsonObject desired = step.placementState();
    if (desired == null || desired.size() == 0) return true;
    for (var entry : desired.entrySet()) {
      String actualVal = actualProps == null ? null : actualProps.get(entry.getKey());
      if (actualVal == null || !actualVal.equals(entry.getValue().getAsString())) return false;
    }
    return true;
  }

  public static MatchStatus classifyBlock(
      String actualBlockId, Map<String, String> actualProps, boolean replaceable, BuildStep step) {
    if (step != null && actualBlockId != null && actualBlockId.equals(step.blockId())) {
      return stateMatches(actualBlockId, actualProps, step)
          ? MatchStatus.MATCHED
          : MatchStatus.STATE_MISMATCH;
    }
    if (replaceable || BlueprintFormat.isAirBlock(actualBlockId)) return MatchStatus.MISSING;
    return MatchStatus.BLOCKED_EXISTING;
  }

  public static Map<String, Integer> billOfMaterials(BlueprintFormat.BlueprintSpec spec) {
    Map<String, Integer> need = new TreeMap<>();
    if (spec == null) return need;
    for (BlueprintFormat.Voxel v : spec.voxels) {
      if (isUnsupportedDirect(v.blockId(), v.properties())) continue;
      String item = toPlaceItemId(v.blockId(), v.properties());
      if (item.isBlank()) continue;
      need.merge(item, itemCountForVoxel(v.blockId(), v.properties()), Integer::sum);
    }
    return need;
  }

  public static List<BomRow> diffBom(
      BlueprintFormat.BlueprintSpec spec,
      Map<String, Integer> bagCounts,
      Map<String, Integer> baseChestCounts) {
    Map<String, Integer> needMap = billOfMaterials(spec);
    List<BomRow> rows = new ArrayList<>();
    for (var e : needMap.entrySet()) {
      String item = e.getKey();
      int need = e.getValue();
      int bag = bagCounts == null ? 0 : Math.max(0, bagCounts.getOrDefault(item, 0));
      int chest = baseChestCounts == null ? 0 : Math.max(0, baseChestCounts.getOrDefault(item, 0));
      int missing = Math.max(0, need - bag - chest);
      rows.add(new BomRow(item, need, bag, chest, missing));
    }
    return rows;
  }

  public static List<BuildStep> buildOrder(
      BlueprintFormat.BlueprintSpec spec,
      int anchorX,
      int anchorY,
      int anchorZ,
      int yMin,
      int yMax) {
    if (spec == null) return List.of();
    int loY = Math.max(0, yMin);
    int hiY = Math.min(spec.sizeY - 1, yMax);
    Map<Integer, List<BlueprintFormat.Voxel>> byLayer = new TreeMap<>();
    for (BlueprintFormat.Voxel v : spec.voxels) {
      if (v.y() < loY || v.y() > hiY) continue;
      if (isUnsupportedDirect(v.blockId(), v.properties())) continue;
      byLayer.computeIfAbsent(v.y(), k -> new ArrayList<>()).add(v);
    }

    Set<GridPos> scheduled = new HashSet<>();
    for (BlueprintFormat.Voxel v : spec.voxels) {
      if (v.y() < loY) scheduled.add(new GridPos(v.x(), v.y(), v.z()));
    }

    List<BuildStep> ordered = new ArrayList<>();
    int[] cursorXZ = new int[] {0, 0};
    for (var entry : byLayer.entrySet()) {
      List<BlueprintFormat.Voxel> wave1UpAllowed = new ArrayList<>();
      List<BlueprintFormat.Voxel> wave2SideOrOverhang = new ArrayList<>();
      List<BlueprintFormat.Voxel> wave3Attached = new ArrayList<>();
      for (BlueprintFormat.Voxel v : entry.getValue()) {
        if (isAttachedDecoration(v.blockId())) {
          wave3Attached.add(v);
        } else {
          JsonObject filtered = filterPlacementProperties(v.properties());
          BuildStep probe =
              new BuildStep(
                  anchorX + v.x(),
                  anchorY + v.y(),
                  anchorZ + v.z(),
                  v.blockId(),
                  toPlaceItemId(v.blockId(), v.properties()),
                  filtered,
                  false);
          boolean hasLower =
              v.y() <= 0 || v.y() <= loY || scheduled.contains(new GridPos(v.x(), v.y() - 1, v.z()));
          if (!forbidsUpFace(probe) && hasLower) {
            wave1UpAllowed.add(v);
          } else {
            wave2SideOrOverhang.add(v);
          }
        }
      }
      // Wave 1: bottom-supported UP-placeable solids/stairs -> Wave 2: side-face/top-half/overhangs -> Wave 3: attached
      appendSupportAwareSweep(
          wave1UpAllowed, loY, anchorX, anchorY, anchorZ, false, false, cursorXZ, scheduled, ordered);
      appendSupportAwareSweep(
          wave2SideOrOverhang, loY, anchorX, anchorY, anchorZ, false, true, cursorXZ, scheduled, ordered);
      appendSupportAwareSweep(
          wave3Attached, loY, anchorX, anchorY, anchorZ, true, false, cursorXZ, scheduled, ordered);
    }
    return ordered;
  }

  private static boolean hasSupport(
      BlueprintFormat.Voxel cand, int loY, Set<GridPos> scheduled, boolean checkForbidUp) {
    if (cand.y() <= 0 || cand.y() <= loY) return true;
    int x = cand.x(), y = cand.y(), z = cand.z();
    if (!checkForbidUp && scheduled.contains(new GridPos(x, y - 1, z))) return true;
    if (checkForbidUp) {
      JsonObject filtered = filterPlacementProperties(cand.properties());
      BuildStep probe =
          new BuildStep(x, y, z, cand.blockId(), cand.blockId(), filtered, false);
      if (!forbidsUpFace(probe) && scheduled.contains(new GridPos(x, y - 1, z))) return true;
    }
    return scheduled.contains(new GridPos(x + 1, y, z))
        || scheduled.contains(new GridPos(x - 1, y, z))
        || scheduled.contains(new GridPos(x, y, z + 1))
        || scheduled.contains(new GridPos(x, y, z - 1));
  }

  private static long packXZ(int x, int z) {
    return (((long) x) << 32) ^ (z & 0xffffffffL);
  }

  private static long bucketKey(int bx, int bz) {
    return (((long) bx) << 32) ^ (bz & 0xffffffffL);
  }

  private static int bucketLowerBound(int bx, int bz, int curX, int curZ) {
    int minX = bx << 3;
    int maxX = minX + 7;
    int minZ = bz << 3;
    int maxZ = minZ + 7;
    int dx = curX < minX ? minX - curX : (curX > maxX ? curX - maxX : 0);
    int dz = curZ < minZ ? minZ - curZ : (curZ > maxZ ? curZ - maxZ : 0);
    return dx + dz;
  }

  private static void appendSupportAwareSweep(
      List<BlueprintFormat.Voxel> pool,
      int loY,
      int anchorX,
      int anchorY,
      int anchorZ,
      boolean attached,
      boolean checkForbidUp,
      int[] cursorXZ,
      Set<GridPos> scheduled,
      List<BuildStep> out) {
    if (pool.isEmpty()) return;
    List<BlueprintFormat.Voxel> sorted = new ArrayList<>(pool);
    sorted.sort(
        Comparator.comparingInt(BlueprintFormat.Voxel::y)
            .thenComparingInt(BlueprintFormat.Voxel::z)
            .thenComparingInt(BlueprintFormat.Voxel::x));
    int n = sorted.size();
    boolean[] removed = new boolean[n];
    boolean[] isReady = new boolean[n];
    Map<Long, List<Integer>> byXZ = new HashMap<>(n * 2);
    Map<Long, List<Integer>> readyBuckets = new HashMap<>();
    Map<Long, List<Integer>> fallbackBuckets = new HashMap<>();
    for (int i = 0; i < n; i++) {
      BlueprintFormat.Voxel v = sorted.get(i);
      byXZ.computeIfAbsent(packXZ(v.x(), v.z()), k -> new ArrayList<>(1)).add(i);
      long bKey = bucketKey(v.x() >> 3, v.z() >> 3);
      if (hasSupport(v, loY, scheduled, checkForbidUp)) {
        isReady[i] = true;
        readyBuckets.computeIfAbsent(bKey, k -> new ArrayList<>()).add(i);
      } else {
        fallbackBuckets.computeIfAbsent(bKey, k -> new ArrayList<>()).add(i);
      }
    }

    int curX = cursorXZ[0], curZ = cursorXZ[1];
    int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    for (int placedCount = 0; placedCount < n; placedCount++) {
      Map<Long, List<Integer>> active = !readyBuckets.isEmpty() ? readyBuckets : fallbackBuckets;
      List<Map.Entry<Long, List<Integer>>> entries = new ArrayList<>(active.entrySet());
      int qx = curX, qz = curZ;
      entries.sort(
          Comparator.comparingInt(
              e -> {
                long bk = e.getKey();
                return bucketLowerBound((int) (bk >> 32), (int) (long) bk, qx, qz);
              }));

      int bestIdx = -1;
      int bestDist = Integer.MAX_VALUE;
      long bestBucketKey = 0;
      for (var be : entries) {
        long bk = be.getKey();
        int lb = bucketLowerBound((int) (bk >> 32), (int) (long) bk, curX, curZ);
        if (lb > bestDist) break;
        List<Integer> list = be.getValue();
        for (int idx : list) {
          if (removed[idx]) continue;
          BlueprintFormat.Voxel cand = sorted.get(idx);
          int d = Math.abs(cand.x() - curX) + Math.abs(cand.z() - curZ);
          if (bestIdx < 0 || d < bestDist || (d == bestDist && idx < bestIdx)) {
            bestIdx = idx;
            bestDist = d;
            bestBucketKey = bk;
          }
        }
      }

      removed[bestIdx] = true;
      List<Integer> owner = active.get(bestBucketKey);
      owner.remove(Integer.valueOf(bestIdx));
      if (owner.isEmpty()) active.remove(bestBucketKey);

      BlueprintFormat.Voxel chosen = sorted.get(bestIdx);
      curX = chosen.x();
      curZ = chosen.z();
      scheduled.add(new GridPos(chosen.x(), chosen.y(), chosen.z()));
      out.add(
          new BuildStep(
              anchorX + chosen.x(),
              anchorY + chosen.y(),
              anchorZ + chosen.z(),
              chosen.blockId(),
              toPlaceItemId(chosen.blockId(), chosen.properties()),
              filterPlacementProperties(chosen.properties()),
              attached));

      // Incrementally promote newly supported horizontal neighbors
      for (int[] dir : dirs) {
        List<Integer> nbs = byXZ.get(packXZ(chosen.x() + dir[0], chosen.z() + dir[1]));
        if (nbs == null) continue;
        for (int nb : nbs) {
          if (removed[nb] || isReady[nb]) continue;
          BlueprintFormat.Voxel nv = sorted.get(nb);
          if (nv.y() == chosen.y()) {
            isReady[nb] = true;
            long nbBucket = bucketKey(nv.x() >> 3, nv.z() >> 3);
            List<Integer> fb = fallbackBuckets.get(nbBucket);
            if (fb != null) {
              fb.remove(Integer.valueOf(nb));
              if (fb.isEmpty()) fallbackBuckets.remove(nbBucket);
            }
            readyBuckets.computeIfAbsent(nbBucket, k -> new ArrayList<>()).add(nb);
          }
        }
      }
    }
    cursorXZ[0] = curX;
    cursorXZ[1] = curZ;
  }
}
