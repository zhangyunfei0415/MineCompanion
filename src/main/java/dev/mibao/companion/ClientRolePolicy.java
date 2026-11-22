package dev.mibao.companion;

/**
 * Pure dual-client identity & public chat arbitration policy. Zero {@code net.minecraft.*}
 * dependencies for offline JUnit testing.
 */
public final class ClientRolePolicy {
  private ClientRolePolicy() {}

  /**
   * Returns true only when this window holds AI control or is NOT a passive human-owner companion
   * window. Prevents dual-client same-directory accounts from echoing `@name` replies as the owner.
   */
  public static boolean shouldProcessPublicChat(
      String localPlayerName,
      String configuredOwner,
      boolean agentEnabled,
      boolean hasControlLock) {
    if (!agentEnabled && !hasControlLock) return false;
    if (!agentEnabled
        && localPlayerName != null
        && configuredOwner != null
        && !configuredOwner.isBlank()
        && localPlayerName.trim().equalsIgnoreCase(configuredOwner.trim())) {
      return false;
    }
    return true;
  }

  public static boolean isOwnerCompanionWindow(
      boolean agentEnabled, boolean hasControlLock, boolean hasRemotePeer) {
    return !agentEnabled && !hasControlLock && hasRemotePeer;
  }

  /**
   * Only the window holding active AI or control lock may claim the persistent exploration shard
   * write lock; passive human-owner windows load read-only so they never steal the writer lease.
   */
  public static boolean shouldClaimExplorationWriteLock(
      boolean agentEnabled, boolean hasControlLock) {
    return agentEnabled || hasControlLock;
  }
}
