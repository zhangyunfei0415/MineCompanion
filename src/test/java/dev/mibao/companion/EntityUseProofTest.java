package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EntityUseProofTest {
  @Test
  void exactlyOneCorrelatedSpawnAndConsumptionPass() {
    assertTrue(EntityUseProof.verified(1, 1, 100, 200, 1));
  }

  @Test
  void eggConsumptionAloneFails() {
    assertFalse(EntityUseProof.verified(1, 0, 100, 200, 1));
  }

  @Test
  void oldOrAmbiguousEntitiesFail() {
    assertFalse(EntityUseProof.verified(1, 1, 200, 100, 1));
    assertFalse(EntityUseProof.verified(1, 2, 100, 200, 1));
  }

  @Test
  void farOrLateSpawnFails() {
    assertFalse(EntityUseProof.verified(1, 1, 100, 200, 7));
    assertFalse(EntityUseProof.verified(1, 1, 100, 6_000_000_000L, 1));
  }

  @Test
  void entityWithoutEggConsumptionFails() {
    assertFalse(EntityUseProof.verified(0, 1, 100, 200, 1));
  }
}
