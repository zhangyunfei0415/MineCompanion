package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class BlueprintPlannerTest {
  @Test
  void mapsAttachedItemsStripsRuntimeStatesAndOrdersBottomUp() {
    var voxels =
        List.of(
            new BlueprintFormat.Voxel(
                0, 1, 0, "minecraft:wall_torch", Map.of("facing", "north", "lit", "true")),
            new BlueprintFormat.Voxel(
                0, 0, 0, "minecraft:oak_slab", Map.of("type", "double", "waterlogged", "true")),
            new BlueprintFormat.Voxel(
                1,
                0,
                0,
                "minecraft:oak_stairs",
                Map.of("facing", "east", "half", "top", "shape", "straight", "waterlogged", "false")));
    var spec =
        new BlueprintFormat.BlueprintSpec(
            "test", "nbt", 2, 2, 1, voxels, Map.of("minecraft:oak_slab", 1));

    var bom =
        BlueprintPlanner.diffBom(
            spec,
            Map.of("minecraft:torch", 1),
            Map.of("minecraft:oak_slab", 1));
    assertEquals(3, bom.size());
    var slabRow = bom.stream().filter(r -> r.itemId().equals("minecraft:oak_slab")).findFirst().orElseThrow();
    assertEquals(2, slabRow.need()); // type=double needs 2 slabs
    assertEquals(1, slabRow.missing());

    var filteredStairs =
        BlueprintPlanner.filterPlacementProperties(voxels.get(2).properties());
    assertEquals("east", filteredStairs.get("facing").getAsString());
    assertEquals("top", filteredStairs.get("half").getAsString());
    assertFalse(filteredStairs.has("shape"));
    assertFalse(filteredStairs.has("waterlogged"));

    var filteredDoubleSlab =
        BlueprintPlanner.filterPlacementProperties(voxels.get(1).properties());
    assertFalse(filteredDoubleSlab.has("type"));

    var queue = BlueprintPlanner.buildOrder(spec, 100, 64, 200, 0, 5);
    assertEquals(3, queue.size());
    assertEquals(64, queue.get(0).wy());
    assertEquals(64, queue.get(1).wy());
    assertEquals(65, queue.get(2).wy());
    assertEquals("minecraft:torch", queue.get(2).itemId());
  }

  @Test
  void prioritizesSupportedRoofPerimeterBeforeUnsupportedCenterAndVerifiesState() {
    // Y=0..2 perimeter wall ring (hollow center at (1, *, 1)), Y=3 solid 3x3 roof including center (1, 3, 1)
    List<BlueprintFormat.Voxel> voxels = new ArrayList<>();
    for (int y = 0; y <= 2; y++) {
      for (int x = 0; x <= 2; x++) {
        for (int z = 0; z <= 2; z++) {
          if (x == 1 && z == 1) continue;
          voxels.add(new BlueprintFormat.Voxel(x, y, z, "minecraft:stone_bricks", Map.of()));
        }
      }
    }
    for (int x = 0; x <= 2; x++) {
      for (int z = 0; z <= 2; z++) {
        voxels.add(
            new BlueprintFormat.Voxel(
                x,
                3,
                z,
                x == 0 ? "minecraft:oak_stairs" : "minecraft:oak_planks",
                x == 0 ? Map.of("facing", "east", "half", "bottom") : Map.of()));
      }
    }
    var spec =
        new BlueprintFormat.BlueprintSpec("hollow-tower", "litematic", 3, 4, 3, voxels, Map.of());
    var queue = BlueprintPlanner.buildOrder(spec, 0, 0, 0, 0, 3);
    int centerRoofIdx = -1;
    int firstRoofIdx = -1;
    BlueprintPlanner.BuildStep stairStep = null;
    for (int i = 0; i < queue.size(); i++) {
      var s = queue.get(i);
      if (s.wy() == 3) {
        if (firstRoofIdx < 0) firstRoofIdx = i;
        if (s.wx() == 1 && s.wz() == 1) centerRoofIdx = i;
        if (s.blockId().equals("minecraft:oak_stairs") && stairStep == null) stairStep = s;
      }
    }
    assertTrue(centerRoofIdx > firstRoofIdx, "unsupported center roof must schedule after perimeter");
    assertNotNull(stairStep);

    assertEquals(
        BlueprintPlanner.MatchStatus.MATCHED,
        BlueprintPlanner.classifyBlock(
            "minecraft:oak_stairs",
            Map.of("facing", "east", "half", "bottom", "waterlogged", "false"),
            false,
            stairStep));
    assertEquals(
        BlueprintPlanner.MatchStatus.STATE_MISMATCH,
        BlueprintPlanner.classifyBlock(
            "minecraft:oak_stairs", Map.of("facing", "west", "half", "bottom"), false, stairStep));
    assertEquals(
        BlueprintPlanner.MatchStatus.MISSING,
        BlueprintPlanner.classifyBlock("minecraft:air", Map.of(), true, stairStep));
    assertEquals(
        BlueprintPlanner.MatchStatus.BLOCKED_EXISTING,
        BlueprintPlanner.classifyBlock("minecraft:cobblestone", Map.of(), false, stairStep));
  }

  @Test
  void ordersRoofLayerInThreeWavesWithAxisLogsAndTopSlabsAfterWallsAndStairs() {
    List<BlueprintFormat.Voxel> voxels = new ArrayList<>();
    // Base Y=2 supporting perimeter
    for (int x = 0; x < 3; x++) {
      for (int z = 0; z < 3; z++) {
        voxels.add(new BlueprintFormat.Voxel(x, 2, z, "minecraft:oak_planks", Map.of()));
      }
    }
    // Roof Y=3 mixing Wave 3 torch, Wave 2 axis=x log & type=top slab, and Wave 1 stairs/planks
    voxels.add(new BlueprintFormat.Voxel(1, 3, 1, "minecraft:torch", Map.of()));
    voxels.add(new BlueprintFormat.Voxel(0, 3, 1, "minecraft:oak_log", Map.of("axis", "x")));
    voxels.add(new BlueprintFormat.Voxel(2, 3, 1, "minecraft:oak_slab", Map.of("type", "top")));
    voxels.add(
        new BlueprintFormat.Voxel(
            1, 3, 0, "minecraft:oak_stairs", Map.of("facing", "south", "half", "bottom")));

    var spec = new BlueprintFormat.BlueprintSpec("porch-layer", "litematic", 3, 4, 3, voxels, Map.of());
    var queue = BlueprintPlanner.buildOrder(spec, 0, 0, 0, 2, 3);

    int wave1StairIdx = -1, wave2LogIdx = -1, wave2TopSlabIdx = -1, wave3TorchIdx = -1;
    BlueprintPlanner.BuildStep stair = null, logX = null, topSlab = null;
    for (int i = 0; i < queue.size(); i++) {
      var s = queue.get(i);
      if (s.wy() != 3) continue;
      switch (s.blockId()) {
        case "minecraft:oak_stairs" -> {
          wave1StairIdx = i;
          stair = s;
        }
        case "minecraft:oak_log" -> {
          wave2LogIdx = i;
          logX = s;
        }
        case "minecraft:oak_slab" -> {
          wave2TopSlabIdx = i;
          topSlab = s;
        }
        case "minecraft:torch" -> wave3TorchIdx = i;
      }
    }
    assertTrue(wave1StairIdx >= 0 && wave2LogIdx > wave1StairIdx);
    assertTrue(wave2TopSlabIdx > wave1StairIdx);
    assertTrue(wave3TorchIdx > wave2LogIdx && wave3TorchIdx > wave2TopSlabIdx);
    assertFalse(BlueprintPlanner.forbidsUpFace(stair));
    assertTrue(BlueprintPlanner.forbidsUpFace(logX));
    assertTrue(BlueprintPlanner.forbidsUpFace(topSlab));
    assertEquals("north", BlueprintPlanner.requiredStanceSide(stair));
  }

  @Test
  void plansLargeFumoScaleBlueprintDeterministicallyWithinSpatialBudget() {
    // 41x27x19 shell (~6,500 voxels) matching Fumo scale
    List<BlueprintFormat.Voxel> voxels = new ArrayList<>();
    for (int y = 0; y < 27; y++) {
      for (int x = 0; x < 41; x++) {
        for (int z = 0; z < 19; z++) {
          if (x == 0 || x == 40 || z == 0 || z == 18 || y == 0 || y == 26) {
            voxels.add(new BlueprintFormat.Voxel(x, y, z, "minecraft:black_concrete", Map.of()));
          }
        }
      }
    }
    var spec =
        new BlueprintFormat.BlueprintSpec("fumo-synth", "litematic", 41, 27, 19, voxels, Map.of());
    long t0 = System.nanoTime();
    var q1 = BlueprintPlanner.buildOrder(spec, 10, -60, 10, 0, 26);
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
    var q2 = BlueprintPlanner.buildOrder(spec, 10, -60, 10, 0, 26);

    assertEquals(voxels.size(), q1.size());
    assertEquals(q1, q2);
    assertTrue(elapsedMs < 1500, "large blueprint planning took too long: " + elapsedMs + "ms");
    for (int i = 1; i < q1.size(); i++) {
      assertTrue(q1.get(i).wy() >= q1.get(i - 1).wy(), "must remain layer-monotonic");
    }
  }
}
