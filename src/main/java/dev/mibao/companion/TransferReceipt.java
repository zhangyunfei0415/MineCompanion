package dev.mibao.companion;

/** A unique authoritative transfer receipt; legacy constructor stays compatible. */
public record TransferReceipt(
    String object,
    String item,
    boolean deposit,
    int moved,
    int containerAfter,
    long epoch,
    String id) {
  public TransferReceipt(
      String object, String item, boolean deposit, int moved, int containerAfter, long epoch) {
    this(
        object,
        item,
        deposit,
        moved,
        containerAfter,
        epoch,
        java.util.UUID.randomUUID().toString());
  }
}
