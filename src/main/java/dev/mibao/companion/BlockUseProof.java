package dev.mibao.companion;

/** Evidence semantics: consuming an item alone does not prove an intended world effect. */
public final class BlockUseProof {
  private BlockUseProof() {}

  public static boolean verified(
      boolean serverWorldChange, boolean synchronizedMenu, boolean inventorySynchronized) {
    return inventorySynchronized && (serverWorldChange || synchronizedMenu);
  }

  public static String outcome(boolean effect, int consumed) {
    return effect
        ? (consumed > 0 ? "world_effect_and_consumption" : "world_effect_without_consumption")
        : consumed > 0 ? "consumed_effect_unconfirmed" : "no_confirmed_effect";
  }
}
