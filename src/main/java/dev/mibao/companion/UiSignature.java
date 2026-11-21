package dev.mibao.companion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Cross-modpack portable container UI signatures. Excludes EnvironmentKey, world coordinates,
 * item stacks, and window dimensions so signatures transfer cleanly across different modpacks.
 */
public final class UiSignature {
  public record SlotSpec(String slotClass, int x, int y, boolean playerInventory) {
    public SlotSpec {
      slotClass = slotClass == null ? "" : slotClass.trim();
    }
  }

  private UiSignature() {}

  public static String portableSignature(String menuClass, String blockId, List<SlotSpec> slots) {
    String normalizedBlock = (blockId == null || blockId.isBlank()) ? "none" : blockId.trim();
    return compute(menuClass, normalizedBlock, slots);
  }

  public static String layoutSignature(String menuClass, List<SlotSpec> slots) {
    return compute(menuClass, "*", slots);
  }

  private static String compute(String menuClass, String blockField, List<SlotSpec> slots) {
    StringBuilder sb = new StringBuilder(256);
    sb.append("ui-sig-v1\n");
    sb.append("menu=").append(menuClass == null ? "" : menuClass.trim()).append('\n');
    sb.append("block=").append(blockField).append('\n');
    if (slots != null) {
      for (SlotSpec slot : slots) {
        if (slot == null) continue;
        sb.append("slot=")
            .append(slot.slotClass())
            .append('|')
            .append(slot.x())
            .append(',')
            .append(slot.y())
            .append('|')
            .append(slot.playerInventory())
            .append('\n');
      }
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
