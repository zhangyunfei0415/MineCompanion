package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorldScopeTest {
  @Test
  void sameDisplayNameInDifferentSaveFoldersIsIsolated() {
    assertNotEquals(
        WorldScope.singleplayer(Path.of("saves/world-A")),
        WorldScope.singleplayer(Path.of("saves/world-B")));
    assertNotEquals(
        WorldScope.singleplayer(Path.of("instance-A/saves/world")),
        WorldScope.singleplayer(Path.of("instance-B/saves/world")));
  }

  @Test
  void normalizedWindowsSavePathKeepsSameIdentity() {
    assertEquals(
        WorldScope.singleplayer(Path.of("saves/world-A")),
        WorldScope.singleplayer(Path.of("saves/temporary/../WORLD-A")));
  }

  @Test
  void remoteServerScopeKeepsLegacyIdentityAndDifferentPortsStaySeparate() {
    assertEquals(
        java.util
            .UUID
            .nameUUIDFromBytes("localhost:25565".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString(),
        WorldScope.key("localhost:25565"));
    assertNotEquals(WorldScope.key("localhost:25565"), WorldScope.key("localhost:25566"));
    assertNotEquals(
        WorldScope.key("localhost:25565"), WorldScope.singleplayer(Path.of("saves/world")));
  }
}
