package dev.mibao.companion;

public final class PlacementProof {
  public static boolean accepted(
      boolean issued,
      boolean matchingServerBlock,
      boolean matchingClientBlock,
      int before,
      int actual,
      int server) {
    return issued
        && matchingServerBlock
        && matchingClientBlock
        && before > 0
        && actual == before - 1
        && server == actual;
  }
}
