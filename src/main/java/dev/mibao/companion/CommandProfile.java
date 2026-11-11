package dev.mibao.companion;

/** Saved with the world/server ledger, never with portable skills. Player-edited only. */
public final class CommandProfile {
  public String playerCommands =
      "home,sethome,delhome,homes,tpa,tpaccept,tpdeny,rtp,spawn,back,warp,warps";
  public boolean customCommands, advancedCommands, broadSelectors;
  public int radius = 128, maxBlocks = 4096;
}
