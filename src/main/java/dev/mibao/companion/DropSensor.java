package dev.mibao.companion;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

/** Per-job short-lived entity observation; never scans unloaded blocks or persists entities. */
public final class DropSensor {
  private List<ItemEntity> cached = List.of();
  private int next;
  public boolean truncated;
  public int sampled;

  public List<ItemEntity> observe(AABB area, Predicate<ItemEntity> eligible, int tick) {
    if (tick < next) return cached;
    next = tick + 10;
    truncated = false;
    sampled = 0;
    var p = Minecraft.getInstance().player;
    var found =
        new PriorityQueue<ItemEntity>(
            Comparator.<ItemEntity>comparingDouble(p::distanceToSqr).reversed());
    long until = System.nanoTime() + 1_000_000;
    for (var e : Minecraft.getInstance().level.getEntitiesOfClass(ItemEntity.class, area)) {
      if (++sampled > 512 || sampled % 32 == 0 && System.nanoTime() > until) {
        truncated = true;
        break;
      }
      if (!eligible.test(e)) continue;
      found.add(e);
      if (found.size() > 64) {
        found.poll();
        truncated = true;
      }
    }
    cached = found.stream().sorted(Comparator.comparingDouble(p::distanceToSqr)).toList();
    return cached;
  }
}
