package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Short-lived read-only observation of one interaction: inventory delta, item entities that
 * appeared nearby, and block/machine changes for at most {@link #WINDOW_TICKS} client ticks. Never
 * sends a packet and never reports on-ground drops as picked up.
 */
public final class InteractionOutcome {
  public static final int WINDOW_TICKS = 40;

  private record Watch(BlockPos pos, BlockState before, String machine, long revision) {}

  private final BlockPos[] positions;
  private final List<Watch> watches = new ArrayList<>();
  private final Map<String, Integer> inventoryBefore = new LinkedHashMap<>();
  private final List<ItemStack> slotBefore = new ArrayList<>();
  private final Set<Integer> knownEntities = new LinkedHashSet<>();
  private final Map<Integer, String[]> freshEntities = new LinkedHashMap<>();
  private final DropSensor sensor = new DropSensor();
  private AABB area;
  private int ticks;
  private boolean blockChanged, machineChanged, truncated;
  private String blockAfter = "";

  private InteractionOutcome(BlockPos[] positions) {
    this.positions = positions;
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  /** Starts the window around the given target blocks (none for inventory-only commands). */
  public static InteractionOutcome begin(BlockPos... watch) {
    InteractionOutcome outcome = new InteractionOutcome(watch == null ? new BlockPos[0] : watch);
    outcome.captureBaseline();
    return outcome;
  }

  private void captureBaseline() {
    var player = mc().player;
    if (player == null || mc().level == null) return;
    PlayerEvidence.begin();
    var inventory = player.getInventory();
    for (int i = 0; i < 36; i++) {
      ItemStack stack = inventory.getItem(i);
      slotBefore.add(stack.copy());
      if (!stack.isEmpty())
        inventoryBefore.merge(WorldSense.fingerprint(stack), stack.getCount(), Integer::sum);
    }
    for (BlockPos pos : positions) {
      watches.add(
          new Watch(
              pos,
              mc().level.getBlockState(pos),
              MachineObservation.state(pos),
              MachineObservation.revision(pos)));
    }
    AABB observed = null;
    for (BlockPos pos : positions) observed = union(observed, new AABB(pos).inflate(8));
    area = union(observed, player.getBoundingBox().inflate(8));
    for (ItemEntity entity : mc().level.getEntitiesOfClass(ItemEntity.class, area))
      knownEntities.add(entity.getId());
  }

  public void tick() {
    if (area == null || mc().level == null || mc().player == null) return;
    ticks++;
    for (Watch watch : watches) {
      BlockState current = mc().level.getBlockState(watch.pos());
      if (!current.equals(watch.before())) {
        BlockState server = PlayerEvidence.block(watch.pos());
        if (server != null && server.equals(current)) {
          blockChanged = true;
          blockAfter = blockId(current);
        }
      }
      if (MachineObservation.changed(watch.pos(), watch.machine(), watch.revision()))
        machineChanged = true;
    }
    for (ItemEntity entity : sensor.observe(area, candidate -> true, ticks)) {
      if (knownEntities.contains(entity.getId())) continue;
      ItemStack stack = entity.getItem();
      if (stack.isEmpty()) continue;
      freshEntities.putIfAbsent(
          entity.getId(), new String[] {itemId(stack), Integer.toString(stack.getCount())});
    }
    if (sensor.truncated) truncated = true;
  }

  public boolean ready() {
    return area != null && ticks >= WINDOW_TICKS;
  }

  public int ticks() {
    return ticks;
  }

  public boolean confirmed() {
    return InteractionReceipt.confirmed(evidence());
  }

  public String detail() {
    return InteractionReceipt.summarize(evidence());
  }

  /** Evidence JSON shared by interaction jobs and the pure receipt/grading helpers. */
  public JsonObject evidence() {
    JsonObject out = new JsonObject();
    out.addProperty("ticks", ticks);
    JsonArray inventory = new JsonArray();
    Map<String, Integer> now = inventoryNow();
    Set<String> ids = new LinkedHashSet<>(inventoryBefore.keySet());
    ids.addAll(now.keySet());
    for (String id : ids) {
      int delta = now.getOrDefault(id, 0) - inventoryBefore.getOrDefault(id, 0);
      if (delta == 0) continue;
      JsonObject row = new JsonObject();
      row.addProperty("id", id.split("#", 2)[0]);
      row.addProperty("fingerprint", id);
      row.addProperty("delta", delta);
      inventory.add(row);
    }
    out.add("inventory", inventory);
    out.addProperty(
        "inventory_source",
        "post-action server slot receipts matching current main inventory; no causal attribution");
    JsonArray dropped = new JsonArray();
    for (String[] row : freshEntities.values()) {
      JsonObject item = new JsonObject();
      item.addProperty("id", row[0]);
      item.addProperty("count", Integer.parseInt(row[1]));
      dropped.add(item);
    }
    out.add("dropped", dropped);
    if (truncated) out.addProperty("entity_scan_truncated", true);
    if (!watches.isEmpty()) {
      Watch first = watches.get(0);
      out.addProperty("block_position", first.pos().toShortString());
      out.addProperty("block_before", blockId(first.before()));
      out.addProperty("block_changed", blockChanged);
      if (blockChanged) out.addProperty("block_after", blockAfter);
      out.addProperty("machine_changed", machineChanged);
    }
    return out;
  }

  private Map<String, Integer> inventoryNow() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    var player = mc().player;
    if (player == null) return counts;
    for (int i = 0; i < slotBefore.size(); i++) {
      ItemStack current = player.getInventory().getItem(i);
      ItemStack stack =
          PlayerEvidence.slotConfirmed(i)
                  && ItemStack.matches(current, PlayerEvidence.inventorySlot(i))
              ? current
              : slotBefore.get(i);
      if (!stack.isEmpty())
        counts.merge(WorldSense.fingerprint(stack), stack.getCount(), Integer::sum);
    }
    return counts;
  }

  private static String itemId(ItemStack stack) {
    return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
  }

  private static String blockId(BlockState state) {
    return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
  }

  private static AABB union(AABB a, AABB b) {
    if (a == null) return b;
    return new AABB(
        Math.min(a.minX, b.minX),
        Math.min(a.minY, b.minY),
        Math.min(a.minZ, b.minZ),
        Math.max(a.maxX, b.maxX),
        Math.max(a.maxY, b.maxY),
        Math.max(a.maxZ, b.maxZ));
  }
}
