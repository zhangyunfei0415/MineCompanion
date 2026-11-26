package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class BlueprintScaffoldPlannerTest {
  @Test
  void selectMaterialPrefersScaffoldingAndNeverStealsBlueprintBomItems() {
    var bom = Map.of("minecraft:cobblestone", 20, "minecraft:oak_planks", 10);

    // 1. Both scaffolding & surplus cobblestone -> AUTO picks scaffolding
    var withScaff =
        BlueprintScaffoldPlanner.selectMaterial(
            BlueprintScaffoldPlanner.Mode.AUTO,
            "",
            Map.of("minecraft:scaffolding", 12, "minecraft:cobblestone", 30),
            bom,
            4,
            10);
    assertEquals(BlueprintScaffoldPlanner.Mode.SCAFFOLDING, withScaff.resolvedMode());
    assertEquals("minecraft:scaffolding", withScaff.blockId());
    assertEquals("", withScaff.error());

    // 2. Cobblestone count == BOM need (20) -> surplus is 0 -> falls back to dirt (never steals BOM!)
    var bomProtected =
        BlueprintScaffoldPlanner.selectMaterial(
            BlueprintScaffoldPlanner.Mode.AUTO,
            "",
            Map.of("minecraft:cobblestone", 20, "minecraft:dirt", 16),
            bom,
            3,
            8);
    assertEquals(BlueprintScaffoldPlanner.Mode.SOLID_RAMP, bomProtected.resolvedMode());
    assertEquals("minecraft:dirt", bomProtected.blockId());
    assertEquals(16, bomProtected.availableSurplus());

    // 3. No surplus -> reports NEED_SCAFFOLD honestly
    var missing =
        BlueprintScaffoldPlanner.selectMaterial(
            BlueprintScaffoldPlanner.Mode.AUTO,
            "",
            Map.of("minecraft:cobblestone", 20),
            bom,
            4,
            10);
    assertTrue(missing.error().startsWith("[NEED_SCAFFOLD]"), missing.error());

    // 4. Rejects sand/tnt explicit blocks
    var dangerous =
        BlueprintScaffoldPlanner.selectMaterial(
            BlueprintScaffoldPlanner.Mode.AUTO, "minecraft:sand", Map.of("minecraft:sand", 64), bom, 3, 6);
    assertFalse(dangerous.error().isBlank());
  }

  @Test
  void hollowPerimeterRampUsesTwoBlocksPerVerticalStepAndGuaranteesLifoSupport() {
    // 41x19 blueprint box rising 26 blocks (similar to MCS Fumo upper layers)
    int minX = 40, maxX = 80, minZ = 35, maxZ = 53, groundY = -60, standY = -34;
    var plan =
        BlueprintScaffoldPlanner.planHollowPerimeterRamp(
            minX, maxX, minZ, maxZ, groundY, standY, "north", Set.of());
    assertFalse(plan.isEmpty());
    // 26 rise * 2 + corner/catwalk should stay well below ~75 blocks (unlike solid pyramid ~350+)
    assertTrue(plan.size() < 75, "hollow ramp too large: " + plan.size());

    // Zero overlap inside blueprint footprint & every cell has ground or prior step neighbor
    Set<String> built = new HashSet<>();
    for (var c : plan) {
      boolean outsideBox =
          c.wx() < minX || c.wx() > maxX || c.wz() < minZ || c.wz() > maxZ;
      assertTrue(outsideBox, "scaffold must stay outside blueprint footprint");
      if (c.wy() > groundY) {
        boolean supported =
            built.contains(BlueprintScaffoldPlanner.posKey(c.wx(), c.wy() - 1, c.wz()))
                || built.contains(BlueprintScaffoldPlanner.posKey(c.wx() + 1, c.wy(), c.wz()))
                || built.contains(BlueprintScaffoldPlanner.posKey(c.wx() - 1, c.wy(), c.wz()))
                || built.contains(BlueprintScaffoldPlanner.posKey(c.wx(), c.wy(), c.wz() + 1))
                || built.contains(BlueprintScaffoldPlanner.posKey(c.wx(), c.wy(), c.wz() - 1))
                || built.contains(BlueprintScaffoldPlanner.posKey(c.wx() + 1, c.wy() - 1, c.wz()))
                || built.contains(BlueprintScaffoldPlanner.posKey(c.wx() - 1, c.wy() - 1, c.wz()))
                || built.contains(BlueprintScaffoldPlanner.posKey(c.wx(), c.wy() - 1, c.wz() + 1))
                || built.contains(BlueprintScaffoldPlanner.posKey(c.wx(), c.wy() - 1, c.wz() - 1));
        assertTrue(supported, "cell must remain reachable/supported for LIFO teardown: " + c);
      }
      built.add(BlueprintScaffoldPlanner.posKey(c.wx(), c.wy(), c.wz()));
    }
  }

  @Test
  void plansScaffoldingTowerOverhangAndTempSupportAxisPos() {
    var tower =
        BlueprintScaffoldPlanner.planScaffoldingTower(10, 64, 10, 70, 0, 1, 3, Set.of());
    assertEquals(6 + 3, tower.size()); // 64..69 vertical + 3 overhang blocks at y=69

    var logStep =
        new BlueprintPlanner.BuildStep(
            5, 67, 5, "minecraft:oak_log", "minecraft:oak_log", gsonObj("axis", "x"), false);
    var bpKeys = Set.of(BlueprintScaffoldPlanner.posKey(5, 67, 5), BlueprintScaffoldPlanner.posKey(4, 67, 5));
    var aux =
        BlueprintScaffoldPlanner.pickTempSupportPos(
            logStep, bpKeys, c -> true, c -> true);
    assertNotNull(aux);
    assertEquals(6, aux.wx());
    assertEquals(67, aux.wy());
    assertEquals(5, aux.wz());
  }

  @Test
  void estimatesScaffoldNeedAccuratelyForTallAndFlatBlueprints() {
    var porchLike =
        new BlueprintFormat.BlueprintSpec(
            "porch",
            "litematic",
            5,
            4,
            5,
            List.of(new BlueprintFormat.Voxel(0, 3, 0, "minecraft:oak_stairs", Map.of("facing", "south"))),
            Map.of());
    var est = BlueprintScaffoldPlanner.estimateForBlueprint(porchLike);
    assertTrue(est.get("needs_scaffold").getAsBoolean());
    assertTrue(est.get("recommended_solid_blocks").getAsInt() >= 4);

    var carpet =
        new BlueprintFormat.BlueprintSpec(
            "flat",
            "nbt",
            3,
            1,
            3,
            List.of(new BlueprintFormat.Voxel(0, 0, 0, "minecraft:stone_bricks", Map.of())),
            Map.of());
    assertFalse(BlueprintScaffoldPlanner.estimateForBlueprint(carpet).get("needs_scaffold").getAsBoolean());
  }

  @Test
  void extendsInwardCatwalkSpurAndValidatesLifoReversibility() {
    int minX = 40, maxX = 80, minZ = 35, maxZ = 53, groundY = -60, standY = -34;
    var rampPlan =
        BlueprintScaffoldPlanner.planHollowPerimeterRamp(
            minX, maxX, minZ, maxZ, groundY, standY, "north", 60, 44, Set.of());
    assertTrue(BlueprintScaffoldPlanner.validateLifoReversible(rampPlan, Set.of()).ok());
    var towerPlan =
        BlueprintScaffoldPlanner.planScaffoldingTower(10, 64, 10, 70, 0, 1, 3, Set.of());
    assertTrue(BlueprintScaffoldPlanner.validateLifoReversible(towerPlan, Set.of()).ok());

    // Target-aware ramp should extend at least one catwalk block closer to interior target than pure outer ring
    boolean reachedInward =
        rampPlan.stream()
            .anyMatch(
                c ->
                    c.kind() == BlueprintScaffoldPlanner.Kind.CATWALK
                        && c.wz() > minZ - 2
                        && c.wx() >= minX
                        && c.wx() <= maxX);
    assertTrue(reachedInward, "wide interior target should pull catwalk spur inward");

    // Floating cell must fail LIFO reversibility
    var invalid =
        List.of(
            new BlueprintScaffoldPlanner.ScaffoldCell(0, 64, 0, BlueprintScaffoldPlanner.Kind.PILLAR),
            new BlueprintScaffoldPlanner.ScaffoldCell(0, 68, 0, BlueprintScaffoldPlanner.Kind.CATWALK));
    assertFalse(BlueprintScaffoldPlanner.validateLifoReversible(invalid, Set.of()).ok());
  }

  @Test
  void vanillaScaffolding01BfsEnforcesDistanceLimitAndMixedLifoSupport() {
    var sturdy = Set.of("0,63,0");
    var scaffolds = new LinkedHashSet<String>();
    // 5-block vertical pillar at (0,64..68,0): all distance 0.
    for (int y = 64; y <= 68; y++) scaffolds.add(BlueprintScaffoldPlanner.posKey(0, y, 0));
    // Overhang 1..6 horizontally along +X: distances 1..6 valid, 7th fails.
    for (int dx = 1; dx <= 6; dx++) scaffolds.add(BlueprintScaffoldPlanner.posKey(dx, 68, 0));
    var validDist =
        BlueprintScaffoldPlanner.computeVanillaScaffoldDistances(
            scaffolds, p -> sturdy.contains(BlueprintScaffoldPlanner.posKey(p[0], p[1], p[2])));
    assertEquals(0, validDist.get("0,68,0"));
    assertEquals(6, validDist.get("6,68,0"));

    scaffolds.add("7,68,0");
    var invalidDist =
        BlueprintScaffoldPlanner.computeVanillaScaffoldDistances(
            scaffolds, p -> sturdy.contains(BlueprintScaffoldPlanner.posKey(p[0], p[1], p[2])));
    assertEquals(7, invalidDist.get("7,68,0"));

    var mixedPlan = new ArrayList<BlueprintScaffoldPlanner.ScaffoldCell>();
    for (int y = 64; y <= 68; y++) {
      mixedPlan.add(
          new BlueprintScaffoldPlanner.ScaffoldCell(0, y, 0, BlueprintScaffoldPlanner.Kind.SCAFFOLD_TOWER));
    }
    for (int dx = 1; dx <= 6; dx++) {
      mixedPlan.add(
          new BlueprintScaffoldPlanner.ScaffoldCell(dx, 68, 0, BlueprintScaffoldPlanner.Kind.SCAFFOLD_OVERHANG));
    }
    assertTrue(
        BlueprintScaffoldPlanner.validateVanillaScaffoldingLifo(
                mixedPlan, p -> sturdy.contains(BlueprintScaffoldPlanner.posKey(p[0], p[1], p[2])), Set.of())
            .ok());

    mixedPlan.add(
        new BlueprintScaffoldPlanner.ScaffoldCell(7, 68, 0, BlueprintScaffoldPlanner.Kind.SCAFFOLD_OVERHANG));
    assertFalse(
        BlueprintScaffoldPlanner.validateVanillaScaffoldingLifo(
                mixedPlan, p -> sturdy.contains(BlueprintScaffoldPlanner.posKey(p[0], p[1], p[2])), Set.of())
            .ok());
  }

  @Test
  void planClusterBuildsInteriorRelayPillarsAndValidPlatformsForWideRoofs() {
    // Interior ring of a 41x27x19-style concave roof well away from perimeters at y=-45 (ground=-60).
    var targets = new ArrayList<BlueprintScaffoldPlanner.Pos>();
    for (int x = 18; x <= 24; x += 2) {
      for (int z = 12; z <= 16; z += 2) {
        targets.add(new BlueprintScaffoldPlanner.Pos(x, -45, z));
      }
    }
    var world = BlueprintScaffoldPlanner.WorldView.flatGround(-61, Set.of());
    var clusterPlan =
        BlueprintScaffoldPlanner.planCluster(
            world,
            targets,
            new BlueprintScaffoldPlanner.Pos(0, -60, 0),
            BlueprintScaffoldPlanner.Mode.SOLID_RAMP,
            4.2,
            Set.of());
    assertTrue(clusterPlan.usesRelayPillar());
    assertTrue(clusterPlan.validLifo());
    assertTrue(clusterPlan.uncoveredTargets().isEmpty());
    assertFalse(clusterPlan.buildOrder().isEmpty());
    assertFalse(clusterPlan.poses().isEmpty());
  }

  @Test
  void planClusterReportsUnbuildableTargetInUncoveredAndRejectsDuplicateCoords() {
    var unreachable = new BlueprintScaffoldPlanner.Pos(10, -45, 10);
    // Block the whole neighborhood around unreachable so no ground pillar can be built.
    var blocked = new HashSet<String>();
    for (int dx = -4; dx <= 4; dx++) {
      for (int dz = -4; dz <= 4; dz++) {
        blocked.add(BlueprintScaffoldPlanner.posKey(10 + dx, -61, 10 + dz));
      }
    }
    var world = BlueprintScaffoldPlanner.WorldView.flatGround(-61, blocked);
    var plan =
        BlueprintScaffoldPlanner.planCluster(
            world,
            List.of(unreachable),
            new BlueprintScaffoldPlanner.Pos(0, -60, 0),
            BlueprintScaffoldPlanner.Mode.SOLID_RAMP,
            4.2,
            Set.of());
    assertEquals(List.of(unreachable), plan.uncoveredTargets());
    assertTrue(plan.buildOrder().isEmpty());

    var dup =
        List.of(
            new BlueprintScaffoldPlanner.ScaffoldCell(0, 64, 0, BlueprintScaffoldPlanner.Kind.SCAFFOLD_TOWER),
            new BlueprintScaffoldPlanner.ScaffoldCell(0, 64, 0, BlueprintScaffoldPlanner.Kind.SCAFFOLD_TOWER));
    assertFalse(
        BlueprintScaffoldPlanner.validateVanillaScaffoldingLifo(
                dup, p -> p[1] == 63, Set.of())
            .ok());
  }

  private static com.google.gson.JsonObject gsonObj(String k, String v) {
    var o = new com.google.gson.JsonObject();
    o.addProperty(k, v);
    return o;
  }
}
