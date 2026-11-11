package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RestockPlanTest {
  @Test
  void unconfirmedTransferAndCancellationMarkQuantitiesUncertain() {
    var plan = new RestockPlan(32);
    plan.destination(0);
    plan.source(32);
    plan.fetched(32);
    plan.uncertain("重开核对不符");
    assertTrue(plan.uncertain());
    assertEquals(RestockPlan.Step.BLOCKED, plan.step());
    assertEquals(0, plan.delivered());
    var cancelled = new RestockPlan(32);
    cancelled.cancel();
    assertTrue(cancelled.uncertain());
  }

  @Test
  void fillsOnlyTheObservedDeficitAndChecksEachTrip() {
    var plan = new RestockPlan(128);
    plan.destination(37);
    assertEquals(91, plan.missing());
    plan.source(200);
    assertEquals(64, plan.requested());
    plan.fetched(64);
    plan.deposited(64);
    assertEquals(RestockPlan.Step.RECHECK, plan.step());
    assertEquals(37, plan.current());
    plan.destination(101);
    assertEquals(RestockPlan.Step.SOURCE, plan.step());
    plan.source(200);
    assertEquals(27, plan.requested());
    plan.fetched(27);
    plan.deposited(27);
    plan.destination(128);
    assertEquals(RestockPlan.Step.COMPLETE, plan.step());
    assertEquals(91, plan.delivered());
    assertEquals(2, plan.trips());
    assertEquals(0, plan.carrying());
  }

  @Test
  void alreadySatisfiedTargetDoesNotFetchOrDeposit() {
    var plan = new RestockPlan(32);
    plan.destination(64);
    assertEquals(RestockPlan.Step.COMPLETE, plan.step());
    assertEquals(0, plan.delivered());
    assertThrows(IllegalStateException.class, () -> plan.source(32));
  }

  @Test
  void smallBackpackCanCompleteInVerifiedBatchesWithoutTakingExtraPersonalStock() {
    var plan = new RestockPlan(32);
    plan.destination(0);
    plan.source(64);
    plan.fetched(16);
    plan.deposited(16);
    plan.destination(16);
    plan.source(48);
    assertEquals(16, plan.requested());
    plan.fetched(16);
    plan.deposited(16);
    plan.destination(32);
    assertEquals(RestockPlan.Step.COMPLETE, plan.step());
    assertEquals(32, plan.delivered());
  }

  @Test
  void partialDepositReportsRemainderInsteadOfAssumingFullDelivery() {
    var plan = new RestockPlan(64);
    plan.destination(0);
    plan.source(64);
    plan.fetched(64);
    plan.deposited(10);
    plan.destination(10);
    assertEquals(RestockPlan.Step.BLOCKED, plan.step());
    assertEquals(10, plan.delivered());
    assertEquals(54, plan.carrying());
    assertTrue(plan.reason().contains("54"));
  }

  @Test
  void concurrentDestinationChangesCannotCountAsOurDelivery() {
    var plan = new RestockPlan(64);
    plan.destination(0);
    plan.source(32);
    plan.fetched(32);
    plan.deposited(32);
    plan.destination(64);
    assertEquals(RestockPlan.Step.BLOCKED, plan.step());
    assertEquals(32, plan.delivered());
  }

  @Test
  void missingStockAndCancellationNeverReportCompletion() {
    var plan = new RestockPlan(32);
    plan.destination(0);
    plan.source(0);
    assertEquals(RestockPlan.Step.SOURCE, plan.step());
    plan.block("仓库缺货");
    assertEquals(RestockPlan.Step.BLOCKED, plan.step());
    assertEquals(0, plan.delivered());
    var cancelled = new RestockPlan(32);
    cancelled.destination(0);
    cancelled.source(32);
    cancelled.fetched(32);
    cancelled.cancel();
    assertEquals(RestockPlan.Step.CANCELLED, cancelled.step());
    assertEquals(32, cancelled.carrying());
    assertThrows(IllegalStateException.class, () -> cancelled.deposited(32));
  }

  @Test
  void invalidCountsAndWrongCallbackOrderAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new RestockPlan(0));
    var plan = new RestockPlan(32);
    assertThrows(IllegalStateException.class, () -> plan.fetched(1));
    plan.destination(0);
    plan.source(32);
    plan.fetched(33);
    assertEquals(RestockPlan.Step.BLOCKED, plan.step());
  }
}
