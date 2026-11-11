package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class FluentExecutionTest {
  @Test
  void ordinaryBowLowTrajectoryIncludesGravityAndRemainsFinite() {
    assertTrue(BowAim.elevation(12, 0, 3) > 0);
    assertTrue(BowAim.elevation(12, 3, 3) > BowAim.elevation(12, 0, 3));
    assertTrue(Double.isFinite(BowAim.elevation(1000, 1000, 3)));
    assertTrue(BowAim.chargeTicks() >= 20 && BowAim.chargeTicks() < 200);
  }

  private JsonObject input(String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }

  @Test
  void initialPlantingDoesNotImmediatelyTryToHarvestUnripePlants() {
    var p = FarmPlan.create(input("{\"crop\":\"minecraft:wheat\",\"count\":4}"));
    assertEquals(
        "plant_crops",
        p.getAsJsonArray("steps").get(0).getAsJsonObject().get("tool").getAsString());
    assertFalse(p.toString().contains("harvest_replant"));
    new SessionPlan(p);
  }

  @Test
  void immediateFullCycleReservesSeedAndKeepsEveryStepPermissionGated() {
    var p =
        FarmPlan.create(
            input(
                "{\"crop\":\"minecraft:wheat\",\"count\":4,\"fertilize\":true,\"source\":\"seed-box\"}"));
    var steps = p.getAsJsonArray("steps");
    assertEquals(8, steps.get(0).getAsJsonObject().getAsJsonObject("args").get("count").getAsInt());
    assertEquals(
        8,
        steps
            .get(1)
            .getAsJsonObject()
            .getAsJsonObject("requires")
            .get("minecraft:wheat_seeds")
            .getAsInt());
    assertTrue(p.toString().contains("harvest_replant"));
    new SessionPlan(p);
  }

  @Test
  void existingCropsSkipPlantingAndDefaultToHarvest() {
    var p = FarmPlan.create(input("{\"crop\":\"minecraft:carrot\",\"plant\":false}"));
    assertFalse(p.toString().contains("plant_crops"));
    assertTrue(p.toString().contains("minecraft:carrots"));
    assertTrue(p.toString().contains("harvest_replant"));
  }

  @Test
  void cropAliasesHaveCorrectPlantingItems() {
    assertEquals("minecraft:carrot", CropSpec.of("minecraft:carrots").seed());
    assertEquals("minecraft:potatoes", CropSpec.of("minecraft:potato").block());
    assertEquals("minecraft:beetroot_seeds", CropSpec.of("minecraft:beetroots").seed());
    assertThrows(IllegalArgumentException.class, () -> CropSpec.of("minecraft:grass_block"));
  }

  @Test
  void resourceLimitsAndEmptyProceduresFailBeforeActions() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FarmPlan.create(input("{\"crop\":\"minecraft:wheat\",\"count\":65}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> FarmPlan.create(input("{\"crop\":\"minecraft:wheat\",\"max_uses\":257}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FarmPlan.create(
                input(
                    "{\"crop\":\"minecraft:wheat\",\"plant\":false,\"harvest\":false,\"collect\":false}")));
  }

  @Test
  void usingAnItemOrConsumingItAloneDoesNotProveGenericWorldSuccess() {
    assertFalse(BlockUseProof.verified(false, false, true));
    assertFalse(BlockUseProof.verified(true, false, false));
    assertEquals("consumed_effect_unconfirmed", BlockUseProof.outcome(false, 1));
    assertTrue(BlockUseProof.verified(true, false, true));
  }

  @Test
  void controllerCanDetachWithoutLosingFiniteJobReceipt() {
    var s = new HarnessSession();
    String old = s.connect("A", 0);
    var receipt = s.begin("job", "farm:1", 2, "farm");
    s.revokeLease();
    assertFalse(s.active(1));
    assertEquals("running", receipt.get("state").getAsString());
    String fresh = s.connect("A", 2);
    assertThrows(IllegalArgumentException.class, () -> s.check(old, "A", 3));
    s.check(fresh, "A", 3);
    assertSame(receipt, s.receipt("job"));
  }

  @Test
  void userStopStillInterruptsDetachedWork() {
    var s = new HarnessSession();
    s.connect("A", 0);
    var receipt = s.begin("job", "farm:1", 2, "farm");
    s.revokeLease();
    s.interrupt("user stop");
    assertEquals("interrupted", receipt.get("state").getAsString());
  }

  @Test
  void sharpTurnDoesNotMoveForwardAwayFromThePath() {
    var s = MovementSteering.toward(0, 180);
    assertFalse(s.forward());
    assertTrue(Math.abs(s.yaw()) <= 55);
  }

  @Test
  void steeringWrapsAcrossNorthAndConverges() {
    var s = MovementSteering.toward(179, -179);
    assertTrue(s.forward());
    assertEquals(181, s.yaw(), .001);
    float yaw = 0;
    for (int i = 0; i < 4; i++) yaw = MovementSteering.toward(yaw, 170).yaw();
    assertEquals(170, yaw, .001);
  }

  @Test
  void recentAttackerOutranksNearbyPassiveCandidate() {
    assertTrue(TargetScore.score(5, true, true, 20) < TargetScore.score(2, false, true, 20));
    assertTrue(TargetScore.score(2, false, true, 20) < TargetScore.score(2, false, false, 1));
  }

  @Test
  void farmCycleFlagsAreBooleanInRealModelSchema() {
    JsonObject schema = null;
    for (var e : ToolSchemas.all()) {
      var f = e.getAsJsonObject().getAsJsonObject("function");
      if (f.get("name").getAsString().equals("farm_cycle"))
        schema = f.getAsJsonObject("parameters");
    }
    assertNotNull(schema);
    for (String k : new String[] {"plant", "fertilize", "harvest", "collect"})
      assertEquals(
          "boolean",
          schema.getAsJsonObject("properties").getAsJsonObject(k).get("type").getAsString());
  }
}
