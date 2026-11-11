package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MiningCombatProofTest {
  @Test
  void predictedAirOrActionAloneIsNotMiningSuccess() {
    assertFalse(MiningProof.broken(true, false, true));
    assertFalse(MiningProof.broken(true, true, false));
    assertFalse(MiningProof.broken(false, true, true));
    assertFalse(MiningProof.broken(true, false, false));
    assertTrue(MiningProof.broken(true, true, true));
  }

  @Test
  void sendingAttackOrLosingEntityCannotConfirmDeath() {
    assertFalse(CombatPolicy.deathConfirmed(100, 0));
    assertFalse(CombatPolicy.deathConfirmed(0, 200));
    assertFalse(CombatPolicy.deathConfirmed(100, 100));
    assertFalse(CombatPolicy.deathConfirmed(100, 99));
    assertTrue(CombatPolicy.deathConfirmed(100, 101));
  }

  @Test
  void staleAndFutureDamageDoNotAuthorizeSelfDefense() {
    long now = 20_000_000_000L;
    assertTrue(CombatPolicy.recent(now, now - 1));
    assertTrue(CombatPolicy.recent(now, now - 10_000_000_000L));
    assertFalse(CombatPolicy.recent(now, now - 10_000_000_001L));
    assertFalse(CombatPolicy.recent(now, now + 1));
    assertFalse(CombatPolicy.recent(now, 0));
  }

  @Test
  void huntCannotExpandToPvpPetsBossesOrExplosiveMobs() {
    for (String id :
        new String[] {
          "minecraft:player",
          "minecraft:wolf",
          "minecraft:iron_golem",
          "minecraft:ender_dragon",
          "minecraft:wither",
          "minecraft:creeper",
          "minecraft:enderman",
          "*"
        }) assertFalse(CombatPolicy.HUNTABLE.contains(id));
    assertEquals(7, CombatPolicy.HUNTABLE.size());
    assertTrue(CombatPolicy.HUNTABLE.contains("minecraft:zombie"));
  }
}
