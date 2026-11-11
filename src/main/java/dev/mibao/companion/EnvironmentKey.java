package dev.mibao.companion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Shared skills remain portable; UI experience must match loaded mod versions, never a world ID.
 */
public final class EnvironmentKey {
  private static String cached;

  public static String of(Collection<String> modules) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      String.join("\n", modules.stream().sorted().toList())
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String current() {
    if (cached == null)
      try {
        cached =
            of(
                net.minecraftforge.fml.ModList.get().getMods().stream()
                    .map(m -> m.getModId() + "@" + m.getVersion())
                    .toList());
      } catch (RuntimeException | LinkageError ignored) {
        return "unknown";
      }
    return cached;
  }
}
