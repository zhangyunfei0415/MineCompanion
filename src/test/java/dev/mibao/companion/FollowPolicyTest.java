package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FollowPolicyTest {
  @Test void standingDistanceDoesNotRepeatedlyToggleChase() {
    assertFalse(FollowPolicy.chase(6, false));
    assertTrue(FollowPolicy.chase(7, false));
    assertTrue(FollowPolicy.chase(6, true));
    assertFalse(FollowPolicy.chase(4, true));
  }

  @Test void profilesOnlyAuthorizeTheirOwnNormalPortals() {
    assertTrue(FollowPolicy.allowPortal("nether", "nether", false));
    assertFalse(FollowPolicy.allowPortal("follow", "nether", false));
    assertFalse(FollowPolicy.allowPortal("end", "end", false));
    assertTrue(FollowPolicy.allowPortal("end", "end", true));
    assertFalse(FollowPolicy.allowPortal("end", "gateway", true));
  }
}
