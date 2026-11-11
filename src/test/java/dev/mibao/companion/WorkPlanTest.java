package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorkPlanTest {
  private WorkPlan.Step block(int count) {
    return new WorkPlan.Step(WorkPlan.ScanType.BLOCK, "#forge:ores/iron", count, 12);
  }

  @Test
  void noUnboundedCountOrRadius() {
    assertThrows(IllegalArgumentException.class, () -> block(0));
    assertThrows(IllegalArgumentException.class, () -> block(257));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkPlan.Step(WorkPlan.ScanType.BLOCK, "ore", 1, 25));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkPlan.Step(WorkPlan.ScanType.BLOCK, "ore", 1, 0));
  }

  @Test
  void combatHasTighterBudget() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkPlan.Step(WorkPlan.ScanType.ENTITY, "minecraft:zombie", 9, 8));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkPlan.Step(WorkPlan.ScanType.ENTITY, "minecraft:zombie", 1, 17));
  }

  @Test
  void constructingOrReadingDoesNotCompleteActions() {
    var p = new WorkPlan(block(2));
    assertEquals(0, p.progress());
    assertEquals(0, p.completed());
    assertNotNull(p.current());
    assertFalse(p.isDone());
  }

  @Test
  void duplicateOrMissingReceiptsCannotInflateCount() {
    var p = new WorkPlan(block(2));
    assertFalse(p.verify(null));
    assertFalse(p.verify(" "));
    assertTrue(p.verify("block:1,2,3"));
    assertFalse(p.verify("block:1,2,3"));
    assertEquals(1, p.completed());
    assertFalse(p.isDone());
    assertTrue(p.verify("block:2,2,3"));
    assertTrue(p.isDone());
    assertFalse(p.verify("extra"));
    assertEquals(2, p.completed());
  }

  @Test
  void advancesOnlyWhenCurrentStepCountIsMet() {
    var p = new WorkPlan(block(2), block(1));
    p.verify("a");
    assertEquals(0, p.progress());
    p.verify("b");
    assertEquals(1, p.progress());
    assertEquals(0, p.completed());
    p.verify("c");
    assertTrue(p.isDone());
    assertNull(p.current());
    assertEquals(2, p.progress());
  }

  @Test
  void plansCannotBeMutatedByCaller() {
    var steps = new WorkPlan.Step[] {block(1)};
    var p = new WorkPlan(steps);
    steps[0] = block(4);
    assertEquals(1, p.current().count());
    assertThrows(UnsupportedOperationException.class, () -> p.steps.clear());
  }

  @Test
  void emptyOrExcessivePlansRejected() {
    assertThrows(IllegalArgumentException.class, () -> new WorkPlan());
    assertThrows(IllegalArgumentException.class, () -> new WorkPlan(new WorkPlan.Step[33]));
  }
}
