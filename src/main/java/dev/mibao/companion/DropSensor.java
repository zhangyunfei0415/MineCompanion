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
    int[] checked = {0};
    // Filter inside the entity query so the 512 budget counts only eligible drops, not unrelated clutter.
    var matches =
        Minecraft.getInstance()
            .level
            .getEntitiesOfClass(
                ItemEntity.class,
                area,
                e -> {
                  if (truncated) return false;
                  if (++checked[0] % 32 == 0 && System.nanoTime() > until) {
                    truncated = true;
                    return false;
                  }
                  return eligible.test(e);
                });
    sampled = checked[0];
    int accepted = 0;
    for (var e : matches) {
      if (++accepted > 512) {
        truncated = true;
        break;
      }
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
