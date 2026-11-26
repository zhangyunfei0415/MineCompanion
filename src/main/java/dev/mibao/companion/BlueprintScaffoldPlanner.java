package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.Predicate;

/**
 * Pure-Java 3D scaffolding & temporary-support planner for PRO-level blueprint replication and
 * standalone agent building primitives. Zero {@code net.minecraft.*} imports so all algorithms are
 * offline JUnit testable.
 */
public final class BlueprintScaffoldPlanner {
  public enum Mode {
    AUTO,
    SCAFFOLDING,
    SOLID_RAMP,
    PILLAR,
    NONE
  }

  public enum Kind {
    SCAFFOLD_TOWER,
    SCAFFOLD_OVERHANG,
    RAMP_SUPPORT,
    RAMP_STEP,
    CATWALK,
    PILLAR,
    TEMP_SUPPORT
  }

  public record ScaffoldCell(int wx, int wy, int wz, Kind kind) {}

  public record MaterialChoice(
      Mode resolvedMode, String blockId, int availableSurplus, int estimatedNeed, String error) {}

  public record ValidationResult(boolean ok, String reason) {}

  public static final List<String> CHEAP_SOLID_BLOCKS =
      List.of(
          "minecraft:dirt",
          "minecraft:cobblestone",
          "minecraft:cobbled_deepslate",
          "minecraft:netherrack",
          "minecraft:coarse_dirt",
          "minecraft:rooted_dirt");

  public static final Set<String> FORBIDDEN_SCAFFOLD_BLOCKS =
      Set.of(
          "minecraft:air",
          "minecraft:cave_air",
          "minecraft:void_air",
          "minecraft:sand",
          "minecraft:red_sand",
          "minecraft:gravel",
          "minecraft:tnt",
          "minecraft:lava",
          "minecraft:water",
          "minecraft:bedrock",
          "minecraft:magma_block",
          "minecraft:cactus",
          "minecraft:fire",
          "minecraft:soul_fire",
          "minecraft:powder_snow");

  private BlueprintScaffoldPlanner() {}

  public static String posKey(int x, int y, int z) {
    return x + "," + y + "," + z;
  }

  public static Mode parseMode(String raw) {
    if (raw == null || raw.isBlank()) return Mode.AUTO;
    return switch (raw.strip().toLowerCase(Locale.ROOT)) {
      case "auto" -> Mode.AUTO;
      case "scaffolding", "vanilla_scaffolding" -> Mode.SCAFFOLDING;
      case "solid_ramp", "ramp", "solid", "blocks", "blocks_only" -> Mode.SOLID_RAMP;
      case "pillar", "pillar_up" -> Mode.PILLAR;
      case "none", "off", "disabled" -> Mode.NONE;
      default -> throw new IllegalArgumentException(
          "[INVALID_ARGUMENT] scaffold_mode须为 auto/scaffolding/solid_ramp/pillar/none");
    };
  }

  /**
   * Selects scaffold mode and material using ONLY bag surplus above remaining blueprint BOM needs
   * unless {@code explicitBlock} was explicitly requested by the agent/user.
   */
  public static MaterialChoice selectMaterial(
      Mode requestedMode,
      String explicitBlock,
      Map<String, Integer> bagCounts,
      Map<String, Integer> remainingBomNeed,
      int heightNeeded,
      int estimatedCells) {
    Mode req = requestedMode == null ? Mode.AUTO : requestedMode;
    if (req == Mode.NONE) {
      return new MaterialChoice(Mode.NONE, "", 0, 0, "");
    }

    int h = Math.max(1, heightNeeded);
    int rampNeed = Math.max(2, estimatedCells > 0 ? estimatedCells : h * 2 + 2);
    int towerNeed = Math.max(1, h + 2);
    String explicit = explicitBlock == null ? "" : explicitBlock.strip().toLowerCase(Locale.ROOT);

    if (!explicit.isBlank()) {
      if (FORBIDDEN_SCAFFOLD_BLOCKS.contains(explicit)) {
        return new MaterialChoice(
            req,
            "",
            0,
            rampNeed,
            "[INVALID_ARGUMENT] 禁止使用高危或重力掉落方块作为脚手架：" + explicit);
      }
      int bag = bagCounts == null ? 0 : Math.max(0, bagCounts.getOrDefault(explicit, 0));
      Mode resolved =
          req == Mode.AUTO
              ? ("minecraft:scaffolding".equals(explicit)
                  ? Mode.SCAFFOLDING
                  : (h <= 3 && bag < rampNeed ? Mode.PILLAR : Mode.SOLID_RAMP))
              : req;
      int need = resolved == Mode.SCAFFOLDING ? towerNeed : (resolved == Mode.PILLAR ? h : rampNeed);
      if (bag < Math.min(need, 1)) {
        return new MaterialChoice(
            resolved,
            explicit,
            bag,
            need,
            "[NEED_SCAFFOLD] 指定脚手架材料 " + explicit + " 不足（背包 " + bag + "，建议至少 " + need + "）");
      }
      return new MaterialChoice(resolved, explicit, bag, need, "");
    }

    int scaffSurplus = surplus("minecraft:scaffolding", bagCounts, remainingBomNeed);
    if (req == Mode.SCAFFOLDING) {
      if (scaffSurplus >= 1) {
        return new MaterialChoice(Mode.SCAFFOLDING, "minecraft:scaffolding", scaffSurplus, towerNeed, "");
      }
      return new MaterialChoice(
          Mode.SCAFFOLDING,
          "minecraft:scaffolding",
          scaffSurplus,
          towerNeed,
          "[NEED_SCAFFOLD] 背包缺少可用 minecraft:scaffolding（富余 "
              + scaffSurplus
              + "，建至该层需约 "
              + towerNeed
              + " 个）");
    }

    if (req == Mode.AUTO && scaffSurplus >= Math.min(4, towerNeed)) {
      return new MaterialChoice(Mode.SCAFFOLDING, "minecraft:scaffolding", scaffSurplus, towerNeed, "");
    }

    String bestSolid = "";
    int bestSurplus = 0;
    for (String candidate : CHEAP_SOLID_BLOCKS) {
      int sup = surplus(candidate, bagCounts, remainingBomNeed);
      if (sup > bestSurplus) {
        bestSurplus = sup;
        bestSolid = candidate;
      }
    }

    if (req == Mode.PILLAR) {
      if (bestSurplus >= Math.min(1, h)) {
        return new MaterialChoice(Mode.PILLAR, bestSolid, bestSurplus, h, "");
      }
      return new MaterialChoice(
          Mode.PILLAR,
          "",
          bestSurplus,
          h,
          "[NEED_SCAFFOLD] 背包缺少非蓝图占用廉价实心垫块（dirt/cobblestone/netherrack 等富余 "
              + bestSurplus
              + "）");
    }

    if (req == Mode.SOLID_RAMP) {
      if (bestSurplus >= 2) {
        return new MaterialChoice(Mode.SOLID_RAMP, bestSolid, bestSurplus, rampNeed, "");
      }
      return new MaterialChoice(
          Mode.SOLID_RAMP,
          "",
          bestSurplus,
          rampNeed,
          "[NEED_SCAFFOLD] 背包缺少非蓝图占用廉价实心块（dirt/cobblestone 等富余 "
              + bestSurplus
              + "，搭梯约需 "
              + rampNeed
              + " 块）");
    }

    // AUTO fallback: solid ramp if >= 2 cheap blocks, else pillar if <= 3 blocks high, else error
    if (bestSurplus >= 2 && (bestSurplus >= rampNeed || h > 3 || bestSurplus >= 4)) {
      return new MaterialChoice(Mode.SOLID_RAMP, bestSolid, bestSurplus, rampNeed, "");
    }
    if (h <= 3 && bestSurplus >= h) {
      return new MaterialChoice(Mode.PILLAR, bestSolid, bestSurplus, h, "");
    }
    if (bestSurplus >= 1) {
      return new MaterialChoice(Mode.SOLID_RAMP, bestSolid, bestSurplus, rampNeed, "");
    }
    return new MaterialChoice(
        Mode.AUTO,
        "",
        0,
        rampNeed,
        "[NEED_SCAFFOLD] 背包缺少非蓝图占用的脚手架或廉价垫块（scaffolding 需约 "
            + towerNeed
            + "，或 dirt/cobblestone 需约 "
            + rampNeed
            + "）");
  }

  private static int surplus(
      String itemId, Map<String, Integer> bagCounts, Map<String, Integer> remainingBomNeed) {
    int bag = bagCounts == null ? 0 : Math.max(0, bagCounts.getOrDefault(itemId, 0));
    int reserved =
        remainingBomNeed == null ? 0 : Math.max(0, remainingBomNeed.getOrDefault(itemId, 0));
    return Math.max(0, bag - reserved);
  }

  /**
   * Plans a hollow perimeter stepped ramp (strictly 2 blocks per vertical rise step + 1-block flat
   * corner landings) along the outer margin-2 corridor of the blueprint bounding box, plus a
   * catwalk spur along {@code preferredSide} at {@code targetStandY - 1}.
   *
   * <p>Every placed cell connects cleanly back to the ground along the list order, so reverse
   * iteration provides a mathematically guaranteed crash-free LIFO teardown path.
   */
  public static List<ScaffoldCell> planHollowPerimeterRamp(
      int minX,
      int maxX,
      int minZ,
      int maxZ,
      int groundY,
      int targetStandY,
      String preferredSide,
      Set<String> occupiedKeys) {
    return planHollowPerimeterRampInternal(
        minX,
        maxX,
        minZ,
        maxZ,
        groundY,
        targetStandY,
        preferredSide,
        (minX + maxX) / 2,
        (minZ + maxZ) / 2,
        false,
        occupiedKeys);
  }

  public static List<ScaffoldCell> planHollowPerimeterRamp(
      int minX,
      int maxX,
      int minZ,
      int maxZ,
      int groundY,
      int targetStandY,
      String preferredSide,
      int targetX,
      int targetZ,
      Set<String> occupiedKeys) {
    return planHollowPerimeterRampInternal(
        minX, maxX, minZ, maxZ, groundY, targetStandY, preferredSide, targetX, targetZ, true, occupiedKeys);
  }

  private static List<ScaffoldCell> planHollowPerimeterRampInternal(
      int minX,
      int maxX,
      int minZ,
      int maxZ,
      int groundY,
      int targetStandY,
      String preferredSide,
      int targetX,
      int targetZ,
      boolean extendInward,
      Set<String> occupiedKeys) {
    List<ScaffoldCell> out = new ArrayList<>();
    int rise = targetStandY - groundY;
    if (rise <= 0) return out;

    Set<String> used = new HashSet<>();
    if (occupiedKeys != null) used.addAll(occupiedKeys);

    int ringMinX = Math.min(minX, maxX) - 2;
    int ringMaxX = Math.max(minX, maxX) + 2;
    int ringMinZ = Math.min(minZ, maxZ) - 2;
    int ringMaxZ = Math.max(minZ, maxZ) + 2;

    List<int[]> loop = perimeterLoop(ringMinX, ringMaxX, ringMinZ, ringMaxZ, preferredSide);
    if (loop.isEmpty()) return out;

    int curY = groundY; // height of step being placed (groundY is first block at feet+0 -> feet end up at targetStandY)
    int idx = 0;
    int safety = 0;
    while (curY <= targetStandY - 1 && safety++ < 256) {
      int[] xy = loop.get(idx % loop.size());
      int[] nextXy = loop.get((idx + 1) % loop.size());
      boolean cornerNext = isCorner(ringMinX, ringMaxX, ringMinZ, ringMaxZ, xy[0], xy[1]);

      if (curY > groundY) {
        addIfFree(out, used, xy[0], curY - 1, xy[1], Kind.RAMP_SUPPORT);
      }
      addIfFree(out, used, xy[0], curY, xy[1], Kind.RAMP_STEP);

      // Insert a flat 1-block landing on corners before stepping up again to keep Navigator turn safe
      if (cornerNext && curY < targetStandY - 1) {
        addIfFree(out, used, nextXy[0], curY - 1, nextXy[1], Kind.RAMP_SUPPORT);
        addIfFree(out, used, nextXy[0], curY, nextXy[1], Kind.CATWALK);
        idx++;
      }
      curY++;
      idx++;
    }

    // Add a short horizontal catwalk spur along the perimeter, then step inward toward interior target when wide
    int catY = targetStandY - 1;
    int[] topEnd = loop.get(Math.max(0, idx - 1) % loop.size());
    int tipX = topEnd[0];
    int tipZ = topEnd[1];
    int spanLimit = 4;
    for (int i = 0; i < Math.min(spanLimit, loop.size()); i++) {
      int[] c = loop.get((idx + i) % loop.size());
      if (i == 0 && c[0] == topEnd[0] && c[1] == topEnd[1]) continue;
      if (addIfFree(out, used, c[0], catY, c[1], Kind.CATWALK)) {
        tipX = c[0];
        tipZ = c[1];
      }
    }

    if (extendInward) {
      int spurSteps = 0;
      while (spurSteps++ < 10 && (Math.abs(targetX - tipX) + Math.abs(targetZ - tipZ)) > 3) {
        int dx = Integer.compare(targetX, tipX);
        int dz = Integer.compare(targetZ, tipZ);
        boolean preferX = Math.abs(targetX - tipX) >= Math.abs(targetZ - tipZ);
        int nx1 = tipX + (preferX ? dx : 0);
        int nz1 = tipZ + (preferX ? 0 : dz);
        int nx2 = tipX + (preferX ? 0 : dx);
        int nz2 = tipZ + (preferX ? dz : 0);
        if ((nx1 != tipX || nz1 != tipZ) && addIfFree(out, used, nx1, catY, nz1, Kind.CATWALK)) {
          tipX = nx1;
          tipZ = nz1;
        } else if ((nx2 != tipX || nz2 != tipZ)
            && addIfFree(out, used, nx2, catY, nz2, Kind.CATWALK)) {
          tipX = nx2;
          tipZ = nz2;
        } else {
          break;
        }
      }
    }
    return out;
  }

  private static boolean addIfFree(
      List<ScaffoldCell> out, Set<String> used, int x, int y, int z, Kind kind) {
    String key = posKey(x, y, z);
    if (used.add(key)) {
      out.add(new ScaffoldCell(x, y, z, kind));
      return true;
    }
    return false;
  }

  private static boolean isCorner(int minX, int maxX, int minZ, int maxZ, int x, int z) {
    return (x == minX || x == maxX) && (z == minZ || z == maxZ);
  }

  private static List<int[]> perimeterLoop(
      int minX, int maxX, int minZ, int maxZ, String preferredSide) {
    List<int[]> ring = new ArrayList<>();
    for (int x = minX; x <= maxX; x++) ring.add(new int[] {x, minZ});
    for (int z = minZ + 1; z <= maxZ; z++) ring.add(new int[] {maxX, z});
    for (int x = maxX - 1; x >= minX; x--) ring.add(new int[] {x, maxZ});
    for (int z = maxZ - 1; z >= minZ + 1; z--) ring.add(new int[] {minX, z});
    if (ring.isEmpty()) return ring;

    String side = preferredSide == null ? "" : preferredSide.strip().toLowerCase(Locale.ROOT);
    int startIdx = 0;
    for (int i = 0; i < ring.size(); i++) {
      int[] p = ring.get(i);
      boolean match =
          switch (side) {
            case "south" -> p[1] == maxZ && p[0] == (minX + maxX) / 2;
            case "east" -> p[0] == maxX && p[1] == (minZ + maxZ) / 2;
            case "west" -> p[0] == minX && p[1] == (minZ + maxZ) / 2;
            default -> p[1] == minZ && p[0] == (minX + maxX) / 2;
          };
      if (match) {
        startIdx = i;
        break;
      }
    }
    List<int[]> rotated = new ArrayList<>(ring.size());
    for (int i = 0; i < ring.size(); i++) {
      rotated.add(ring.get((startIdx + i) % ring.size()));
    }
    return rotated;
  }

  /**
   * Plans a vanilla {@code minecraft:scaffolding} vertical climb shaft plus a bounded horizontal
   * bridge (max 5 blocks cantilever so vanilla scaffolding does not fall).
   */
  public static List<ScaffoldCell> planScaffoldingTower(
      int baseX,
      int groundY,
      int baseZ,
      int targetStandY,
      int overhangDx,
      int overhangDz,
      int overhangLen,
      Set<String> occupiedKeys) {
    List<ScaffoldCell> out = new ArrayList<>();
    int rise = targetStandY - groundY;
    if (rise <= 0) return out;
    Set<String> used = occupiedKeys == null ? new HashSet<>() : new HashSet<>(occupiedKeys);
    for (int y = groundY; y <= targetStandY - 1; y++) {
      addIfFree(out, used, baseX, y, baseZ, Kind.SCAFFOLD_TOWER);
    }
    int len = Math.max(0, Math.min(5, overhangLen));
    int stepX = Integer.signum(overhangDx);
    int stepZ = stepX != 0 ? 0 : Integer.signum(overhangDz);
    if (stepX != 0 || stepZ != 0) {
      int topY = targetStandY - 1;
      for (int i = 1; i <= len; i++) {
        addIfFree(
            out, used, baseX + stepX * i, topY, baseZ + stepZ * i, Kind.SCAFFOLD_OVERHANG);
      }
    }
    return out;
  }

  /**
   * Picks a single temporary auxiliary support position next to {@code step} outside the
   * blueprint's own solid blocks so side-dependent blocks (axis=x/z logs, top slabs, unsupported
   * overhangs) gain a valid placement face.
   */
  public static ScaffoldCell pickTempSupportPos(
      BlueprintPlanner.BuildStep step,
      Set<String> blueprintSolidKeys,
      Predicate<int[]> isAirAndLoaded,
      Predicate<int[]> hasOwnSupport) {
    if (step == null) return null;
    List<int[]> candidates = new ArrayList<>();
    JsonObject st = step.placementState();
    String axis = st != null && st.has("axis") ? st.get("axis").getAsString() : "";
    if ("x".equals(axis)) {
      candidates.add(new int[] {step.wx() - 1, step.wy(), step.wz()});
      candidates.add(new int[] {step.wx() + 1, step.wy(), step.wz()});
    } else if ("z".equals(axis)) {
      candidates.add(new int[] {step.wx(), step.wy(), step.wz() - 1});
      candidates.add(new int[] {step.wx(), step.wy(), step.wz() + 1});
    } else {
      candidates.add(new int[] {step.wx() - 1, step.wy(), step.wz()});
      candidates.add(new int[] {step.wx() + 1, step.wy(), step.wz()});
      candidates.add(new int[] {step.wx(), step.wy(), step.wz() - 1});
      candidates.add(new int[] {step.wx(), step.wy(), step.wz() + 1});
      if (!BlueprintPlanner.forbidsUpFace(step)) {
        candidates.add(new int[] {step.wx(), step.wy() - 1, step.wz()});
      }
    }
    for (int[] c : candidates) {
      if (blueprintSolidKeys != null && blueprintSolidKeys.contains(posKey(c[0], c[1], c[2]))) {
        continue;
      }
      if ((isAirAndLoaded == null || isAirAndLoaded.test(c))
          && (hasOwnSupport == null || hasOwnSupport.test(c))) {
        return new ScaffoldCell(c[0], c[1], c[2], Kind.TEMP_SUPPORT);
      }
    }
    return null;
  }

  /** Computes transparent scaffold requirements included in inspect/dry-run outputs. */
  public static JsonObject estimateForBlueprint(BlueprintFormat.BlueprintSpec spec) {
    var o = new JsonObject();
    if (spec == null || spec.voxels.isEmpty()) {
      o.addProperty("max_relative_y", 0);
      o.addProperty("needs_scaffold", false);
      o.addProperty("recommended_mode", "none");
      o.addProperty("recommended_scaffolding", 0);
      o.addProperty("recommended_solid_blocks", 0);
      return o;
    }
    int maxY = 0;
    boolean hasElevatedSideState = false;
    for (var v : spec.voxels) {
      if (v.y() > maxY) maxY = v.y();
      if (v.y() >= 2 && v.properties() != null && !v.properties().isEmpty()) {
        hasElevatedSideState = true;
      }
    }
    boolean needs = maxY >= 3 || (maxY >= 2 && hasElevatedSideState);
    int standHeight = Math.max(0, maxY - 1);
    int recScaff = needs ? standHeight + 4 : 0;
    int recSolid = needs ? Math.max(3, standHeight * 2 + 4) : 0;
    o.addProperty("max_relative_y", maxY);
    o.addProperty("needs_scaffold", needs);
    o.addProperty("recommended_mode", needs ? "auto (scaffolding / dirt / cobblestone)" : "none");
    o.addProperty("recommended_scaffolding", recScaff);
    o.addProperty("recommended_solid_blocks", recSolid);
    return o;
  }

  /**
   * Simulates reverse (LIFO) removal of {@code plan} and verifies no duplicate cells, no permanent
   * collision, and that every remaining cell stays connected to ground ({@code minY}) or {@code
   * permanentKeys} after each step.
   */
  public static ValidationResult validateLifoReversible(
      List<ScaffoldCell> plan, Set<String> permanentKeys) {
    if (plan == null || plan.isEmpty()) {
      return new ValidationResult(true, "");
    }
    Set<String> permanent = permanentKeys == null ? Set.of() : permanentKeys;
    Set<String> seen = new HashSet<>();
    int minY = Integer.MAX_VALUE;
    Set<String> towerCols = new HashSet<>();
    for (ScaffoldCell c : plan) {
      String k = posKey(c.wx(), c.wy(), c.wz());
      if (permanent.contains(k)) {
        return new ValidationResult(false, "collides with permanent block at " + k);
      }
      if (!seen.add(k)) {
        return new ValidationResult(false, "duplicate cell at " + k);
      }
      if (c.wy() < minY) minY = c.wy();
      if (c.kind() == Kind.SCAFFOLD_TOWER) {
        towerCols.add(c.wx() + "," + c.wz());
      }
    }

    int[][] dirs6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    for (int limit = plan.size(); limit >= 1; limit--) {
      Map<String, ScaffoldCell> present = new HashMap<>();
      ArrayDeque<ScaffoldCell> bfs = new ArrayDeque<>();
      Set<String> visited = new HashSet<>();
      for (int i = 0; i < limit; i++) {
        ScaffoldCell c = plan.get(i);
        present.put(posKey(c.wx(), c.wy(), c.wz()), c);
      }
      for (int i = 0; i < limit; i++) {
        ScaffoldCell c = plan.get(i);
        String k = posKey(c.wx(), c.wy(), c.wz());
        boolean grounded =
            c.wy() == minY || permanent.contains(posKey(c.wx(), c.wy() - 1, c.wz()));
        if (!grounded) {
          for (int[] d : dirs6) {
            if (permanent.contains(posKey(c.wx() + d[0], c.wy() + d[1], c.wz() + d[2]))) {
              grounded = true;
              break;
            }
          }
        }
        if (grounded && visited.add(k)) bfs.add(c);
      }
      while (!bfs.isEmpty()) {
        ScaffoldCell cur = bfs.removeFirst();
        for (int[] d : dirs6) {
          String nk = posKey(cur.wx() + d[0], cur.wy() + d[1], cur.wz() + d[2]);
          if (present.containsKey(nk) && visited.add(nk)) {
            bfs.add(present.get(nk));
          }
        }
      }
      if (visited.size() != present.size()) {
        ScaffoldCell removed = limit < plan.size() ? plan.get(limit) : plan.get(0);
        return new ValidationResult(
            false,
            "LIFO step leaves unsupported cells after removing "
                + posKey(removed.wx(), removed.wy(), removed.wz()));
      }
    }
    return new ValidationResult(true, "");
  }

  public record Pos(int x, int y, int z) {}

  public record WorkPose(Pos stand, List<Pos> coveredTargets) {}

  public record ClusterPlan(
      List<ScaffoldCell> buildOrder,
      List<WorkPose> poses,
      List<Pos> uncoveredTargets,
      boolean usesRelayPillar,
      boolean validLifo) {}

  public interface WorldView {
    boolean isLoaded(int x, int y, int z);

    boolean isFreeAir(int x, int y, int z);

    boolean isWalkableStand(int x, int y, int z);

    boolean hasSturdyTopFace(int x, int y, int z);

    static WorldView flatGround(int groundY, Set<String> blocked) {
      return new WorldView() {
        @Override
        public boolean isLoaded(int x, int y, int z) {
          return true;
        }

        @Override
        public boolean isFreeAir(int x, int y, int z) {
          return y > groundY && (blocked == null || !blocked.contains(posKey(x, y, z)));
        }

        @Override
        public boolean isWalkableStand(int x, int y, int z) {
          return hasSturdyTopFace(x, y - 1, z) && isFreeAir(x, y, z) && isFreeAir(x, y + 1, z);
        }

        @Override
        public boolean hasSturdyTopFace(int x, int y, int z) {
          return y == groundY && (blocked == null || !blocked.contains(posKey(x, y, z)));
        }
      };
    }
  }

  /**
   * Exact Forge 1.20.1 {@code ScaffoldingBlock.getDistance} 0-1 BFS. Distance 0 seeds sit atop
   * non-scaffolding blocks with sturdy top faces; vertical steps UP add 0 distance, horizontal steps
   * add +1 distance. Any scaffolding unreachable with distance &le; 6 gets distance 7 (falls).
   */
  public static Map<String, Integer> computeVanillaScaffoldDistances(
      Set<String> scaffoldPositions, Predicate<int[]> hasSturdyTopFaceBelow) {
    if (scaffoldPositions == null || scaffoldPositions.isEmpty()) return Map.of();
    var coordsByKey = new HashMap<String, int[]>(scaffoldPositions.size() * 2);
    for (String key : scaffoldPositions) {
      coordsByKey.put(key, parseKey(key));
    }

    var dist = new HashMap<String, Integer>(scaffoldPositions.size() * 2);
    var deque = new ArrayDeque<String>();

    // Seed all distance-0 scaffolding directly supported by a sturdy non-scaffolding block below.
    for (var entry : coordsByKey.entrySet()) {
      String key = entry.getKey();
      int[] p = entry.getValue();
      String belowKey = posKey(p[0], p[1] - 1, p[2]);
      if (!coordsByKey.containsKey(belowKey)
          && hasSturdyTopFaceBelow.test(new int[] {p[0], p[1] - 1, p[2]})) {
        dist.put(key, 0);
        deque.addFirst(key);
      }
    }

    int[][] horiz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    while (!deque.isEmpty()) {
      String curKey = deque.removeFirst();
      int curDist = dist.get(curKey);
      if (curDist >= 7) continue;
      int[] p = coordsByKey.get(curKey);

      // Weight-0 edge straight up within the scaffolding column.
      String upKey = posKey(p[0], p[1] + 1, p[2]);
      if (coordsByKey.containsKey(upKey) && curDist < dist.getOrDefault(upKey, 7)) {
        dist.put(upKey, curDist);
        deque.addFirst(upKey);
      }

      // Weight-1 edges horizontally outward.
      int nextH = curDist + 1;
      if (nextH < 7) {
        for (int[] d : horiz) {
          String nKey = posKey(p[0] + d[0], p[1], p[2] + d[1]);
          if (coordsByKey.containsKey(nKey) && nextH < dist.getOrDefault(nKey, 7)) {
            dist.put(nKey, nextH);
            deque.addLast(nKey);
          }
        }
      }
    }

    for (String key : scaffoldPositions) {
      dist.putIfAbsent(key, 7);
    }
    return Map.copyOf(dist);
  }

  /**
   * Validates that every prefix (and LIFO reverse teardown suffix) of a vanilla scaffolding /
   * mixed temporary support plan satisfies {@code distance <= 6} on every {@link Kind#SCAFFOLD} cell
   * and sturdy connectivity on temporary solid cells.
   */
  public static ValidationResult validateVanillaScaffoldingLifo(
      List<ScaffoldCell> plan,
      Predicate<int[]> sturdyGroundBelow,
      Set<String> blockedPositions) {
    if (plan == null || plan.isEmpty()) return new ValidationResult(true, "");
    var seenKeys = new HashSet<String>();
    var solidPlaced = new HashSet<String>();
    var scaffoldPlaced = new HashSet<String>();
    for (int i = 0; i < plan.size(); i++) {
      ScaffoldCell c = plan.get(i);
      String key = posKey(c.wx(), c.wy(), c.wz());
      if (!seenKeys.add(key)) {
        return new ValidationResult(false, "Duplicate scaffold placement at " + key);
      }
      if (blockedPositions != null && blockedPositions.contains(key)) {
        return new ValidationResult(false, "Scaffold collides with protected block at " + key);
      }
      if (c.kind() == Kind.SCAFFOLD_TOWER || c.kind() == Kind.SCAFFOLD_OVERHANG) {
        scaffoldPlaced.add(key);
      } else {
        boolean supported = sturdyGroundBelow.test(new int[] {c.wx(), c.wy() - 1, c.wz()});
        if (!supported) {
          for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
            if (solidPlaced.contains(posKey(c.wx() + d[0], c.wy() + d[1], c.wz() + d[2]))) {
              supported = true;
              break;
            }
          }
        }
        if (!supported) {
          return new ValidationResult(
              false, "Unsupported temporary solid cell at " + key + " on step " + (i + 1));
        }
        solidPlaced.add(key);
      }

      if (!scaffoldPlaced.isEmpty()) {
        Predicate<int[]> support =
            pos ->
                solidPlaced.contains(posKey(pos[0], pos[1], pos[2]))
                    || sturdyGroundBelow.test(pos);
        var distances = computeVanillaScaffoldDistances(scaffoldPlaced, support);
        for (var e : distances.entrySet()) {
          if (e.getValue() >= 7) {
            return new ValidationResult(
                false,
                "Vanilla scaffolding exceeds distance 6 at " + e.getKey() + " on step " + (i + 1));
          }
        }
      }
    }
    return new ValidationResult(true, "");
  }

  /**
   * Plans interior ground-rooted relay pillars and shared horizontal working platforms for wide /
   * concave high builds (e.g. 41x27x19 roofs) where perimeter outer rings cannot reach interior
   * targets. Preserves strict bottom-up LIFO reversibility.
   */
  static boolean stanceMatchesRequiredSide(int sx, int sz, Pos target, String requiredSide) {
    if (requiredSide == null || requiredSide.isBlank()) return true;
    int dx = target.x() - sx;
    int dz = target.z() - sz;
    if (dx == 0 && dz == 0) return false;
    return switch (requiredSide) {
      case "north" -> target.z() - sz >= Math.abs(dx) + 0.2;
      case "south" -> sz - target.z() >= Math.abs(dx) + 0.2;
      case "west" -> target.x() - sx >= Math.abs(dz) + 0.2;
      case "east" -> sx - target.x() >= Math.abs(dz) + 0.2;
      default -> true;
    };
  }

  public static ClusterPlan planCluster(
      WorldView world,
      List<Pos> targets,
      Pos startStand,
      Mode mode,
      double reach,
      Set<String> protectedPositions) {
    return planCluster(
        world, targets, startStand, mode, reach, protectedPositions, null, Map.of());
  }

  public static ClusterPlan planCluster(
      WorldView world,
      List<Pos> targets,
      Pos startStand,
      Mode mode,
      double reach,
      Set<String> protectedPositions,
      Pos primaryTarget,
      Map<Pos, String> requiredStanceSides) {
    if (targets == null || targets.isEmpty()) {
      return new ClusterPlan(List.of(), List.of(), List.of(), false, true);
    }
    Map<Pos, String> sideMap = requiredStanceSides != null ? requiredStanceSides : Map.of();
    double maxReach = Math.max(2.5, Math.min(4.8, reach));
    double reachSq = maxReach * maxReach;
    var protectedSet = new HashSet<String>(protectedPositions != null ? protectedPositions : Set.of());
    for (Pos t : targets) protectedSet.add(posKey(t.x(), t.y(), t.z()));

    // Filter out targets already reachable (within BOTH distance AND facing sector) from ground,
    // NEVER filtering out the explicitly failed primaryTarget.
    var remaining = new ArrayList<Pos>();
    for (Pos t : targets) {
      if (t.equals(primaryTarget)) {
        remaining.add(t);
        continue;
      }
      String reqSide = sideMap.getOrDefault(t, "");
      boolean reachableFromGround = false;
      for (int dx = -4; dx <= 4 && !reachableFromGround; dx++) {
        for (int dz = -4; dz <= 4 && !reachableFromGround; dz++) {
          int sx = t.x() + dx, sz = t.z() + dz;
          if (!world.isWalkableStand(sx, startStand.y(), sz)) continue;
          if (!stanceMatchesRequiredSide(sx, sz, t, reqSide)) continue;
          if (!standEyeCoversTarget(startStand.y(), t, reqSide)) continue;
          double distSq = distSqStandToTarget(sx, startStand.y(), sz, t);
          if (distSq <= reachSq) reachableFromGround = true;
        }
      }
      if (!reachableFromGround) remaining.add(t);
    }
    if (remaining.isEmpty()) {
      return new ClusterPlan(List.of(), List.of(), List.of(), false, true);
    }

    var builtKeys = new HashSet<String>();
    var buildOrder = new ArrayList<ScaffoldCell>();
    var poses = new ArrayList<WorkPose>();
    boolean usedRelay = false;

    Kind pillarKind = mode == Mode.SCAFFOLDING ? Kind.SCAFFOLD_TOWER : Kind.PILLAR;
    Kind platformKind = mode == Mode.SCAFFOLDING ? Kind.SCAFFOLD_OVERHANG : Kind.CATWALK;

    var uncovered = new ArrayList<>(remaining);
    uncovered.sort(
        Comparator.comparingInt((Pos p) -> p.equals(primaryTarget) ? 0 : 1)
            .thenComparingInt(Pos::y)
            .thenComparingInt(Pos::x)
            .thenComparingInt(Pos::z));

    var coveredSet = new HashSet<Pos>();
    var failedSeeds = new HashSet<Pos>();
    int safetyIterations = 0;
    while (coveredSet.size() + failedSeeds.size() < uncovered.size() && safetyIterations++ < 64) {
      Pos seed = null;
      for (Pos u : uncovered) {
        if (!coveredSet.contains(u) && !failedSeeds.contains(u)) {
          seed = u;
          break;
        }
      }
      if (seed == null) break;

      // Pick best interior/nearby ground-rooted pillar column near seed covering maximum uncovered targets.
      int standY = Math.max(startStand.y() + 1, seed.y() - 1);
      int bestX = Integer.MIN_VALUE, bestZ = Integer.MIN_VALUE;
      int bestCount = -1;
      for (int dx = -3; dx <= 3; dx++) {
        for (int dz = -3; dz <= 3; dz++) {
          int cx = seed.x() + dx, cz = seed.z() + dz;
          if (!canBuildPillar(world, cx, startStand.y(), standY - 1, cz, protectedSet)) continue;
          int coverCount = countCoveredFrom(cx, standY, cz, uncovered, coveredSet, reachSq, sideMap);
          if (coverCount > bestCount) {
            bestCount = coverCount;
            bestX = cx;
            bestZ = cz;
          }
        }
      }
      if (bestX == Integer.MIN_VALUE || bestCount <= 0) {
        failedSeeds.add(seed); // Leave unbuildable target in uncoveredTargets!
        continue;
      }

      usedRelay = true;
      for (int py = startStand.y(); py < standY; py++) {
        String key = posKey(bestX, py, bestZ);
        if (builtKeys.add(key)) {
          buildOrder.add(new ScaffoldCell(bestX, py, bestZ, pillarKind));
        }
      }
      recordPoseCoverage(bestX, standY, bestZ, uncovered, coveredSet, poses, reachSq, sideMap);

      // Expand shared working platform outward only while each added tile covers new uncovered targets.
      int maxRadius = mode == Mode.SCAFFOLDING ? 5 : 3;
      int platformY = standY - 1;
      var q = new ArrayDeque<Pos>();
      q.add(new Pos(bestX, platformY, bestZ));
      var visited = new HashSet<String>();
      visited.add(posKey(bestX, platformY, bestZ));

      while (!q.isEmpty()) {
        Pos cur = q.removeFirst();
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
          int nx = cur.x() + d[0], nz = cur.z() + d[1];
          if (Math.abs(nx - bestX) + Math.abs(nz - bestZ) > maxRadius) continue;
          String nk = posKey(nx, platformY, nz);
          if (!visited.add(nk)) continue;
          if (protectedSet.contains(nk) || !world.isLoaded(nx, platformY, nz)) continue;
          if (!world.isFreeAir(nx, platformY, nz)
              || !world.isFreeAir(nx, platformY + 1, nz)
              || !world.isFreeAir(nx, platformY + 2, nz)) continue;

          int newlyCovered = countCoveredFrom(nx, standY, nz, uncovered, coveredSet, reachSq, sideMap);
          if (newlyCovered <= 0) continue;
          if (builtKeys.add(nk)) {
            buildOrder.add(new ScaffoldCell(nx, platformY, nz, platformKind));
          }
          recordPoseCoverage(nx, standY, nz, uncovered, coveredSet, poses, reachSq, sideMap);
          q.addLast(new Pos(nx, platformY, nz));
        }
      }
    }

    var leftover = new ArrayList<Pos>();
    for (Pos u : uncovered) {
      if (!coveredSet.contains(u)) leftover.add(u);
    }
    ValidationResult lifo = validateLifoReversible(buildOrder, protectedSet);
    boolean valid = lifo.ok();
    if (valid && mode == Mode.SCAFFOLDING) {
      valid =
          validateVanillaScaffoldingLifo(
                  buildOrder,
                  pos -> world.hasSturdyTopFace(pos[0], pos[1], pos[2]),
                  protectedSet)
              .ok();
    }
    return new ClusterPlan(
        List.copyOf(buildOrder),
        List.copyOf(poses),
        List.copyOf(leftover),
        usedRelay,
        valid);
  }

  private static boolean canBuildPillar(
      WorldView world, int x, int baseY, int topY, int z, Set<String> protectedSet) {
    if (!world.isLoaded(x, baseY - 1, z) || !world.hasSturdyTopFace(x, baseY - 1, z)) {
      return false;
    }
    for (int y = baseY; y <= topY + 2; y++) {
      String key = posKey(x, y, z);
      if (protectedSet.contains(key) || !world.isLoaded(x, y, z) || !world.isFreeAir(x, y, z)) {
        return false;
      }
    }
    return true;
  }

  private static boolean standEyeCoversTarget(int standY, Pos target, String reqSide) {
    if (standY + 1.62 >= target.y() + 0.05) return true;
    return (reqSide == null || reqSide.isBlank()) && target.y() <= standY + 2;
  }

  private static int countCoveredFrom(
      int sx,
      int sy,
      int sz,
      List<Pos> candidates,
      Set<Pos> alreadyCovered,
      double reachSq,
      Map<Pos, String> requiredSides) {
    int count = 0;
    for (Pos c : candidates) {
      String req = requiredSides.getOrDefault(c, "");
      if (!alreadyCovered.contains(c)
          && standEyeCoversTarget(sy, c, req)
          && distSqStandToTarget(sx, sy, sz, c) <= reachSq
          && stanceMatchesRequiredSide(sx, sz, c, req)) {
        count++;
      }
    }
    return count;
  }

  private static void recordPoseCoverage(
      int sx,
      int sy,
      int sz,
      List<Pos> candidates,
      Set<Pos> coveredSet,
      List<WorkPose> poses,
      double reachSq,
      Map<Pos, String> requiredSides) {
    var hits = new ArrayList<Pos>();
    for (Pos u : candidates) {
      String req = requiredSides.getOrDefault(u, "");
      if (!coveredSet.contains(u)
          && standEyeCoversTarget(sy, u, req)
          && distSqStandToTarget(sx, sy, sz, u) <= reachSq
          && stanceMatchesRequiredSide(sx, sz, u, req)) {
        hits.add(u);
      }
    }
    if (!hits.isEmpty()) {
      coveredSet.addAll(hits);
      poses.add(new WorkPose(new Pos(sx, sy, sz), List.copyOf(hits)));
    }
  }

  private static double distSqStandToTarget(int sx, int sy, int sz, Pos target) {
    double dx = (target.x() + 0.5) - (sx + 0.5);
    double dy = (target.y() + 0.5) - (sy + 1.62);
    double dz = (target.z() + 0.5) - (sz + 0.5);
    return dx * dx + dy * dy + dz * dz;
  }

  private static int[] parseKey(String key) {
    String[] parts = key.split(",");
    return new int[] {
      Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])
    };
  }
}
