package dev.mibao.companion;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

/** Reads the client's synchronized bite flag. No server fishing timers or loot tables are read. */
final class FishingSignal {
  private static final EntityDataAccessor<Boolean> BITING = accessor();

  private static EntityDataAccessor<Boolean> accessor() {
    try {
      // Forge 1.20.1 SRG name, mapped to DATA_BITING in the local official mappings.
      return ObfuscationReflectionHelper.getPrivateValue(FishingHook.class, null, "f_37102_");
    } catch (RuntimeException | LinkageError unavailable) {
      return null;
    }
  }

  static Boolean biting(FishingHook hook) {
    return BITING == null ? null : hook.getEntityData().get(BITING);
  }

  private FishingSignal() {}
}
