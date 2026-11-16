package dev.mibao.companion;

import java.util.*;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/** Server entity creation receipts, independent of predicted entities and unsynced mob targets. */
public final class EntitySpawnEvidence {
  private record Spawn(EntityType<?> type, Vec3 position, long received, int ownerId) {}

  private static final Map<UUID, Spawn> spawns = new LinkedHashMap<>();

  public static void reset() {
    spawns.clear();
  }

  public static void record(ClientboundAddEntityPacket p) {
    var level = net.minecraft.client.Minecraft.getInstance().level;
    var entity = level == null ? null : level.getEntity(p.getId());
    var owner =
        entity instanceof net.minecraft.world.entity.projectile.Projectile projectile
            ? projectile.getOwner()
            : null;
    if (spawns.size() >= 256) spawns.remove(spawns.keySet().iterator().next());
    spawns.put(
        p.getUUID(),
        new Spawn(
            p.getType(),
            new Vec3(p.getX(), p.getY(), p.getZ()),
            System.nanoTime(),
            owner == null ? -1 : owner.getId()));
  }

  public static boolean ownedProjectile(Set<EntityType<?>> types, int ownerId, long started) {
    return spawns.values().stream()
        .anyMatch(
            s -> s.received() >= started && s.ownerId() == ownerId && types.contains(s.type()));
  }

  static EntityType<?> placedEntityType(net.minecraft.world.item.ItemStack item) {
    String id =
        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
    if (!id.startsWith("minecraft:")) return null;
    if (item.getItem() instanceof net.minecraft.world.item.BoatItem)
      return id.contains("chest") ? EntityType.CHEST_BOAT : EntityType.BOAT;
    if (!Set.of(
            "minecraft:minecart",
            "minecraft:chest_minecart",
            "minecraft:furnace_minecart",
            "minecraft:hopper_minecart",
            "minecraft:tnt_minecart",
            "minecraft:command_block_minecart",
            "minecraft:item_frame",
            "minecraft:glow_item_frame",
            "minecraft:painting",
            "minecraft:armor_stand",
            "minecraft:end_crystal")
        .contains(id)) return null;
    return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(
        net.minecraft.resources.ResourceLocation.tryParse(id));
  }

  static boolean placedEntity(
      net.minecraft.world.item.ItemStack item,
      Vec3 target,
      long started,
      int consumed,
      boolean creative) {
    var type = placedEntityType(item);
    return type != null && confirms(type, target, started, creative ? 1 : consumed);
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
