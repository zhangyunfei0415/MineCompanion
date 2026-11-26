package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.*;
import org.junit.jupiter.api.Test;

class TerraformExecutionPolicyTest {

  @Test
  void breakPolicySeparatesBulkAuthorityFromExplicitTargetedDismantling() {
    // Bulk terraform even at an exact coordinate must NEVER dismantle block entities/containers.
    String bulkChest = BreakPolicy.denial("minecraft:chest", true, false, false, true, true);
    assertTrue(bulkChest.contains("[BLOCK_ENTITY_PROTECTED]"));

    String targetedUnconfirmed =
        BreakPolicy.denial("minecraft:chest", true, true, false, true, true);
    assertTrue(targetedUnconfirmed.contains("[CONFIRM_REQUIRED]"));

    String targetedConfirmed = BreakPolicy.denial("minecraft:chest", true, true, true, true, true);
    assertEquals("", targetedConfirmed);
  }

  @Test
  void limitedDenialDerivesPermissionsFromTerraformSpecAndControlAction() {
    var cutOnly = new JsonObject();
    cutOnly.addProperty("min_x", 0);
    cutOnly.addProperty("min_y", 60);
    cutOnly.addProperty("min_z", 0);
    cutOnly.addProperty("max_x", 4);
    cutOnly.addProperty("max_y", 64);
    cutOnly.addProperty("max_z", 4);
    cutOnly.addProperty("mode", "flatten");
    cutOnly.addProperty("variant", "cut_only");
    cutOnly.addProperty("target_y", 60);

    // cut_only only needs mine, not place!
    assertEquals("", Rules.limitedDenial("terraform_run", cutOnly, true, false, false));
    assertFalse(Rules.limitedDenial("terraform_run", cutOnly, false, true, false).isEmpty());

    // terraform_preview and terraform_control(status) always permitted without write flags
    assertEquals("", Rules.limitedDenial("terraform_preview", cutOnly, false, false, false));
    var ctrlStatus = new JsonObject();
    ctrlStatus.addProperty("action", "status");
    assertEquals("", Rules.limitedDenial("terraform_control", ctrlStatus, false, false, false));

    var ctrlResume = new JsonObject();
    ctrlResume.addProperty("action", "resume");
    assertFalse(Rules.limitedDenial("terraform_control", ctrlResume, false, false, false).isEmpty());
  }

  @Test
  void guestSandboxAllowsPreviewButBlocksTerraformWrites() {
    assertTrue(MentionPolicy.isGuestReadOnlyTool("terraform_preview"));
    assertFalse(MentionPolicy.isGuestReadOnlyTool("terraform_run"));
    assertFalse(MentionPolicy.isGuestReadOnlyTool("terraform_control"));
  }

  @Test
  void survivalPolicyPausesOnCriticalHealthOrActiveStarvationAndExemptsCreativeOrTest() {
    assertFalse(SurvivalPolicy.shouldPauseBuildWork(2.0, 20.0, 0, true, false));
    assertFalse(SurvivalPolicy.shouldPauseBuildWork(2.0, 20.0, 0, false, true));
    assertTrue(SurvivalPolicy.shouldPauseBuildWork(19.0, 20.0, 0, false, false));
    assertTrue(SurvivalPolicy.shouldPauseBuildWork(5.0, 20.0, 20, false, false));
    assertFalse(SurvivalPolicy.shouldPauseBuildWork(20.0, 20.0, 12, false, false));
  }

  @Test
  void selectNextStanceWalksAroundOcclusionInsteadOfPickingZeroProgressSelf() {
    TerraformSpec.Pos start = new TerraformSpec.Pos(0, 64, 0);
    TerraformSpec.Pos target = new TerraformSpec.Pos(3, 66, 0);
    // Simulate wall occlusion at start (0,64,0) and at intermediate close stance (1,64,0);
    // only stepping outward/around to (-1,64,0) or (3,64,2) has unoccluded LOS.
    Set<TerraformSpec.Pos> clearLos =
        Set.of(new TerraformSpec.Pos(-1, 64, 0), new TerraformSpec.Pos(3, 64, 2));
    Set<TerraformSpec.Pos> noProgress = new HashSet<>(Set.of(start));

    var choice =
        TerraformPlanner.selectNextStance(
            start,
            p -> p.y() == 64 && Math.abs(p.x()) <= 4 && Math.abs(p.z()) <= 4,
            (a, b) -> true,
            List.of(target),
            (stPos, tgtPos) ->
                !stPos.equals(start)
                    && !noProgress.contains(stPos)
                    && clearLos.contains(stPos),
            256);

    assertTrue(choice.isPresent());
    assertNotEquals(start, new TerraformSpec.Pos(choice.get().stanceX(), choice.get().stanceY(), choice.get().stanceZ()));
    assertEquals(-1, choice.get().stanceX());
    assertEquals(1, choice.get().routeSteps());
  }

  @Test
  void selectNextStanceReturnsEmptyWhenAllCandidateStancesExhausted() {
    TerraformSpec.Pos start = new TerraformSpec.Pos(0, 64, 0);
    TerraformSpec.Pos target = new TerraformSpec.Pos(2, 66, 0);
    Set<TerraformSpec.Pos> noProgress =
        Set.of(start, new TerraformSpec.Pos(-1, 64, 0), new TerraformSpec.Pos(1, 64, 0));

    var choice =
        TerraformPlanner.selectNextStance(
            start,
            p -> p.y() == 64 && Math.abs(p.x()) <= 2 && Math.abs(p.z()) <= 2,
            (a, b) -> true,
            List.of(target),
            (stPos, tgtPos) ->
                !stPos.equals(start)
                    && !noProgress.contains(stPos)
                    && (stPos.x() == -1 || stPos.x() == 1)
                    && stPos.z() == 0,
            128);

    assertTrue(choice.isEmpty());
  }

  @Test
  void screenGuardExemptsOnlyManagedInventorySwaps() {
    assertFalse(TerraformPlanner.shouldPauseForScreen(false, false, false));
    assertFalse(TerraformPlanner.shouldPauseForScreen(true, true, true));
    assertTrue(TerraformPlanner.shouldPauseForScreen(true, true, false));
    assertTrue(TerraformPlanner.shouldPauseForScreen(true, false, true));
  }

  @Test
  void checkpointSpecRoundTripAllowsExpectedPlanHashOnResume() {
    var flatten = new JsonObject();
    flatten.addProperty("min_x", 0);
    flatten.addProperty("min_y", 60);
    flatten.addProperty("min_z", 0);
    flatten.addProperty("max_x", 4);
    flatten.addProperty("max_y", 64);
    flatten.addProperty("max_z", 4);
    flatten.addProperty("mode", "flatten");
    flatten.addProperty("variant", "cut_only");
    flatten.addProperty("target_y", 60);
    flatten.addProperty("expected_plan_hash", "deadbeef");

    var fill = new JsonObject();
    fill.addProperty("min_x", 0);
    fill.addProperty("min_y", 60);
    fill.addProperty("min_z", 0);
    fill.addProperty("max_x", 4);
    fill.addProperty("max_y", 64);
    fill.addProperty("max_z", 4);
    fill.addProperty("mode", "fill");
    fill.addProperty("variant", "hollow");
    fill.addProperty("block", "minecraft:stone_bricks");
    fill.addProperty("expected_plan_hash", "deadbeef");

    var farm = new JsonObject();
    farm.addProperty("min_x", 0);
    farm.addProperty("min_y", 62);
    farm.addProperty("min_z", 0);
    farm.addProperty("max_x", 8);
    farm.addProperty("max_y", 63);
    farm.addProperty("max_z", 8);
    farm.addProperty("mode", "farm_grid");
    farm.addProperty("target_y", 62);
    farm.addProperty("crop", "minecraft:wheat");
    farm.addProperty("expected_plan_hash", "deadbeef");

    for (JsonObject raw : List.of(flatten, fill, farm)) {
      TerraformSpec parsed = TerraformSpec.parse(raw, true);
      JsonObject saved = parsed.toJson();
      TerraformSpec resumed = TerraformSpec.parse(saved, true);
      assertEquals(parsed, resumed);
      assertEquals("deadbeef", resumed.expectedPlanHash());
      IllegalArgumentException previewEx =
          assertThrows(IllegalArgumentException.class, () -> TerraformSpec.parse(saved, false));
      assertTrue(previewEx.getMessage().contains("expected_plan_hash"));
    }
  }
}
