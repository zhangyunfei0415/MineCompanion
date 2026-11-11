package dev.mibao.companion;

/**
 * Correlated server spawn + one consumed egg; consumption or an old entity alone is insufficient.
 */
public final class EntityUseProof {
  public static boolean verified(
      int consumed, int candidates, long started, long received, double distanceSquared) {
    return consumed == 1
        && candidates == 1
        && received > started
        && received - started <= 5_000_000_000L
        && distanceSquared >= 0
        && distanceSquared <= 6.25;
  }

  private EntityUseProof() {}
}
