package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Pure-Java 3D terraforming planner for {@code flatten}, {@code fill}, and {@code farm_grid}.
 * Zero {@code net.minecraft.*} dependencies for fast offline JUnit verification.
 */
public final class TerraformPlanner {
  public enum BlockKind {
    AIR,
    REPLACEABLE_PLANT,
    NATURAL_SOLID,
    FALLING_SOLID,
    WATER_SOURCE,
    WATER_FLOW,
    LAVA,
    FARMLAND,
    PROTECTED,
    OTHER_SOLID,
    UNKNOWN
  }

  public record CellState(
      boolean loaded,
      String blockId,
      BlockKind kind,
      boolean hasBlockEntity,
      boolean waterlogged,
      boolean solidSupport,
      boolean walkableTop) {
    public static CellState air() {
      return new CellState(true, "minecraft:air", BlockKind.AIR, false, false, false, false);
    }

    public static CellState unknown() {
      return new CellState(false, "", BlockKind.UNKNOWN, false, false, false, false);
    }

    public boolean isReplaceableForFill() {
      return kind == BlockKind.AIR || kind == BlockKind.REPLACEABLE_PLANT;
    }
  }

  @FunctionalInterface
  public interface GridView {
    CellState at(int x, int y, int z);
  }

  public enum OpPhase {
    CUT,
    FILL,
    WATER,
    COVER_SLAB,
    HOE_AND_SEED
  }

  public enum OpKind {
    BREAK,
    PLACE,
    PLACE_WATER,
    PLACE_SLAB,
    HOE,
    SEED
  }

  public record WorkOp(
      int id,
      OpPhase phase,
      OpKind kind,
      int x,
      int y,
      int z,
      String itemId,
      String expectedBeforeId) {
    public TerraformSpec.Pos pos() {
      return new TerraformSpec.Pos(x, y, z);
    }

    public boolean isJournalable() {
      return kind == OpKind.BREAK || kind == OpKind.PLACE;
    }
  }

  public record BlockerSample(int x, int y, int z, String code, String detail) {
    public JsonObject toJson() {
      var o = new JsonObject();
      o.addProperty("x", x);
      o.addProperty("y", y);
      o.addProperty("z", z);
      o.addProperty("code", code);
      o.addProperty("detail", detail);
      return o;
    }
  }

  public record RollbackCoverage(
      int maxEntries,
      int estimatedOps,
      int estimatedJournalable,
      int confirmedJournalable,
      int retainedNow,
      int evicted,
      int notReversible) {
    public JsonObject toJson() {
      var o = new JsonObject();
      o.addProperty("max_entries", maxEntries);
      o.addProperty("estimated_ops", estimatedOps);
      o.addProperty("estimated_journalable", estimatedJournalable);
      o.addProperty("confirmed_journalable", confirmedJournalable);
      o.addProperty("retained_now", retainedNow);
      o.addProperty("evicted", evicted);
      o.addProperty("not_reversible", notReversible);
      return o;
    }
  }

  public record FarmLayout(
      List<TerraformSpec.Pos> waterCells,
      List<TerraformSpec.Pos> cropCells,
      TerraformSpec.Pos exitCell,
      List<TerraformSpec.Pos> reservedPaths,
      int coveredCells,
      int totalSoilCells) {
    public JsonObject irrigationJson() {
      var o = new JsonObject();
      o.addProperty("water_sources", waterCells.size());
      o.addProperty("crop_cells", cropCells.size());
      o.addProperty("covered_cells", coveredCells);
      o.addProperty("total_soil_cells", totalSoilCells);
      o.addProperty("reserved_walkway_cells", reservedPaths.size());
      if (exitCell != null) o.addProperty("exit_cell", exitCell.toShortString());
      return o;
    }
  }

  public record Plan(
      TerraformSpec spec,
      String planHash,
      List<WorkOp> ops,
      Map<String, Integer> materialsNeeded,
      Map<OpPhase, Integer> countsByPhase,
      List<BlockerSample> blockers,
      Map<String, Integer> blockerTotals,
      int alreadyMatched,
      int preserved,
      int unloadedCount,
      FarmLayout farmLayout,
      RollbackCoverage rollbackCoverage) {

    public boolean hasHardBlockers() {
      for (String k : blockerTotals.keySet()) {
        if (k.equals("DEGENERATE_FARM_SIZE")
            || k.equals("NO_SAFE_EXIT")
            || k.equals("WATER_WALL_UNSEALED")
            || k.equals("OPS_LIMIT_EXCEEDED")) {
          return true;
        }
      }
      return false;
    }

    public JsonObject toPreviewJson(String scope) {
      var o = new JsonObject();
      o.addProperty("scope", scope == null ? "" : scope);
      o.addProperty("plan_hash", planHash);
      o.add("bounds", spec.box().toJson());
      o.addProperty("mode", spec.mode().name().toLowerCase(Locale.ROOT));
      if (spec instanceof TerraformSpec.Flatten f) {
        o.addProperty("variant", f.variant().name().toLowerCase(Locale.ROOT));
        o.addProperty("target_y", f.targetY());
      } else if (spec instanceof TerraformSpec.Fill fi) {
        o.addProperty("variant", fi.variant().name().toLowerCase(Locale.ROOT));
      } else if (spec instanceof TerraformSpec.FarmGrid fg) {
        o.addProperty("target_y", fg.targetY());
      }
      var phases = new JsonObject();
      for (OpPhase p : OpPhase.values()) {
        int c = countsByPhase.getOrDefault(p, 0);
        if (c > 0) phases.addProperty(p.name().toLowerCase(Locale.ROOT), c);
      }
      o.add("counts_by_phase", phases);
      o.addProperty("estimated_ops", ops.size());
      o.addProperty("already_matched", alreadyMatched);
      o.addProperty("preserved", preserved);
      o.addProperty("unloaded", unloadedCount);

      var mats = new JsonObject();
      for (var e : materialsNeeded.entrySet()) mats.addProperty(e.getKey(), e.getValue());
      o.add("materials", mats);

      var bTotals = new JsonObject();
      for (var e : blockerTotals.entrySet()) bTotals.addProperty(e.getKey(), e.getValue());
      o.add("blocker_totals", bTotals);

      var bSamples = new JsonArray();
      for (BlockerSample bs : blockers) bSamples.add(bs.toJson());
      o.add("blockers", bSamples);

      if (farmLayout != null) {
        o.add("irrigation_coverage", farmLayout.irrigationJson());
        var resArr = new JsonArray();
        for (var rp : farmLayout.reservedPaths()) resArr.add(rp.toShortString());
        o.add("reserved_paths", resArr);
      }
      o.add("rollback_coverage", rollbackCoverage.toJson());
      return o;
    }
  }

  private static final int SAMPLE_CAP_PER_REASON = 16;

  private static final class Builder {
    final TerraformSpec spec;
    final List<WorkOp> ops = new ArrayList<>();
    final Map<String, Integer> materials = new LinkedHashMap<>();
    final Map<OpPhase, Integer> phaseCounts = new EnumMap<>(OpPhase.class);
    final List<BlockerSample> samples = new ArrayList<>();
    final Map<String, Integer> reasonCounts = new LinkedHashMap<>();
    int nextId = 1;
    int alreadyMatched = 0;
    int preserved = 0;
    int unloaded = 0;
    FarmLayout farmLayout;

    Builder(TerraformSpec spec) {
      this.spec = spec;
    }

    void addOp(OpPhase phase, OpKind kind, int x, int y, int z, String item, String expected) {
      if (ops.size() >= TerraformSpec.MAX_OPS) {
        addBlocker(x, y, z, "OPS_LIMIT_EXCEEDED", "单批世界变更超出上限 " + TerraformSpec.MAX_OPS);
        return;
      }
      ops.add(new WorkOp(nextId++, phase, kind, x, y, z, item == null ? "" : item, expected == null ? "" : expected));
      phaseCounts.merge(phase, 1, Integer::sum);
      if (item != null && !item.isBlank() && kind != OpKind.HOE) {
        materials.merge(item, 1, Integer::sum);
      }
    }

    void addBlocker(int x, int y, int z, String code, String detail) {
      int total = reasonCounts.merge(code, 1, Integer::sum);
      if (total <= SAMPLE_CAP_PER_REASON && samples.size() < 48) {
        samples.add(new BlockerSample(x, y, z, code, detail));
      }
    }

    Plan build() {
      int journalable = 0;
      int nonRev = 0;
      for (WorkOp op : ops) {
        if (op.isJournalable()) journalable++;
        else nonRev++;
      }
      var rb =
          new RollbackCoverage(
              RollbackJournal.MAX_ENTRIES, ops.size(), journalable, 0, 0, 0, nonRev);
      String hash = computePlanHash();
      return new Plan(
          spec,
          hash,
          List.copyOf(ops),
          Collections.unmodifiableMap(materials),
          Collections.unmodifiableMap(phaseCounts),
          List.copyOf(samples),
          Collections.unmodifiableMap(reasonCounts),
          alreadyMatched,
          preserved,
          unloaded,
          farmLayout,
          rb);
    }

    private String computePlanHash() {
      var o = new JsonObject();
      o.addProperty("spec", spec.specHash());
      o.addProperty("ops", ops.size());
      var ph = new JsonObject();
      for (var e : phaseCounts.entrySet()) ph.addProperty(e.getKey().name(), e.getValue());
      o.add("phases", ph);
      var rc = new JsonObject();
      for (var e : reasonCounts.entrySet()) rc.addProperty(e.getKey(), e.getValue());
      o.add("reasons", rc);
      try {
        byte[] d =
            MessageDigest.getInstance("SHA-256")
                .digest(o.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d)
          sb.append(Character.forDigit((b >>> 4) & 0xF, 16))
              .append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private TerraformPlanner() {}

  public static Plan plan(
      TerraformSpec spec, GridView view, TerraformSpec.Pos playerStart) {
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(view, "view");
    Builder b = new Builder(spec);
    if (spec instanceof TerraformSpec.Flatten f) {
      planFlatten(f, view, b);
    } else if (spec instanceof TerraformSpec.Fill fi) {
      planFill(fi, view, b);
    } else if (spec instanceof TerraformSpec.FarmGrid fg) {
      planFarmGrid(fg, view, playerStart, b);
    }
    return b.build();
  }

  private static boolean checkColumnTopSafe(
      int x, int maxY, int z, GridView view, Builder b, boolean hasCutInColumn) {
    if (!hasCutInColumn) return true;
    CellState above = view.at(x, maxY + 1, z);
    if (!above.loaded()) {
      b.unloaded++;
      b.addBlocker(x, maxY + 1, z, "UNLOADED_ABOVE", "盒顶上方区块未加载，隔离该列");
      return false;
    }
    if (above.kind() == BlockKind.FALLING_SOLID) {
      b.addBlocker(x, maxY + 1, z, "FALLING_COLUMN_ABOVE", "盒顶上方存在落沙/砂砾压顶，整列跳过防坍塌");
      return false;
    }
    return true;
  }

  private static boolean isCuttableBySpec(TerraformSpec spec, CellState cs) {
    if (cs.hasBlockEntity() || cs.kind() == BlockKind.PROTECTED) return false;
    if (cs.kind() == BlockKind.REPLACEABLE_PLANT) return spec.cutBlocks().contains(cs.blockId())
        || TerraformSpec.DEFAULT_NATURAL_CUT_BLOCKS.contains(cs.blockId());
    if (cs.kind() != BlockKind.NATURAL_SOLID && cs.kind() != BlockKind.FALLING_SOLID) return false;
    return spec.cutBlocks().contains(cs.blockId());
  }

  private static void planFlatten(TerraformSpec.Flatten spec, GridView view, Builder b) {
    var box = spec.box();
    int ty = spec.targetY();

    // Pre-check each XZ column above box.maxY if any cut target exists in [ty+1..maxY]
    Set<Long> blockedColumns = new HashSet<>();
    for (int z = box.minZ(); z <= box.maxZ(); z++) {
      for (int x = box.minX(); x <= box.maxX(); x++) {
        boolean anyNonAirAbove = false;
        for (int y = box.maxY(); y >= ty + 1; y--) {
          CellState cs = view.at(x, y, z);
          if (!cs.loaded() || cs.kind() != BlockKind.AIR) {
            anyNonAirAbove = true;
            break;
          }
        }
        if (!checkColumnTopSafe(x, box.maxY(), z, view, b, anyNonAirAbove)) {
          blockedColumns.add(packXZ(x, z));
        }
      }
    }

    // Strict top-down cut: maxY -> targetY + 1
    for (int y = box.maxY(); y >= ty + 1; y--) {
      for (int z = box.minZ(); z <= box.maxZ(); z++) {
        int rowIdx = z - box.minZ();
        for (int dx = 0; dx < box.width(); dx++) {
          int x = (rowIdx % 2 == 0) ? (box.minX() + dx) : (box.maxX() - dx);
          if (blockedColumns.contains(packXZ(x, z))) continue;
          CellState cs = view.at(x, y, z);
          if (!cs.loaded()) {
            b.unloaded++;
            b.addBlocker(x, y, z, "UNKNOWN_UNLOADED", "目标格未加载");
            blockedColumns.add(packXZ(x, z));
            continue;
          }
          if (cs.kind() == BlockKind.AIR) {
            b.alreadyMatched++;
            continue;
          }
          if (cs.kind() == BlockKind.WATER_SOURCE
              || cs.kind() == BlockKind.WATER_FLOW
              || cs.kind() == BlockKind.LAVA) {
            b.addBlocker(x, y, z, "FLUID_EXPOSURE", "切削层存在流体，禁止盲拆");
            blockedColumns.add(packXZ(x, z));
            continue;
          }
          if (!isCuttableBySpec(spec, cs)) {
            b.addBlocker(
                x,
                y,
                z,
                cs.hasBlockEntity() || cs.kind() == BlockKind.PROTECTED
                    ? "PROTECTED_BLOCK"
                    : "NON_NATURAL_BLOCK",
                "跳过非授权/受保护方块：" + cs.blockId());
            blockedColumns.add(packXZ(x, z));
            continue;
          }
          b.addOp(OpPhase.CUT, OpKind.BREAK, x, y, z, "", cs.blockId());
        }
      }
    }

    if (spec.variant() == TerraformSpec.FlattenVariant.CUT_AND_FILL) {
      // Bottom-up fill: minY -> targetY
      Set<Long> supportedCells = new HashSet<>();
      for (int y = box.minY(); y <= ty; y++) {
        for (int z = box.minZ(); z <= box.maxZ(); z++) {
          int rowIdx = z - box.minZ();
          for (int dx = 0; dx < box.width(); dx++) {
            int x = (rowIdx % 2 == 0) ? (box.minX() + dx) : (box.maxX() - dx);
            CellState cs = view.at(x, y, z);
            if (!cs.loaded()) {
              b.unloaded++;
              b.addBlocker(x, y, z, "UNKNOWN_UNLOADED", "回填层未加载");
              continue;
            }
            if (cs.solidSupport()) {
              supportedCells.add(packXYZ(x, y, z));
              b.alreadyMatched++;
              continue;
            }
            if (!cs.isReplaceableForFill()) {
              b.preserved++;
              continue;
            }
            boolean hasFloor =
                y == box.minY()
                    ? view.at(x, y - 1, z).solidSupport()
                    : (view.at(x, y - 1, z).solidSupport()
                        || supportedCells.contains(packXYZ(x, y - 1, z)));
            if (!hasFloor) {
              b.addBlocker(x, y, z, "NO_FOUNDATION", "回填底部缺少实心承托，拒绝悬空填充");
              continue;
            }
            b.addOp(OpPhase.FILL, OpKind.PLACE, x, y, z, spec.fillBlock(), cs.blockId());
            supportedCells.add(packXYZ(x, y, z));
          }
        }
      }
    }
  }

  private static void planFill(TerraformSpec.Fill spec, GridView view, Builder b) {
    var box = spec.box();
    // Phase 1 (optional CUT when replaceNatural=true): top-down
    Set<Long> clearedCells = new HashSet<>();
    Set<Long> blockedColumns = new HashSet<>();

    if (spec.variant() != TerraformSpec.FillVariant.REPLACE_AIR && spec.replaceNatural()) {
      for (int z = box.minZ(); z <= box.maxZ(); z++) {
        for (int x = box.minX(); x <= box.maxX(); x++) {
          boolean needsTopCheck = false;
          for (int y = box.maxY(); y >= box.minY(); y--) {
            if (isInFillShape(box, spec.variant(), x, y, z)) {
              CellState cs = view.at(x, y, z);
              if (!cs.loaded() || (!cs.isReplaceableForFill() && !cs.blockId().equals(spec.block()))) {
                needsTopCheck = true;
                break;
              }
            }
          }
          if (!checkColumnTopSafe(x, box.maxY(), z, view, b, needsTopCheck)) {
            blockedColumns.add(packXZ(x, z));
          }
        }
      }
      for (int y = box.maxY(); y >= box.minY(); y--) {
        for (int z = box.minZ(); z <= box.maxZ(); z++) {
          for (int x = box.minX(); x <= box.maxX(); x++) {
            if (!isInFillShape(box, spec.variant(), x, y, z)) continue;
            if (blockedColumns.contains(packXZ(x, z))) continue;
            CellState cs = view.at(x, y, z);
            if (!cs.loaded()) continue;
            if (cs.blockId().equals(spec.block())) continue;
            if (cs.isReplaceableForFill()) continue;
            if (isCuttableBySpec(spec, cs)) {
              b.addOp(OpPhase.CUT, OpKind.BREAK, x, y, z, "", cs.blockId());
              clearedCells.add(packXYZ(x, y, z));
            }
          }
        }
      }
    }

    // Phase 2: bottom-up FILL
    for (int y = box.minY(); y <= box.maxY(); y++) {
      for (int z = box.minZ(); z <= box.maxZ(); z++) {
        int rowIdx = z - box.minZ();
        for (int dx = 0; dx < box.width(); dx++) {
          int x = (rowIdx % 2 == 0) ? (box.minX() + dx) : (box.maxX() - dx);
          if (!isInFillShape(box, spec.variant(), x, y, z)) continue;
          CellState cs = view.at(x, y, z);
          if (!cs.loaded()) {
            b.unloaded++;
            b.addBlocker(x, y, z, "UNKNOWN_UNLOADED", "填充格未加载");
            continue;
          }
          if (spec.variant() == TerraformSpec.FillVariant.REPLACE_AIR) {
            if (cs.kind() == BlockKind.AIR) {
              b.addOp(OpPhase.FILL, OpKind.PLACE, x, y, z, spec.block(), cs.blockId());
            } else {
              b.preserved++;
            }
            continue;
          }
          if (cs.blockId().equals(spec.block())) {
            b.alreadyMatched++;
            continue;
          }
          if (cs.isReplaceableForFill() || clearedCells.contains(packXYZ(x, y, z))) {
            b.addOp(OpPhase.FILL, OpKind.PLACE, x, y, z, spec.block(), cs.blockId());
          } else {
            b.addBlocker(
                x,
                y,
                z,
                cs.hasBlockEntity() || cs.kind() == BlockKind.PROTECTED
                    ? "PROTECTED_BLOCK"
                    : "CONFLICT_EXISTING",
                "已有不同方块（需 replace_natural=true 才拆换天然方块）：" + cs.blockId());
          }
        }
      }
    }
  }

  public static boolean isInFillShape(
      TerraformSpec.Box box, TerraformSpec.FillVariant variant, int x, int y, int z) {
    if (!box.contains(x, y, z)) return false;
    return switch (variant) {
      case SOLID, REPLACE_AIR -> true;
      case WALLS ->
          x == box.minX() || x == box.maxX() || z == box.minZ() || z == box.maxZ();
      case HOLLOW ->
          x == box.minX()
              || x == box.maxX()
              || y == box.minY()
              || y == box.maxY()
              || z == box.minZ()
              || z == box.maxZ();
    };
  }

  public static List<Integer> segmentCenters(int min, int max) {
    int len = max - min + 1;
    int segments = (len + 8) / 9;
    List<Integer> out = new ArrayList<>(segments);
    for (int i = 0; i < segments; i++) {
      int segStart = min + (i * len) / segments;
      int segEnd = min + ((i + 1) * len) / segments - 1;
      out.add((segStart + segEnd) / 2);
    }
    return out;
  }

  private static void planFarmGrid(
      TerraformSpec.FarmGrid spec,
      GridView view,
      TerraformSpec.Pos playerStart,
      Builder b) {
    var box = spec.box();
    int ty = spec.targetY();

    if (box.width() == 1 && box.depth() == 1) {
      b.addBlocker(box.minX(), ty, box.minZ(), "DEGENERATE_FARM_SIZE", "1x1 农田仅含 1 个水源位，净种植面积为 0");
      b.farmLayout = new FarmLayout(List.of(new TerraformSpec.Pos(box.minX(), ty, box.minZ())), List.of(), null, List.of(), 0, 0);
      return;
    }

    // Compute 9x9 segmented water grid centers
    List<Integer> wxs = segmentCenters(box.minX(), box.maxX());
    List<Integer> wzs = segmentCenters(box.minZ(), box.maxZ());
    Set<Long> waterSet = new LinkedHashSet<>();
    List<TerraformSpec.Pos> waterCells = new ArrayList<>();
    for (int wz : wzs) {
      for (int wx : wxs) {
        int bestX = wx, bestZ = wz;
        if (!waterPitSealed(bestX, ty, bestZ, box, view)) {
          // Try tiny ±1 inward shift if it still keeps every cell covered within Chebyshev <= 4
          boolean foundShift = false;
          for (int sx : new int[] {0, 1, -1}) {
            for (int sz : new int[] {0, 1, -1}) {
              int cx = bestX + sx, cz = bestZ + sz;
              if (!box.contains(cx, ty, cz)) continue;
              if (waterPitSealed(cx, ty, cz, box, view)
                  && coversSameSegment(cx, cz, wx, wz, box)) {
                bestX = cx;
                bestZ = cz;
                foundShift = true;
                break;
              }
            }
            if (foundShift) break;
          }
          if (!foundShift) {
            b.addBlocker(bestX, ty, bestZ, "WATER_WALL_UNSEALED", "水窝邻接盒外无实心侧壁，无法安全盛水");
          }
        }
        waterCells.add(new TerraformSpec.Pos(bestX, ty, bestZ));
        waterSet.add(packXZ(bestX, bestZ));
      }
    }

    // 1. CUT above targetY (top-down)
    Set<Long> blockedCols = new HashSet<>();
    for (int z = box.minZ(); z <= box.maxZ(); z++) {
      for (int x = box.minX(); x <= box.maxX(); x++) {
        boolean anyAbove = false;
        for (int y = box.maxY(); y >= ty + 1; y--) {
          CellState cs = view.at(x, y, z);
          if (!cs.loaded() || cs.kind() != BlockKind.AIR) {
            anyAbove = true;
            break;
          }
        }
        if (!checkColumnTopSafe(x, box.maxY(), z, view, b, anyAbove)) {
          blockedCols.add(packXZ(x, z));
        }
      }
    }
    for (int y = box.maxY(); y >= ty + 1; y--) {
      for (int z = box.minZ(); z <= box.maxZ(); z++) {
        for (int x = box.minX(); x <= box.maxX(); x++) {
          if (blockedCols.contains(packXZ(x, z))) continue;
          CellState cs = view.at(x, y, z);
          if (!cs.loaded()) {
            b.unloaded++;
            b.addBlocker(x, y, z, "UNKNOWN_UNLOADED", "作物净空未加载");
            continue;
          }
          if (cs.kind() == BlockKind.AIR) continue;
          if (spec.crop() != null && y == ty + 1 && cs.blockId().equals(spec.crop().block())) continue;
          if (isCuttableBySpec(spec, cs)) {
            b.addOp(OpPhase.CUT, OpKind.BREAK, x, y, z, "", cs.blockId());
          } else {
            b.addBlocker(x, y, z, "OBSTRUCTED_HEADROOM", "上方障碍非天然地形：" + cs.blockId());
          }
        }
      }
    }

    // 2. FILL foundation minY .. ty-1 + soil layer ty
    Set<Long> filledSupport = new HashSet<>();
    for (int y = box.minY(); y < ty; y++) {
      for (int z = box.minZ(); z <= box.maxZ(); z++) {
        for (int x = box.minX(); x <= box.maxX(); x++) {
          CellState cs = view.at(x, y, z);
          if (!cs.loaded()) {
            b.unloaded++;
            b.addBlocker(x, y, z, "UNKNOWN_UNLOADED", "基础层未加载");
            continue;
          }
          if (cs.solidSupport()) {
            filledSupport.add(packXYZ(x, y, z));
            continue;
          }
          if (cs.isReplaceableForFill()) {
            boolean hasFloor =
                y == box.minY()
                    ? view.at(x, y - 1, z).solidSupport()
                    : (view.at(x, y - 1, z).solidSupport()
                        || filledSupport.contains(packXYZ(x, y - 1, z)));
            if (!hasFloor) {
              b.addBlocker(x, y, z, "NO_FOUNDATION", "农田下方缺少承托层");
              continue;
            }
            b.addOp(OpPhase.FILL, OpKind.PLACE, x, y, z, "minecraft:dirt", cs.blockId());
            filledSupport.add(packXYZ(x, y, z));
          }
        }
      }
    }

    // Prepare targetY level: non-water cells become dirt (or keep dirt/grass_block/farmland); water cells must become open pit
    for (int z = box.minZ(); z <= box.maxZ(); z++) {
      for (int x = box.minX(); x <= box.maxX(); x++) {
        CellState cs = view.at(x, ty, z);
        if (!cs.loaded()) {
          b.unloaded++;
          b.addBlocker(x, ty, z, "UNKNOWN_UNLOADED", "土层未加载");
          continue;
        }
        if (waterSet.contains(packXZ(x, z))) {
          if (cs.kind() == BlockKind.WATER_SOURCE) {
            if (!spec.coverSlab().isBlank() && cs.waterlogged() && cs.blockId().equals(spec.coverSlab())) {
              b.alreadyMatched++;
            }
          } else if (cs.kind() == BlockKind.AIR) {
            b.addOp(OpPhase.WATER, OpKind.PLACE_WATER, x, ty, z, "minecraft:water_bucket", cs.blockId());
          } else if (isCuttableBySpec(spec, cs)) {
            b.addOp(OpPhase.CUT, OpKind.BREAK, x, ty, z, "", cs.blockId());
            b.addOp(OpPhase.WATER, OpKind.PLACE_WATER, x, ty, z, "minecraft:water_bucket", "minecraft:air");
          } else {
            b.addBlocker(x, ty, z, "PROTECTED_WATER_SITE", "水窝位置被非天然方块占用：" + cs.blockId());
          }
          if (!spec.coverSlab().isBlank()
              && !(cs.waterlogged() && cs.blockId().equals(spec.coverSlab()))) {
            b.addOp(OpPhase.COVER_SLAB, OpKind.PLACE_SLAB, x, ty, z, spec.coverSlab(), "minecraft:water");
          }
        } else {
          boolean soilReady =
              cs.blockId().equals("minecraft:dirt")
                  || cs.blockId().equals("minecraft:grass_block")
                  || cs.kind() == BlockKind.FARMLAND;
          if (!soilReady) {
            if (cs.isReplaceableForFill()) {
              b.addOp(OpPhase.FILL, OpKind.PLACE, x, ty, z, "minecraft:dirt", cs.blockId());
            } else if (isCuttableBySpec(spec, cs)) {
              b.addOp(OpPhase.CUT, OpKind.BREAK, x, ty, z, "", cs.blockId());
              b.addOp(OpPhase.FILL, OpKind.PLACE, x, ty, z, "minecraft:dirt", "minecraft:air");
            } else {
              b.addBlocker(x, ty, z, "NON_SOIL_OBSTACLE", "农格位置被非天然方块占用：" + cs.blockId());
            }
          }
        }
      }
    }

    // 3. Partition soil into already-tilled farmland (only seed if unseeded) vs walkable soil (peel from exit)
    boolean hasCoverSlab = !spec.coverSlab().isBlank();
    Set<Long> walkableSoil = new LinkedHashSet<>();
    List<TerraformSpec.Pos> unseededFarmland = new ArrayList<>();
    Set<Long> slabWalkCells = new LinkedHashSet<>();
    int totalSoilCells = 0;
    int coveredCount = 0;
    for (int z = box.minZ(); z <= box.maxZ(); z++) {
      for (int x = box.minX(); x <= box.maxX(); x++) {
        if (waterSet.contains(packXZ(x, z))) {
          if (hasCoverSlab) slabWalkCells.add(packXZ(x, z));
          continue;
        }
        totalSoilCells++;
        if (isCoveredByWater(x, z, waterCells)) coveredCount++;
        CellState cs = view.at(x, ty, z);
        if (cs.kind() == BlockKind.FARMLAND) {
          if (spec.crop() != null) {
            CellState above = view.at(x, ty + 1, z);
            if (!above.blockId().equals(spec.crop().block())) {
              unseededFarmland.add(new TerraformSpec.Pos(x, ty, z));
            }
          }
        } else {
          walkableSoil.add(packXZ(x, z));
        }
      }
    }

    for (TerraformSpec.Pos p : unseededFarmland) {
      CellState above = view.at(p.x(), ty + 1, p.z());
      b.addOp(
          OpPhase.HOE_AND_SEED,
          OpKind.SEED,
          p.x(),
          ty + 1,
          p.z(),
          spec.crop().seed(),
          above.blockId());
    }

    List<TerraformSpec.Pos> cropOrder = new ArrayList<>(unseededFarmland);
    List<TerraformSpec.Pos> reservedWalkway = new ArrayList<>();
    TerraformSpec.Pos exitCell = null;

    if (!walkableSoil.isEmpty()) {
      exitCell = findPerimeterExit(box, ty, view, playerStart, walkableSoil, slabWalkCells);

      if (exitCell == null) {
        // Fallback: check if playerStart or any safe boundary cell inside soil can act as 1-cell exit pad
        TerraformSpec.Pos interiorRoot = pickFallbackInteriorExit(box, ty, playerStart, walkableSoil);
        if (interiorRoot == null) {
          b.addBlocker(box.minX(), ty, box.minZ(), "NO_SAFE_EXIT", "农田四周无安全出口且内部无可立足撤离格");
        } else {
          exitCell = interiorRoot;
          reservedWalkway.add(interiorRoot);
          walkableSoil.remove(packXZ(interiorRoot.x(), interiorRoot.z()));
        }
      }

      if (exitCell != null && !walkableSoil.isEmpty()) {
        List<TerraformSpec.Pos> peeled =
            peelFromRoot(box, ty, exitCell, walkableSoil, slabWalkCells);
        if (peeled.size() < walkableSoil.size()) {
          // Disconnected soil pocket unreachable from exit without crossing water/void -> mark unreachable cells
          Set<Long> peeledSet = new HashSet<>();
          for (var p : peeled) peeledSet.add(packXZ(p.x(), p.z()));
          for (long key : walkableSoil) {
            if (!peeledSet.contains(key)) {
              b.addBlocker(unpackX(key), ty, unpackZ(key), "NO_SAFE_EXIT", "该农格与退路出口不连通");
            }
          }
        }
        cropOrder.addAll(peeled);
        for (TerraformSpec.Pos p : peeled) {
          CellState cs = view.at(p.x(), ty, p.z());
          if (cs.kind() != BlockKind.FARMLAND) {
            b.addOp(OpPhase.HOE_AND_SEED, OpKind.HOE, p.x(), ty, p.z(), "minecraft:diamond_hoe", cs.blockId());
          }
          if (spec.crop() != null) {
            CellState above = view.at(p.x(), ty + 1, p.z());
            if (!above.blockId().equals(spec.crop().block())) {
              b.addOp(
                  OpPhase.HOE_AND_SEED,
                  OpKind.SEED,
                  p.x(),
                  ty + 1,
                  p.z(),
                  spec.crop().seed(),
                  above.blockId());
            }
          }
        }
      }
    }

    b.farmLayout =
        new FarmLayout(
            List.copyOf(waterCells),
            List.copyOf(cropOrder),
            exitCell,
            List.copyOf(reservedWalkway),
            coveredCount,
            totalSoilCells);
  }

  private static boolean waterPitSealed(
      int wx, int ty, int wz, TerraformSpec.Box box, GridView view) {
    CellState floor = view.at(wx, ty - 1, wz);
    if (!box.contains(wx, ty - 1, wz) && (!floor.loaded() || !floor.solidSupport())) return false;
    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
      int nx = wx + d[0], nz = wz + d[1];
      if (box.contains(nx, ty, nz)) continue;
      CellState wall = view.at(nx, ty, nz);
      if (!wall.loaded() || !wall.solidSupport()) return false;
    }
    return true;
  }

  private static boolean coversSameSegment(
      int nx, int nz, int ox, int oz, TerraformSpec.Box box) {
    int minX = Math.max(box.minX(), ox - 4), maxX = Math.min(box.maxX(), ox + 4);
    int minZ = Math.max(box.minZ(), oz - 4), maxZ = Math.min(box.maxZ(), oz + 4);
    return Math.abs(minX - nx) <= 4
        && Math.abs(maxX - nx) <= 4
        && Math.abs(minZ - nz) <= 4
        && Math.abs(maxZ - nz) <= 4;
  }

  private static boolean isCoveredByWater(int x, int z, List<TerraformSpec.Pos> waterCells) {
    for (var w : waterCells) {
      if (Math.max(Math.abs(x - w.x()), Math.abs(z - w.z())) <= 4) return true;
    }
    return false;
  }

  private static boolean outsideStandable(int x, int ty, int z, GridView view) {
    CellState floor = view.at(x, ty, z);
    CellState h1 = view.at(x, ty + 1, z);
    CellState h2 = view.at(x, ty + 2, z);
    return floor.loaded()
        && floor.walkableTop()
        && floor.kind() != BlockKind.FARMLAND
        && h1.loaded()
        && h1.isReplaceableForFill()
        && h2.loaded()
        && h2.isReplaceableForFill();
  }

  private static TerraformSpec.Pos findPerimeterExit(
      TerraformSpec.Box box,
      int ty,
      GridView view,
      TerraformSpec.Pos playerStart,
      Set<Long> soil,
      Set<Long> slabs) {
    List<TerraformSpec.Pos> candidates = new ArrayList<>();
    for (int x = box.minX(); x <= box.maxX(); x++) {
      if ((soil.contains(packXZ(x, box.minZ())) || slabs.contains(packXZ(x, box.minZ())))
          && outsideStandable(x, ty, box.minZ() - 1, view)) {
        candidates.add(new TerraformSpec.Pos(x, ty, box.minZ() - 1));
      }
      if ((soil.contains(packXZ(x, box.maxZ())) || slabs.contains(packXZ(x, box.maxZ())))
          && outsideStandable(x, ty, box.maxZ() + 1, view)) {
        candidates.add(new TerraformSpec.Pos(x, ty, box.maxZ() + 1));
      }
    }
    for (int z = box.minZ(); z <= box.maxZ(); z++) {
      if ((soil.contains(packXZ(box.minX(), z)) || slabs.contains(packXZ(box.minX(), z)))
          && outsideStandable(box.minX() - 1, ty, z, view)) {
        candidates.add(new TerraformSpec.Pos(box.minX() - 1, ty, z));
      }
      if ((soil.contains(packXZ(box.maxX(), z)) || slabs.contains(packXZ(box.maxX(), z)))
          && outsideStandable(box.maxX() + 1, ty, z, view)) {
        candidates.add(new TerraformSpec.Pos(box.maxX() + 1, ty, z));
      }
    }
    if (candidates.isEmpty()) return null;
    if (playerStart == null) return candidates.get(0);
    return candidates.stream()
        .min(
            Comparator.comparingInt(
                p -> Math.abs(p.x() - playerStart.x()) + Math.abs(p.z() - playerStart.z())))
        .orElse(candidates.get(0));
  }

  private static TerraformSpec.Pos pickFallbackInteriorExit(
      TerraformSpec.Box box, int ty, TerraformSpec.Pos playerStart, Set<Long> soil) {
    if (playerStart != null && soil.contains(packXZ(playerStart.x(), playerStart.z()))) {
      return new TerraformSpec.Pos(playerStart.x(), ty, playerStart.z());
    }
    if (soil.size() <= 1) return null;
    long first = soil.iterator().next();
    return new TerraformSpec.Pos(unpackX(first), ty, unpackZ(first));
  }

  /**
   * Generates a provably non-trapping backing-out order using BFS distances from {@code root}.
   * Peeling cells in strictly descending BFS depth keeps every remaining cell connected to {@code
   * root} with zero farmland trampling.
   */
  private static List<TerraformSpec.Pos> peelFromRoot(
      TerraformSpec.Box box,
      int ty,
      TerraformSpec.Pos root,
      Set<Long> soil,
      Set<Long> slabs) {
    Map<Long, Integer> dist = new LinkedHashMap<>();
    ArrayDeque<Long> q = new ArrayDeque<>();
    long rootKey = packXZ(root.x(), root.z());
    dist.put(rootKey, 0);
    q.add(rootKey);

    while (!q.isEmpty()) {
      long cur = q.removeFirst();
      int cx = unpackX(cur), cz = unpackZ(cur);
      int nextD = dist.get(cur) + 1;
      for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
        int nx = cx + d[0], nz = cz + d[1];
        long nk = packXZ(nx, nz);
        if (dist.containsKey(nk)) continue;
        if (!soil.contains(nk) && !slabs.contains(nk)) continue;
        dist.put(nk, nextD);
        q.addLast(nk);
      }
    }

    List<TerraformSpec.Pos> toPeel = new ArrayList<>(soil.size());
    for (long k : soil) {
      if (dist.containsKey(k)) toPeel.add(new TerraformSpec.Pos(unpackX(k), ty, unpackZ(k)));
    }
    toPeel.sort(
        Comparator.<TerraformSpec.Pos>comparingInt(
                p -> -dist.get(packXZ(p.x(), p.z())))
            .thenComparingInt(TerraformSpec.Pos::z)
            .thenComparingInt(
                p -> ((p.z() - box.minZ()) % 2 == 0) ? p.x() : -p.x()));
    return toPeel;
  }

  public record StanceChoice(
      int stanceX, int stanceY, int stanceZ, int coverCount, int routeSteps, int firstOpIndex) {}

  /**
   * Pure-Java dynamic stance selection using a bounded walk tree over walkable cells. Picks the
   * reachable stance maximizing {@code coverCount / (routeSteps * 4 + 6)} with deterministic
   * tie-breaking.
   */
  public static Optional<StanceChoice> selectNextStance(
      TerraformSpec.Pos start,
      Predicate<TerraformSpec.Pos> walkableStance,
      BiPredicate<TerraformSpec.Pos, TerraformSpec.Pos> allowedEdge,
      List<TerraformSpec.Pos> readyTargets,
      BiPredicate<TerraformSpec.Pos, TerraformSpec.Pos> canActFrom,
      int maxWalkNodes) {
    if (readyTargets.isEmpty()) return Optional.empty();
    Map<Long, Integer> stepsMap = new LinkedHashMap<>();
    ArrayDeque<TerraformSpec.Pos> q = new ArrayDeque<>();
    long sk = packXYZ(start.x(), start.y(), start.z());
    if (walkableStance.test(start)) {
      stepsMap.put(sk, 0);
      q.add(start);
    }
    List<TerraformSpec.Pos> visited = new ArrayList<>();
    while (!q.isEmpty() && visited.size() < maxWalkNodes) {
      var cur = q.removeFirst();
      visited.add(cur);
      int curSteps = stepsMap.get(packXYZ(cur.x(), cur.y(), cur.z()));
      for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
        for (int dy : new int[] {0, 1, -1}) {
          var nxt = new TerraformSpec.Pos(cur.x() + d[0], cur.y() + dy, cur.z() + d[1]);
          long nk = packXYZ(nxt.x(), nxt.y(), nxt.z());
          if (stepsMap.containsKey(nk)) continue;
          if (!walkableStance.test(nxt)) continue;
          if (allowedEdge != null && !allowedEdge.test(cur, nxt)) continue;
          stepsMap.put(nk, curSteps + 1);
          q.addLast(nxt);
        }
      }
    }

    StanceChoice best = null;
    double bestScore = -1.0;
    int cap = Math.min(readyTargets.size(), 96);
    for (TerraformSpec.Pos cand : visited) {
      int cover = 0;
      int firstIdx = Integer.MAX_VALUE;
      for (int i = 0; i < cap; i++) {
        TerraformSpec.Pos t = readyTargets.get(i);
        int dx = cand.x() - t.x(), dy = cand.y() - t.y(), dz = cand.z() - t.z();
        if (dx * dx + dy * dy + dz * dz > 36) continue;
        if (canActFrom.test(cand, t)) {
          cover++;
          if (i < firstIdx) firstIdx = i;
        }
      }
      if (cover == 0) continue;
      int steps = stepsMap.get(packXYZ(cand.x(), cand.y(), cand.z()));
      double score = (double) cover / (steps * 4.0 + 6.0);
      if (best == null
          || score > bestScore + 1e-9
          || (Math.abs(score - bestScore) <= 1e-9
              && (steps < best.routeSteps()
                  || (steps == best.routeSteps() && firstIdx < best.firstOpIndex())))) {
        best = new StanceChoice(cand.x(), cand.y(), cand.z(), cover, steps, firstIdx);
        bestScore = score;
      }
    }
    return Optional.ofNullable(best);
  }

  public static boolean shouldPauseForScreen(
      boolean screenOpen, boolean isInventoryScreen, boolean opManagingScreen) {
    return screenOpen && !(isInventoryScreen && opManagingScreen);
  }

  public static long packXZ(int x, int z) {
    return (((long) x) << 32) ^ (z & 0xffffffffL);
  }

  public static int unpackX(long k) {
    return (int) (k >> 32);
  }

  public static int unpackZ(long k) {
    return (int) k;
  }

  public static long packXYZ(int x, int y, int z) {
    return (((long) (x & 0x3FFFFFF)) << 38)
        | (((long) (z & 0x3FFFFFF)) << 12)
        | (y & 0xFFFL);
  }
}
