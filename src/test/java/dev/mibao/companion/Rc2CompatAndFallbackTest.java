package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

final class Rc2CompatAndFallbackTest {
  @Test
  void indoorFallbackCandidateCoversTwoBlockSameFloorOnly() {
    // Primary candidate excludes (dx=2, dz=0) and (dx=2, dz=2), whereas indoor fallback allows them on the same floor.
    assertFalse(ArrivalGate.candidate(2.0, 21.0, 0.0, 0.0, 21.0, 0.0, false));
    assertTrue(ArrivalGate.fallbackCandidate(2.0, 21.0, 0.0, 0.0, 21.0, 0.0));
    assertTrue(ArrivalGate.fallbackCandidate(-2.0, 21.5, 2.0, 0.0, 21.0, 0.0));

    // Strictly reject upper/lower floors (>0.65 blocks vertical diff) or horizontal offset > 2.05.
    assertFalse(ArrivalGate.fallbackCandidate(1.0, 22.0, 0.0, 0.0, 21.0, 0.0));
    assertFalse(ArrivalGate.fallbackCandidate(1.0, 20.0, 0.0, 0.0, 21.0, 0.0));
    assertFalse(ArrivalGate.fallbackCandidate(2.5, 21.0, 0.0, 0.0, 21.0, 0.0));
  }

  @Test
  void aeDepositSelectsMatchingSlotByItemAndOptionalCount() {
    var slots =
        List.of(
            new AeTransferProof.DepositSlot(3, 8),
            new AeTransferProof.DepositSlot(12, 2),
            new AeTransferProof.DepositSlot(20, 16));

    // Match explicit count=2 -> picks slot 12 automatically!
    var matchedCount = AeTransferProof.selectDepositSlot(slots, -1, 2);
    assertEquals("", matchedCount.error());
    assertEquals(12, matchedCount.slot());
    assertEquals(2, matchedCount.count());

    // Omitted count -> prefers main inventory (9..35) before hotbar (0..8) in slot order.
    var omittedCount = AeTransferProof.selectDepositSlot(slots, -1, -1);
    assertEquals("", omittedCount.error());
    assertEquals(12, omittedCount.slot());
    assertEquals(2, omittedCount.count());

    // Mismatched count gives an actionable slot summary without claiming blind success.
    var mismatch = AeTransferProof.selectDepositSlot(slots, -1, 4);
    assertTrue(mismatch.error().contains("槽位12×2"));

    // Explicit inventory_slot still works and rejects wrong count/empty slot.
    var explicitOk = AeTransferProof.selectDepositSlot(slots, 3, 8);
    assertEquals("", explicitOk.error());
    assertEquals(3, explicitOk.slot());
    var explicitMismatch = AeTransferProof.selectDepositSlot(slots, 3, 2);
    assertTrue(explicitMismatch.error().contains("实际数量=8"));
  }

  @Test
  void standbyAutoEatHonorsOptInAndThresholdToTargetHysteresis() {
    var duty = new CompanionDuty();
    duty.normalize();

    // Default standbyAutoEat is false (no unrequested consumption when idle).
    assertFalse(duty.shouldTriggerAutoEat(CompanionDuty.Mode.STANDBY, false, false, 6));

    // When enabled with threshold=10, target=20:
    duty.standbyAutoEat = true;
    duty.autoEatThreshold = 10;
    duty.autoEatTarget = 20;
    duty.normalize();

    assertFalse(duty.shouldTriggerAutoEat(CompanionDuty.Mode.STANDBY, false, false, 11));
    assertTrue(duty.shouldTriggerAutoEat(CompanionDuty.Mode.STANDBY, false, false, 10));

    // Once refilling started, continues until foodLevel reaches 20, then stops cleanly.
    assertTrue(duty.shouldTriggerAutoEat(CompanionDuty.Mode.STANDBY, false, true, 16));
    assertFalse(duty.shouldTriggerAutoEat(CompanionDuty.Mode.STANDBY, false, true, 20));

    // Target is clamped strictly above threshold (<= 20).
    duty.autoEatThreshold = 25;
    duty.autoEatTarget = 12;
    duty.normalize();
    assertEquals(19, duty.autoEatThreshold);
    assertEquals(20, duty.autoEatTarget);
  }
}
