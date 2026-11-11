package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FarmBatchTest {
  @Test
  void oldMatureNeighbourCannotReplaceNewPlant() {
    var b = new FarmBatch<String>(1, true, true, true);
    b.record(FarmBatch.Stage.PLANT, "new");
    assertFalse(b.accepts(FarmBatch.Stage.GROW, "old-mature"));
    assertFalse(b.accepts(FarmBatch.Stage.HARVEST, "old-mature"));
    assertFalse(b.complete());
  }

  @Test
  void harvestRequiresMaturityOfTheSamePlant() {
    var b = new FarmBatch<String>(1, true, true, true);
    b.record(FarmBatch.Stage.PLANT, "new");
    assertFalse(b.accepts(FarmBatch.Stage.HARVEST, "new"));
    b.record(FarmBatch.Stage.GROW, "new");
    assertTrue(b.accepts(FarmBatch.Stage.HARVEST, "new"));
    b.record(FarmBatch.Stage.HARVEST, "new");
    assertTrue(b.complete());
  }

  @Test
  void existingCropCycleBindsHarvestToFertilizedPositions() {
    var b = new FarmBatch<String>(1, false, true, true);
    b.record(FarmBatch.Stage.GROW, "young");
    assertFalse(b.accepts(FarmBatch.Stage.HARVEST, "old-mature"));
    b.record(FarmBatch.Stage.HARVEST, "young");
    assertTrue(b.complete());
  }

  @Test
  void partialAndDuplicateReceiptsCannotFinishBatch() {
    var b = new FarmBatch<String>(2, true, false, true);
    b.record(FarmBatch.Stage.PLANT, "a");
    b.record(FarmBatch.Stage.PLANT, "a");
    assertEquals(1, b.count(FarmBatch.Stage.PLANT));
    assertFalse(b.complete());
    assertThrows(IllegalStateException.class, () -> b.record(FarmBatch.Stage.HARVEST, "b"));
  }

  @Test
  void standalonePlantingCanFinishWithoutHarvest() {
    var b = new FarmBatch<String>(1, true, false, false);
    b.record(FarmBatch.Stage.PLANT, "a");
    assertTrue(b.complete());
  }

  @Test
  void completedBatchRejectsExtraTargets() {
    var b = new FarmBatch<String>(1, true, false, false);
    b.record(FarmBatch.Stage.PLANT, "a");
    assertThrows(IllegalStateException.class, () -> b.record(FarmBatch.Stage.PLANT, "b"));
  }

  @Test
  void emptyOrUnboundedBatchFails() {
    assertThrows(IllegalArgumentException.class, () -> new FarmBatch<>(0, true, true, true));
    assertThrows(IllegalArgumentException.class, () -> new FarmBatch<>(65, true, true, true));
  }

  @Test
  void boundTargetsSurviveMovementBetweenStages() {
    var b = new FarmBatch<String>(1, true, true, true);
    b.record(FarmBatch.Stage.PLANT, "116,63,125");
    assertTrue(b.bound(FarmBatch.Stage.GROW));
    assertEquals(java.util.Set.of("116,63,125"), b.targets(FarmBatch.Stage.GROW));
    b.record(FarmBatch.Stage.GROW, "116,63,125");
    assertEquals(b.targets(FarmBatch.Stage.GROW), b.targets(FarmBatch.Stage.HARVEST));
  }
}
