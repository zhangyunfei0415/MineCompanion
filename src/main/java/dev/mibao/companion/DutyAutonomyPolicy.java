package dev.mibao.companion;

import java.util.Locale;

/**
 * Pure autonomous duty mode transition policy with cooldown debounce and human override grace. Zero
 * {@code net.minecraft.*} imports for offline JUnit testing.
 */
public final class DutyAutonomyPolicy {
  public enum Level {
    AUTO_FULL("AI全权自主调档"),
    AUTO_SAFE("仅安全自主调档"),
    MANUAL_LOCK("人工锁定模式");

    public final String label;

    Level(String label) {
      this.label = label;
    }

    public static Level parse(String raw) {
      if (raw == null || raw.isBlank()) return AUTO_FULL;
      return switch (raw.strip().toUpperCase(Locale.ROOT)) {
        case "AUTO_SAFE", "SAFE", "仅安全" -> AUTO_SAFE;
        case "MANUAL_LOCK", "MANUAL", "LOCK", "LOCKED", "人工锁定" -> MANUAL_LOCK;
        default -> AUTO_FULL;
      };
    }
  }

  public record Transition(CompanionDuty.Mode targetMode, boolean armed, String reason) {}

  private static final long AUTO_SWITCH_COOLDOWN_MS = 20_000L;
  private static final long OWNER_OFFLINE_RETURN_MS = 15_000L;

  private DutyAutonomyPolicy() {}

  public static Transition recommendTransition(
      Level level,
      CompanionDuty.Mode current,
      boolean armed,
      boolean hasHome,
      boolean ownerOnline,
      double ownerDistance,
      boolean ownerMovingAway,
      int baseRadius,
      int freeSlots,
      int minFreeSlots,
      boolean expeditionReturned,
      long nowMs,
      long lastAutoSwitchAt,
      long manualOverrideUntil,
      long ownerOfflineDurationMs) {
    if (level == null || level == Level.MANUAL_LOCK) return null;
    if (nowMs < manualOverrideUntil) return null;
    if (lastAutoSwitchAt > 0 && nowMs - lastAutoSwitchAt < AUTO_SWITCH_COOLDOWN_MS) return null;

    if (expeditionReturned && hasHome && current == CompanionDuty.Mode.EXPEDITION_GATHER) {
      return new Transition(
          CompanionDuty.Mode.BASE_STEWARD, true, "外出回城自动切回驻守基地归箱休整");
    }
    if (armed
        && current == CompanionDuty.Mode.ESCORT_GUARD
        && !ownerOnline
        && ownerOfflineDurationMs >= OWNER_OFFLINE_RETURN_MS
        && hasHome) {
      return new Transition(
          CompanionDuty.Mode.BASE_STEWARD, true, "主人离线超过15秒，自动切回驻守基地看家");
    }
    if (armed
        && current == CompanionDuty.Mode.BASE_STEWARD
        && level == Level.AUTO_FULL
        && ownerOnline
        && ownerDistance >= 0
        && ownerDistance <= Math.max(12, baseRadius)
        && ownerMovingAway
        && freeSlots >= Math.max(1, minFreeSlots)) {
      return new Transition(
          CompanionDuty.Mode.ESCORT_GUARD, true, "主人正在离开基地出发，自动切为跟随与守护");
    }
    return null;
  }
}
