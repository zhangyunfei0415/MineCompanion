package dev.mibao.companion;

/** Client prediction alone cannot prove a server accepted block destruction. */
public final class MiningProof {
  public static boolean broken(boolean actionIssued, boolean serverAir, boolean clientAir) {
    return actionIssued && serverAir && clientAir;
  }
}
