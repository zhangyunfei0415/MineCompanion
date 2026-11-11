package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LogisticsPolicyTest {
  private static final String IRON = "minecraft:iron_ingot", DIM = "minecraft:overworld";

  private JsonObject plan(String kind, int count) {
    var j = new JsonObject();
    j.addProperty("kind", kind);
    j.addProperty("item", IRON);
    j.addProperty("count", count);
    j.addProperty("source", "材料箱");
    j.addProperty("destination", "交付箱");
    return j;
  }

  private LogisticsRecord record(String state, int delivered) {
    var r = new LogisticsRecord();
    r.plan = plan("supply", 32);
    r.title = "搬铁";
    r.target = 32;
    r.state = state;
    r.delivered = delivered;
    return r;
  }

  private CommunityBoard.Task waiting(String id, int need) {
    var t = new CommunityBoard.Task();
    t.id = id;
    t.dimension = DIM;
    t.state = "WAITING_MATERIALS";
    t.requires.put(IRON, need);
    return t;
  }

  @Test
  void namedOrdersRoundTripAndUseOnlyExplicitMemberFallback() {
    var j = plan("supply", 32);
    j.remove("destination");
    var p = LogisticsPlan.from(j, "Alice交付箱");
    assertEquals("Alice交付箱", p.destination());
    assertEquals(p, LogisticsPlan.from(p.json(), "不同成员"));
    assertThrows(IllegalArgumentException.class, () -> LogisticsPlan.from(j, ""));
  }

  @Test
  void stockWithoutLowDefaultsToRefillTargetInsteadOfNeverWorking() {
    var p = LogisticsPlan.from(plan("stock", 64), "");
    assertEquals(64, p.low());
    assertTrue(p.needsRefill(0));
    assertFalse(p.needsRefill(64));
  }

  @Test
  void lowWaterMarkHasHysteresisAndLargeRefillsUseSmallBatches() {
    var j = plan("stock", 160);
    j.addProperty("low", 32);
    var p = LogisticsPlan.from(j, "");
    assertFalse(p.needsRefill(32));
    assertTrue(p.needsRefill(31));
    assertEquals(64, p.nextBatch(31));
    assertEquals(64, p.nextBatch(95));
    assertEquals(1, p.nextBatch(159));
    assertEquals(0, p.nextBatch(170));
    assertThrows(IllegalArgumentException.class, () -> p.needsRefill(-1));
  }

  @Test
  void invalidOrFractionalQuantitiesAndSameSourceAreRejected() {
    for (int n : new int[] {0, 65})
      assertThrows(IllegalArgumentException.class, () -> LogisticsPlan.from(plan("supply", n), ""));
    assertThrows(IllegalArgumentException.class, () -> LogisticsPlan.from(plan("stock", 257), ""));
    var fractional = plan("supply", 32);
    fractional.addProperty("count", 1.5);
    assertThrows(IllegalArgumentException.class, () -> LogisticsPlan.from(fractional, ""));
    var same = plan("supply", 32);
    same.addProperty("destination", "材料箱");
    assertThrows(IllegalArgumentException.class, () -> LogisticsPlan.from(same, ""));
  }

  @Test
  void exactNbtCanBeMovedButCannotBeClaimedAsGenericProduction() {
    var j = plan("supply", 1);
    String variant = IRON + "#sha256:" + "a".repeat(64);
    j.addProperty("item", variant);
    assertEquals(variant, LogisticsPlan.from(j, "").item());
    j.addProperty("kind", "craft");
    assertThrows(IllegalArgumentException.class, () -> LogisticsPlan.from(j, ""));
    j.addProperty("item", IRON);
    j.addProperty("kind", "smelt");
    assertThrows(IllegalArgumentException.class, () -> LogisticsPlan.from(j, ""));
    j.addProperty("input", "minecraft:raw_iron");
    assertEquals("minecraft:raw_iron", LogisticsPlan.from(j, "").input());
  }

  @Test
  void receiptsAreUniqueAndFuelDoesNotCountAsDeliveredProduct() {
    var r = record("RUNNING", 0);
    r.transfer("input", false, 32, "minecraft:raw_iron");
    r.transfer("fuel", false, 4, "minecraft:coal");
    assertEquals(0, r.fetched);
    r.transfer("product", true, 32, IRON);
    assertEquals(32, r.delivered);
    assertEquals(4, r.received.get("minecraft:coal"));
    assertEquals(32, r.sent.get(IRON));
    assertThrows(IllegalArgumentException.class, () -> r.transfer("product", true, 32, IRON));
    assertEquals(32, r.delivered);
    assertThrows(IllegalArgumentException.class, () -> r.transfer("bad", true, -1));
  }

  @Test
  void receiptIdsRemainUniqueEvenWhenAllOtherFieldsAreEqual() {
    var a = new TransferReceipt("box", IRON, true, 32, 32, 3);
    var b = new TransferReceipt("box", IRON, true, 32, 32, 3);
    assertNotEquals(a.id(), b.id());
  }

  @Test
  void plannerTextNeverPromotesMissingPartialOrBlockedEvidence() {
    var r = record("BLOCKED", 0);
    r.note = "规划者声称全部成功";
    var records = new LinkedHashMap<String, LogisticsRecord>();
    records.put(r.id, r);
    assertEquals("BLOCKED", LogisticsHistory.outcome(List.of(r.id), records));
    r.state = "PARTIAL";
    r.delivered = 3;
    assertEquals("PARTIAL", LogisticsHistory.outcome(List.of(r.id), records));
    r.state = "COMPLETE";
    r.delivered = 32;
    assertEquals("DONE", LogisticsHistory.outcome(List.of(r.id), records));
    r.uncertain = true;
    assertEquals("PARTIAL", LogisticsHistory.outcome(List.of(r.id), records));
    assertEquals("NEEDS_CHECK", LogisticsHistory.outcome(List.of("missing"), records));
    assertEquals("ENDED", LogisticsHistory.outcome(List.of(), records));
  }

  @Test
  void verifiedPartialProductionIsRetainedWithoutClaimingDelivery() {
    var r = record("PARTIAL", 0);
    r.produced("batch-proof", 8);
    assertEquals(8, r.produced);
    assertEquals(0, r.delivered);
    assertThrows(IllegalArgumentException.class, () -> r.produced("batch-proof", 8));
    assertEquals("PARTIAL", LogisticsHistory.outcome(List.of(r.id), Map.of(r.id, r)));
  }

  @Test
  void stockAboveLowCompletesWithoutClaimingToHaveDeliveredTheTarget() {
    var r = record("COMPLETE", 0);
    r.plan = plan("stock", 64);
    r.target = 64;
    r.destinationCount = 40;
    assertEquals("DONE", LogisticsHistory.outcome(List.of(r.id), Map.of(r.id, r)));
    assertTrue(r.summary().contains("本轮补入 0"));
    assertFalse(r.summary().contains("已交付 0 / 64"));
  }

  @Test
  void liveTaskReceiptsAndRunningRecordsSurviveHistoryPruning() {
    var records = new LinkedHashMap<String, LogisticsRecord>();
    var t = waiting("t", 3);
    var protectedRecord = record("PARTIAL", 1);
    records.put(protectedRecord.id, protectedRecord);
    t.orders.add(protectedRecord.id);
    for (int i = 0; i < 110; i++) {
      var r = record("COMPLETE", 32);
      records.put(r.id, r);
    }
    var running = record("RUNNING", 0);
    records.put(running.id, running);
    LogisticsHistory.trim(records, List.of(t), 96);
    assertEquals(96, records.size());
    assertSame(protectedRecord, records.get(protectedRecord.id));
    assertSame(running, records.get(running.id));
    t.state = "CANCELLED";
    LogisticsHistory.trim(records, List.of(t), 95);
    assertFalse(records.containsKey(protectedRecord.id));
  }

  @Test
  void fullQueueNeverDiscardsMaterialWaitToMakeRoomForNewHistory() {
    var q = new CommunityBoard();
    var waiting = q.add("A", "Alice", "等待铁", "d", "箱", 1);
    waiting.state = "WAITING_MATERIALS";
    for (int i = 1; i < 64; i++) {
      var old = q.add("x" + i, "Other", "已结束" + i, "d", "箱", i);
      old.state = "ENDED";
    }
    q.add("B", "Bob", "新目标", "d", "箱", 99);
    assertEquals(64, q.tasks.size());
    assertTrue(q.tasks.contains(waiting));
    assertEquals("WAITING_MATERIALS", waiting.state);
  }

  @Test
  void oneSupplyDoesNotWakeTwoJobsAndReadyReservationsPersistAcrossTicks() {
    var a = waiting("A", 4);
    var b = waiting("B", 4);
    var tasks = List.of(a, b);
    assertEquals(1, CommunityBoard.wakeAvailable(tasks, DIM, Map.of(IRON, 4)));
    assertEquals("WAITING", a.state);
    assertEquals("WAITING_MATERIALS", b.state);
    assertEquals(4, CommunityBoard.reserved(tasks, DIM, IRON));
    assertEquals(0, CommunityBoard.wakeAvailable(tasks, DIM, Map.of(IRON, 4)));
    assertEquals(1, CommunityBoard.wakeAvailable(tasks, DIM, Map.of(IRON, 8)));
    assertEquals(8, CommunityBoard.reserved(tasks, DIM, IRON));
    assertEquals(0, CommunityBoard.reserved(tasks, "other", IRON));
  }

  @Test
  void waitingForSupplyDoesNotReserveAbsentItemsOrWakeInAnotherDimension() {
    var a = waiting("A", 8);
    var b = waiting("B", 2);
    assertEquals(1, CommunityBoard.wakeAvailable(List.of(a, b), DIM, Map.of(IRON, 2)));
    assertEquals(2, CommunityBoard.reserved(List.of(a, b), DIM, IRON));
    assertEquals("WAITING_MATERIALS", a.state);
    assertEquals(0, CommunityBoard.wakeAvailable(List.of(a), "other", Map.of(IRON, 99)));
  }

  @Test
  void bagSeparatesWishesRequirementsAndFixedItems() {
    var b = new BagPolicy();
    b.desired.put(IRON, 8);
    b.validate();
    assertTrue(b.mandatory.isEmpty());
    assertEquals(8, b.reserve(IRON + "#sha256:" + "b".repeat(64)));
    b.mandatory.add("minecraft:bread");
    assertThrows(IllegalArgumentException.class, b::validate);
    b.mandatory.clear();
    b.protectedItems.add(IRON);
    assertEquals(0, BagPolicy.surplus(64, 8, true));
    assertEquals(56, BagPolicy.surplus(64, 8, false));
    assertEquals(3, BagPolicy.deficit(8, 5));
    assertEquals(0, BagPolicy.deficit(8, 10));
  }

  @Test
  void bagRejectsUnboundUnloadOrOutOfRangeTemplates() {
    var b = new BagPolicy();
    b.unload = true;
    assertThrows(IllegalArgumentException.class, b::validate);
    b.destination = "回收箱";
    b.freeSlots = 21;
    assertThrows(IllegalArgumentException.class, b::validate);
    b.freeSlots = 4;
    b.desired.put(IRON, 257);
    assertThrows(IllegalArgumentException.class, b::validate);
    b.desired.put(IRON, 8);
    b.validate();
  }

  @Test
  void maintenanceProtectsPreexistingStockWithoutDemandingAnUnfilledBagWish() {
    assertTrue(StockTarget.canDeliverCargo(64, 0, 64));
    assertTrue(StockTarget.canDeliverCargo(72, 8, 64));
    assertFalse(StockTarget.canDeliverCargo(64, 8, 64));
    assertFalse(StockTarget.canDeliverCargo(8, 8, 1));
    assertFalse(StockTarget.canDeliverCargo(8, 8, 0));
  }

  @Test
  void maintenanceRequiresLoadedSameDimensionAndExplicitEnable() {
    assertFalse(StockTarget.due(false, 100, 0, DIM, DIM, true));
    assertFalse(StockTarget.due(true, 100, 101, DIM, DIM, true));
    assertFalse(StockTarget.due(true, 100, 0, DIM, "other", true));
    assertFalse(StockTarget.due(true, 100, 0, DIM, DIM, false));
    assertTrue(StockTarget.due(true, 100, 0, DIM, DIM, true));
  }

  @Test
  void maintenanceBacksOffThenPausesAndSuccessResetsFailureCount() {
    var t = new StockTarget();
    t.plan = plan("stock", 64);
    t.enabled = true;
    t.intervalSeconds = 60;
    t.validate();
    t.settled(false, 1000);
    assertEquals(121000, t.nextCheck);
    assertTrue(t.enabled);
    t.settled(false, 2000);
    assertEquals(182000, t.nextCheck);
    t.settled(true, 3000);
    assertEquals(0, t.failures);
    assertEquals(63000, t.nextCheck);
    for (int i = 0; i < 3; i++) t.settled(false, 4000);
    assertFalse(t.enabled);
    assertEquals(3, t.failures);
  }

  @Test
  void newRoutinesRemainDiscoverableWithChildPermissions() {
    for (String name : List.of("run_order", "prepare_bag", "logistics_status"))
      assertEquals("logistics", CapabilityCatalog.family(name));
    assertTrue(CapabilityCatalog.mutates("run_order"));
    assertTrue(CapabilityCatalog.mutates("prepare_bag"));
    assertFalse(CapabilityCatalog.mutates("logistics_status"));
    assertFalse(Rules.WRITE.contains("run_order"));
  }
}
