package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TerraformPlannerTest {

  private static JsonObject baseBox(
      int x0, int y0, int z0, int x1, int y1, int z1, String mode) {
    var o = new JsonObject();
    o.addProperty("min_x", x0);
    o.addProperty("min_y", y0);
    o.addProperty("min_z", z0);
    o.addProperty("max_x", x1);
    o.addProperty("max_y", y1);
    o.addProperty("max_z", z1);
    o.addProperty("mode", mode);
    return o;
  }

  private static TerraformPlanner.CellState solid(String id) {
    return new TerraformPlanner.CellState(
        true, id, TerraformPlanner.BlockKind.NATURAL_SOLID, false, false, true, true);
  }

  private static TerraformPlanner.CellState falling(String id) {
    return new TerraformPlanner.CellState(
        true, id, TerraformPlanner.BlockKind.FALLING_SOLID, false, false, true, true);
  }

  private static TerraformPlanner.CellState protectedBlk(String id, boolean entity) {
    return new TerraformPlanner.CellState(
        true, id, TerraformPlanner.BlockKind.PROTECTED, entity, false, true, true);
  }

  @Test
  void strictSpecParsingEnforcesBoundsOverflowCrossModeAndPermissions() {
    // Reversed bounds
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          var o = baseBox(10, 60, 0, 5, 65, 5, "flatten");
          o.addProperty("variant", "cut_only");
          o.addProperty("target_y", 60);
          TerraformSpec.parse(o, false);
        });

    // Overflow / oversized XZ (>64) and volume (>32768)
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          var o = baseBox(0, 60, 0, 65, 62, 10, "flatten");
          o.addProperty("variant", "cut_only");
          o.addProperty("target_y", 60);
          TerraformSpec.parse(o, false);
        });
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          var o = baseBox(0, 0, 0, 63, 15, 63, "flatten");
          o.addProperty("variant", "cut_only");
          o.addProperty("target_y", 0);
          TerraformSpec.parse(o, false);
        });

    // Cross-mode field rejection & unknown key rejection
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          var o = baseBox(0, 60, 0, 5, 65, 5, "flatten");
          o.addProperty("variant", "cut_only");
          o.addProperty("target_y", 60);
          o.addProperty("block", "minecraft:dirt");
          TerraformSpec.parse(o, false);
        });
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          var o = baseBox(0, 60, 0, 5, 65, 5, "fill");
          o.addProperty("variant", "replace_air");
          o.addProperty("block", "minecraft:stone");
          o.addProperty("replace_natural", true);
          TerraformSpec.parse(o, false);
        });
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          var o = baseBox(0, 60, 0, 8, 60, 8, "farm_grid");
          o.addProperty("target_y", 60);
          o.addProperty("crop", "minecraft:wheat");
          TerraformSpec.parse(o, false); // max_y must be >= target_y + 1
        });
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          var o = baseBox(0, 60, 0, 5, 65, 5, "flatten");
          o.addProperty("variant", "cut_only");
          o.addProperty("target_y", 60);
          o.addProperty("cut_blocks", "minecraft:chest"); // cannot expand whitelist
          TerraformSpec.parse(o, false);
        });

    // RequiredPermissions matrix
    var cutOnlyArgs = baseBox(0, 60, 0, 5, 65, 5, "flatten");
    cutOnlyArgs.addProperty("variant", "cut_only");
    cutOnlyArgs.addProperty("target_y", 60);
    var cutSpec = TerraformSpec.parse(cutOnlyArgs, true);
    assertTrue(cutSpec.requiredPermissions().mine());
    assertFalse(cutSpec.requiredPermissions().place());

    var airArgs = baseBox(0, 60, 0, 5, 65, 5, "fill");
    airArgs.addProperty("variant", "replace_air");
    airArgs.addProperty("block", "minecraft:stone");
    var airSpec = TerraformSpec.parse(airArgs, false);
    assertFalse(airSpec.requiredPermissions().mine());
    assertTrue(airSpec.requiredPermissions().place());
  }

  @Test
  void flattenOrdersStrictlyTopDownIsolatesExternalFallingColumnAndProtectedBlocks() {
    var args = baseBox(0, 62, 0, 2, 65, 0, "flatten");
    args.addProperty("variant", "cut_only");
    args.addProperty("target_y", 62);
    TerraformSpec spec = TerraformSpec.parse(args, false);

    // Column x=0: normal stone up to y=65; x=1: gravel extending above maxY (y=66); x=2: chest at y=63
    TerraformPlanner.GridView view =
        (x, y, z) -> {
          if (z != 0) return TerraformPlanner.CellState.air();
          if (y <= 62) return solid("minecraft:stone");
          if (x == 0 && y <= 65) return solid("minecraft:stone");
          if (x == 1 && y <= 66) return falling("minecraft:gravel");
          if (x == 2 && y == 63) return protectedBlk("minecraft:chest", true);
          return TerraformPlanner.CellState.air();
        };

    var plan = TerraformPlanner.plan(spec, view, new TerraformSpec.Pos(-1, 63, 0));
    // Column x=1 skipped due to FALLING_COLUMN_ABOVE; chest at x=2,y=63 blocked as PROTECTED_BLOCK
    assertEquals(1, plan.blockerTotals().getOrDefault("FALLING_COLUMN_ABOVE", 0));
    assertEquals(1, plan.blockerTotals().getOrDefault("PROTECTED_BLOCK", 0));

    // Only x=0 layers y=65, 64, 63 must be planned in strict descending Y order (never cutting y=62!)
    assertEquals(3, plan.ops().size());
    assertEquals(65, plan.ops().get(0).y());
    assertEquals(64, plan.ops().get(1).y());
    assertEquals(63, plan.ops().get(2).y());
    for (var op : plan.ops()) assertNotEquals(62, op.y());
  }

  @Test
  void cutAndFillEnforcesSolidFoundationAndHollowFillDeduplicatesThinShells() {
    var cutFillArgs = baseBox(0, 62, 0, 1, 63, 0, "flatten");
    cutFillArgs.addProperty("variant", "cut_and_fill");
    cutFillArgs.addProperty("target_y", 62);
    cutFillArgs.addProperty("block", "minecraft:dirt");
    TerraformPlanner.GridView fillView =
        (x, y, z) -> {
          if (y == 61 && x == 0 && z == 0) return solid("minecraft:stone");
          return TerraformPlanner.CellState.air(); // x=1 has void below y=62
        };
    var planCF =
        TerraformPlanner.plan(
            TerraformSpec.parse(cutFillArgs, false), fillView, new TerraformSpec.Pos(0, 63, 1));
    assertEquals(1, planCF.ops().size());
    assertEquals(0, planCF.ops().get(0).x());
    assertEquals(62, planCF.ops().get(0).y());
    assertEquals(1, planCF.blockerTotals().getOrDefault("NO_FOUNDATION", 0));

    // Hollow 2x2x2 has 8 shell cells and zero interior; deduplicated!
    var hollowArgs = baseBox(0, 60, 0, 1, 61, 1, "fill");
    hollowArgs.addProperty("variant", "hollow");
    hollowArgs.addProperty("block", "minecraft:stone_bricks");
    var hollowPlan =
        TerraformPlanner.plan(
            TerraformSpec.parse(hollowArgs, false),
            (x, y, z) -> TerraformPlanner.CellState.air(),
            new TerraformSpec.Pos(-1, 60, 0));
    assertEquals(8, hollowPlan.ops().size());

    // Replace_air skips grass/water
    var airArgs = baseBox(0, 60, 0, 1, 60, 0, "fill");
    airArgs.addProperty("variant", "replace_air");
    airArgs.addProperty("block", "minecraft:cobblestone");
    var airPlan =
        TerraformPlanner.plan(
            TerraformSpec.parse(airArgs, false),
            (x, y, z) ->
                x == 0
                    ? TerraformPlanner.CellState.air()
                    : new TerraformPlanner.CellState(
                        true,
                        "minecraft:grass",
                        TerraformPlanner.BlockKind.REPLACEABLE_PLANT,
                        false,
                        false,
                        false,
                        false),
            new TerraformSpec.Pos(-1, 60, 0));
    assertEquals(1, airPlan.ops().size());
    assertEquals(1, airPlan.preserved());
  }

  @Test
  void farmGridComputesOptimal9x9And10x10WaterCoverageDegenerateSizesAndExitConnectivity() {
    // 1x1 rejected cleanly
    var tiny = baseBox(0, 62, 0, 0, 63, 0, "farm_grid");
    tiny.addProperty("target_y", 62);
    tiny.addProperty("crop", "minecraft:wheat");
    var tinyPlan =
        TerraformPlanner.plan(
            TerraformSpec.parse(tiny, false),
            (x, y, z) -> y <= 62 ? solid("minecraft:dirt") : TerraformPlanner.CellState.air(),
            new TerraformSpec.Pos(-1, 63, 0));
    assertTrue(tinyPlan.hasHardBlockers());
    assertEquals(1, tinyPlan.blockerTotals().getOrDefault("DEGENERATE_FARM_SIZE", 0));

    // 9x9: 1 water source, 80 crop cells, all covered within <= 4 blocks, 0 wasted walkway
    var f9 = baseBox(0, 62, 0, 8, 63, 8, "farm_grid");
    f9.addProperty("target_y", 62);
    f9.addProperty("crop", "minecraft:wheat");
    TerraformPlanner.GridView flatDirt =
        (x, y, z) -> y <= 62 ? solid("minecraft:dirt") : TerraformPlanner.CellState.air();
    var p9 = TerraformPlanner.plan(TerraformSpec.parse(f9, false), flatDirt, new TerraformSpec.Pos(-1, 63, 4));
    assertFalse(p9.hasHardBlockers());
    assertEquals(1, p9.farmLayout().waterCells().size());
    assertEquals(80, p9.farmLayout().cropCells().size());
    assertEquals(80, p9.farmLayout().coveredCells());
    assertEquals(0, p9.farmLayout().reservedPaths().size());
    assertNotNull(p9.farmLayout().exitCell());

    // 10x10: segmented 2x2=4 water sources cover all 96 soil cells including tail edges
    var f10 = baseBox(0, 62, 0, 9, 63, 9, "farm_grid");
    f10.addProperty("target_y", 62);
    f10.addProperty("crop", "minecraft:carrots");
    var p10 = TerraformPlanner.plan(TerraformSpec.parse(f10, false), flatDirt, new TerraformSpec.Pos(4, 63, -1));
    assertFalse(p10.hasHardBlockers());
    assertEquals(4, p10.farmLayout().waterCells().size());
    assertEquals(96, p10.farmLayout().coveredCells());
    assertEquals(96, p10.farmLayout().totalSoilCells());

    // Simulated backing-out check: after peeling cropCells in order, the remaining un-hoed dirt always connects to exitCell
    Set<Long> unhoed = new HashSet<>();
    for (var c : p9.farmLayout().cropCells()) unhoed.add(TerraformPlanner.packXZ(c.x(), c.z()));
    var exit = p9.farmLayout().exitCell();
    for (var c : p9.farmLayout().cropCells()) {
      unhoed.remove(TerraformPlanner.packXZ(c.x(), c.z()));
      // Every remaining cell in unhoed must still reach exit without touching hoed cells or water
      Set<Long> passable = new HashSet<>(unhoed);
      passable.add(TerraformPlanner.packXZ(exit.x(), exit.z()));
      ArrayDeque<Long> q = new ArrayDeque<>();
      Set<Long> seen = new HashSet<>();
      long root = TerraformPlanner.packXZ(exit.x(), exit.z());
      q.add(root);
      seen.add(root);
      while (!q.isEmpty()) {
        long cur = q.removeFirst();
        int cx = TerraformPlanner.unpackX(cur), cz = TerraformPlanner.unpackZ(cur);
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
          long nk = TerraformPlanner.packXZ(cx + d[0], cz + d[1]);
          if (!seen.contains(nk) && passable.contains(nk)) {
            seen.add(nk);
            q.addLast(nk);
          }
        }
      }
      for (long rem : unhoed) {
        assertTrue(seen.contains(rem), "Trapped cell during backing-out hoeing!");
      }
    }

    // Island with no outer walkable border -> reserves 1 interior exit cell
    var islandView =
        (TerraformPlanner.GridView)
            (x, y, z) -> {
              if (x >= 0 && x <= 8 && z >= 0 && z <= 8 && y <= 62) return solid("minecraft:dirt");
              if (y <= 61) return solid("minecraft:dirt");
              return TerraformPlanner.CellState.air();
            };
    var islandPlan =
        TerraformPlanner.plan(
            TerraformSpec.parse(f9, false), islandView, new TerraformSpec.Pos(0, 63, 0));
    assertFalse(islandPlan.hasHardBlockers());
    assertEquals(1, islandPlan.farmLayout().reservedPaths().size());
    assertEquals(79, islandPlan.farmLayout().cropCells().size());
  }

  @Test
  void dynamicStanceSelectionCoversClusterAndPicksSingleWalk() {
    var targets =
        List.of(
            new TerraformSpec.Pos(4, 63, 4),
            new TerraformSpec.Pos(5, 63, 4),
            new TerraformSpec.Pos(6, 63, 4),
            new TerraformSpec.Pos(4, 63, 5));
    var choice =
        TerraformPlanner.selectNextStance(
            new TerraformSpec.Pos(0, 64, 0),
            p -> p.y() == 64 && p.x() >= 0 && p.x() <= 8 && p.z() >= 0 && p.z() <= 8,
            null,
            targets,
            (s, t) ->
                (s.x() - t.x()) * (s.x() - t.x()) + (s.z() - t.z()) * (s.z() - t.z()) <= 16,
            256);
    assertTrue(choice.isPresent());
    assertEquals(4, choice.get().coverCount());
  }

  @Test
  void farmGridResampleAfterPartialProgressIsIdempotentAndSkipsFarmlandTransit() {
    var f9 = baseBox(0, 62, 0, 8, 63, 8, "farm_grid");
    f9.addProperty("target_y", 62);
    f9.addProperty("cover_slab", "minecraft:stone_brick_slab");
    f9.addProperty("crop", "minecraft:wheat");

    var farmland =
        new TerraformPlanner.CellState(
            true, "minecraft:farmland", TerraformPlanner.BlockKind.FARMLAND, false, false, true, false);
    var wheat =
        new TerraformPlanner.CellState(
            true,
            "minecraft:wheat",
            TerraformPlanner.BlockKind.REPLACEABLE_PLANT,
            false,
            false,
            false,
            false);
    var waterloggedSlab =
        new TerraformPlanner.CellState(
            true,
            "minecraft:stone_brick_slab",
            TerraformPlanner.BlockKind.WATER_SOURCE,
            false,
            true,
            true,
            true);

    TerraformPlanner.GridView partialView =
        (x, y, z) -> {
          if (y < 62) return solid("minecraft:stone");
          if (y == 62) {
            if (x == 4 && z == 4) return waterloggedSlab;
            if ((x == 8 && z == 8) || (x == 8 && z == 7) || (x == 7 && z == 8)) return farmland;
            return solid("minecraft:grass_block");
          }
          if (y == 63 && ((x == 8 && z == 8) || (x == 8 && z == 7))) return wheat;
          return TerraformPlanner.CellState.air();
        };

    var plan =
        TerraformPlanner.plan(
            TerraformSpec.parse(f9, false), partialView, new TerraformSpec.Pos(-1, 63, 4));
    assertFalse(plan.hasHardBlockers());

    long breakCount =
        plan.ops().stream().filter(o -> o.kind() == TerraformPlanner.OpKind.BREAK).count();
    long waterCount =
        plan.ops().stream().filter(o -> o.kind() == TerraformPlanner.OpKind.PLACE_WATER).count();
    long slabCount =
        plan.ops().stream().filter(o -> o.kind() == TerraformPlanner.OpKind.PLACE_SLAB).count();
    List<TerraformPlanner.WorkOp> hoeOps =
        plan.ops().stream().filter(o -> o.kind() == TerraformPlanner.OpKind.HOE).toList();
    List<TerraformPlanner.WorkOp> seedOps =
        plan.ops().stream().filter(o -> o.kind() == TerraformPlanner.OpKind.SEED).toList();

    assertEquals(0, breakCount);
    assertEquals(0, waterCount);
    assertEquals(0, slabCount);
    assertEquals(77, hoeOps.size());
    assertEquals(78, seedOps.size());

    Set<TerraformSpec.Pos> alreadyFarmland =
        Set.of(
            new TerraformSpec.Pos(8, 62, 8),
            new TerraformSpec.Pos(8, 62, 7),
            new TerraformSpec.Pos(7, 62, 8));
    assertTrue(hoeOps.stream().noneMatch(o -> alreadyFarmland.contains(o.pos())));
  }
}
