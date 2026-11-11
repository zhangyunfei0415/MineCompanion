package dev.mibao.companion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public final class WorldScope {
  private WorldScope() {}

  public static String key(String identity) {
    return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
  }

  public static String singleplayer(Path save) {
    return key(
        "singleplayer-path:"
            + save.toAbsolutePath()
                .normalize()
                .toString()
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT));
  }
}
