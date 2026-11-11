package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AeTransferProofTest {
  @Test
  void fetchedItemNeedsNetworkAndServerInventoryAgreement() {
    assertTrue(AeTransferProof.confirmed(true, false, 1, 4, 5, 5, 48, 47));
    assertFalse(AeTransferProof.confirmed(false, false, 1, 4, 5, 5, 48, 47));
    assertFalse(AeTransferProof.confirmed(true, false, 1, 4, 5, 4, 48, 47));
    assertFalse(AeTransferProof.confirmed(true, false, 1, 4, 5, 5, 48, 48));
  }

  @Test
  void depositChecksWholeQuantityAndRejectsUnrelatedChanges() {
    assertTrue(AeTransferProof.confirmed(true, true, 32, 32, 0, 0, 48, 80));
    assertFalse(AeTransferProof.confirmed(true, true, 32, 32, 0, 0, 48, 79));
    assertFalse(AeTransferProof.confirmed(true, true, 32, 32, 1, 1, 48, 80));
  }

  @Test
  void largeNetworksAreNotTruncatedToProxyOrIntegerAmounts() {
    assertTrue(AeTransferProof.confirmed(true, false, 1, 0, 1, 1, 3_000_000_000L, 2_999_999_999L));
    assertFalse(AeTransferProof.confirmed(true, false, 1, 0, 1, 1, 3_000_000_000L, 1));
  }
}
