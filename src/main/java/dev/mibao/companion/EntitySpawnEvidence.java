package dev.mibao.companion;

import java.util.*;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/** Server entity creation receipts, independent of predicted entities and unsynced mob targets. */
public final class EntitySpawnEvidence {
  private record Spawn(EntityType<?> type, Vec3 position, long received) {}

  private static final Map<UUID, Spawn> spawns = new LinkedHashMap<>();

  public static void reset() {
    spawns.clear();
  }

  public static void record(ClientboundAddEntityPacket p) {
    if (spawns.size() >= 256) spawns.remove(spawns.keySet().iterator().next());
    spawns.put(
        p.getUUID(),
        new Spawn(p.getType(), new Vec3(p.getX(), p.getY(), p.getZ()), System.nanoTime()));
  }

  public static boolean confirms(EntityType<?> type, Vec3 target, long started, int consumed) {
    int candidates = 0;
    Spawn match = null;
    for (var s : spawns.values())
      if (s.type() == type
          && s.received() > started
          && s.received() - started <= 5_000_000_000L
          && s.position().distanceToSqr(target) <= 6.25) {
        candidates++;
        match = s;
      }
    return match != null
        && EntityUseProof.verified(
            consumed,
            candidates,
            started,
            match.received(),
            match.position().distanceToSqr(target));
  }

  private EntitySpawnEvidence() {}
}
