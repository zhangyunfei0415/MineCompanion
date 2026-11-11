package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LimitedPermissionsTest {
  @Test
  void miningAliasesCannotBypassDisabledMining() {
    for (String tool : new String[] {"mine_blocks", "mine_resource"}) {
      assertFalse(Rules.limitedDenial(tool, false, true, true).isEmpty());
      assertEquals("", Rules.limitedDenial(tool, true, false, false));
    }
  }

  @Test
  void farmingNeedsBothHarvestAndPlacementPermission() {
    assertFalse(Rules.limitedDenial("harvest_replant", false, true, true).isEmpty());
    assertFalse(Rules.limitedDenial("harvest_replant", true, false, true).isEmpty());
    assertEquals("", Rules.limitedDenial("harvest_replant", true, true, false));
  }

  @Test
  void miningOrPlacementDoesNotAuthorizeHunting() {
    assertFalse(Rules.limitedDenial("hunt_mobs", true, true, false).isEmpty());
    assertEquals("", Rules.limitedDenial("hunt_mobs", false, false, true));
  }

  @Test
  void ordinaryCollectionAndStorageStayAvailable() {
    for (String tool : new String[] {"collect_items", "fetch", "deposit", "open_container"})
      assertEquals("", Rules.limitedDenial(tool, false, false, false));
  }
}
