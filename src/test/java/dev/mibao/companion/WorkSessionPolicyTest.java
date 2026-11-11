package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class WorkSessionPolicyTest {
  private SessionPlan parse(String text) {
    return new SessionPlan(JsonParser.parseString(text).getAsJsonObject());
  }

  @Test
  void waitsForIncomingSuppliesWithoutExtendingBudget() {
    var wait = new SupplyWait(30);
    assertEquals(SupplyWait.State.WAIT, wait.check(1000, false));
    assertEquals(SupplyWait.State.WAIT, wait.check(30000, false));
    assertEquals(SupplyWait.State.EXPIRED, wait.check(31000, false));
    assertEquals(0, wait.remaining(40000));
  }

  @Test
  void suppliesCanResolveWaitAndZeroBudgetNeverLoops() {
    var wait = new SupplyWait(30);
    wait.check(0, false);
    assertEquals(SupplyWait.State.READY, wait.check(5000, true));
    assertEquals(SupplyWait.State.EXPIRED, new SupplyWait(0).check(10, false));
  }

  @Test
  void duplicateAndLateReceiptsCannotAdvanceTheNextStep() {
    var p = new SessionProgress(2);
    p.begin("one");
    assertTrue(p.finish("one", true));
    assertFalse(p.finish("one", true));
    p.begin("two");
    assertFalse(p.finish("one", true));
    assertEquals(1, p.completed());
    assertTrue(p.finish("two", true));
    assertTrue(p.done());
  }

  @Test
  void failedChildDoesNotAdvanceAndCannotRestartItself() {
    var p = new SessionProgress(2);
    p.begin("one");
    assertTrue(p.finish("one", false));
    assertEquals(0, p.completed());
    assertThrows(IllegalStateException.class, () -> p.begin("again"));
    assertFalse(p.finish("one", true));
  }

  @Test
  void planPreservesExplicitResourceRequirementsAndCopiesArguments() {
    var p =
        parse(
            "{steps:[{tool:'craft_item',args:{item:'minecraft:stick',count:4},requires:{'minecraft:birch_log':1}}]}");
    assertEquals(1, p.steps.get(0).requires().get("minecraft:birch_log"));
    var args = p.steps.get(0).args();
    args.addProperty("count", 64);
    assertEquals(4, p.steps.get(0).args().get("count").getAsInt());
    assertEquals(30, p.steps.get(0).waitSeconds());
  }

  @Test
  void farmSessionNeedsSeedsBeforeStartingAnyHarvest() {
    var p = parse("{steps:[{tool:'harvest_replant',args:{crop:'minecraft:carrots',count:3}}]}");
    assertEquals(1, p.steps.get(0).requires().get("minecraft:carrot"));
  }

  @Test
  void nestedInputsAndCrossWorldReusableWrappersAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> parse("{steps:[{tool:'work_session'}]}"));
    assertThrows(IllegalArgumentException.class, () -> parse("{steps:[{tool:'press_key'}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SkillSteps.prepare(
                JsonParser.parseString("[{tool:'work_session',args:{}}]").getAsJsonArray(),
                true,
                java.util.Set.of(),
                new JsonObject()));
  }

  @Test
  void boundsAndNonIntegralSupplyAmountsCannotBecomeUnboundedJobs() {
    for (String text :
        new String[] {
          "{steps:[]}",
          "{seconds:1801,steps:[{tool:'eat_food'}]}",
          "{steps:[{tool:'eat_food',wait_seconds:121}]}",
          "{steps:[{tool:'eat_food',requires:{'minecraft:bread':1.5}}]}"
        }) assertThrows(IllegalArgumentException.class, () -> parse(text));
    assertThrows(IllegalArgumentException.class, () -> new SupplyWait(-1));
  }

  @Test
  void toolSchemaAdvertisesExactlyTheSupportedChildrenAndLimits() {
    var schema = SessionPlan.stepSchema();
    assertEquals(16, schema.get("maxItems").getAsInt());
    var properties = schema.getAsJsonObject("items").getAsJsonObject("properties");
    var choices = properties.getAsJsonObject("tool").getAsJsonArray("enum");
    assertEquals(SessionPlan.TOOLS.size(), choices.size());
    assertFalse(choices.asList().stream().anyMatch(x -> x.getAsString().equals("press_key")));
    assertEquals(120, properties.getAsJsonObject("wait_seconds").get("maximum").getAsInt());
  }

  @Test
  void sessionPlanHasImmutableStepAndRequirementCollections() {
    var p =
        parse(
            "{steps:[{tool:'craft_item',args:{item:'minecraft:stick'},requires:{'minecraft:oak_log':1}}]}");
    assertThrows(UnsupportedOperationException.class, () -> p.steps.clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> p.steps.get(0).requires().put("minecraft:diamond", 1));
  }
}
