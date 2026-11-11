package dev.mibao.companion;

/** An AE proxy stack or an inventory prediction alone never proves a transfer. */
public final class AeTransferProof {
  public static boolean confirmed(
      boolean receipt,
      boolean deposit,
      int quantity,
      int beforePlayer,
      int actualPlayer,
      int serverPlayer,
      long beforeNetwork,
      long afterNetwork) {
    if (!receipt || quantity < 1 || quantity > 64 || beforeNetwork < 0 || afterNetwork < 0)
      return false;
    long delta = deposit ? -quantity : quantity;
    return (long) actualPlayer - beforePlayer == delta
        && serverPlayer == actualPlayer
        && afterNetwork - beforeNetwork == -delta;
  }
}
