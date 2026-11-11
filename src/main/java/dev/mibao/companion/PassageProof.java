package dev.mibao.companion;

public final class PassageProof {
  public static boolean open(
      boolean issued, boolean closedNow, boolean serverMatches, boolean serverClosed) {
    return !closedNow && (!issued || serverMatches && !serverClosed);
  }

  private PassageProof() {}
}
