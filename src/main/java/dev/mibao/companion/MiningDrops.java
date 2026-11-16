package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** One batch of newly observed item entities and server pickup receipts. */
final class MiningDrops {
  private record Drop(UUID uuid, Vec3 origin, ItemStack stack, int pickedUp) {}

  private final Set<UUID> preexisting = new HashSet<>();
  private final Map<Integer, Drop> observed = new LinkedHashMap<>();

  void begin() {
    observed.clear();
    preexisting.clear();
    var mc = Minecraft.getInstance();
    for (var item :
        mc.level.getEntitiesOfClass(ItemEntity.class, mc.player.getBoundingBox().inflate(48)))
      preexisting.add(item.getUUID());
  }

  void observe(int entityId, Set<BlockPos> issued) {
    var mc = Minecraft.getInstance();
    if (!(mc.level.getEntity(entityId) instanceof ItemEntity item)
        || item.getItem().isEmpty()
        || preexisting.contains(item.getUUID())
        || observed.size() >= 512) return;
    if (observed.containsKey(entityId)) return;
    if (issued.stream().noneMatch(p -> Vec3.atCenterOf(p).distanceToSqr(item.position()) <= 16))
      return;
    observed.put(entityId, new Drop(item.getUUID(), item.position(), item.getItem().copy(), 0));
  }

  void pickup(int entityId, int collector, int amount) {
    if (collector != Minecraft.getInstance().player.getId() || amount <= 0) return;
    var drop = observed.get(entityId);
    if (drop == null) return;
    int received = Math.min(drop.stack().getCount(), drop.pickedUp() + amount);
    observed.put(entityId, new Drop(drop.uuid(), drop.origin(), drop.stack(), received));
  }

  Map<String, Integer> pickups(Set<BlockPos> broken) {
    Map<String, Integer> picked = new LinkedHashMap<>();
    for (var drop : observed.values()) {
      if (drop.pickedUp() <= 0
          || broken.stream().noneMatch(p -> Vec3.atCenterOf(p).distanceToSqr(drop.origin()) <= 16))
        continue;
      picked.merge(WorldSense.fingerprint(drop.stack()), drop.pickedUp(), Integer::sum);
    }
    return picked;
  }

  boolean eligible(ItemEntity item) {
    return observed.containsKey(item.getId())
        && observed.get(item.getId()).uuid().equals(item.getUUID());
  }
}
