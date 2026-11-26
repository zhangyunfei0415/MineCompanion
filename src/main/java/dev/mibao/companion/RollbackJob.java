package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Records agent block mutations into RollbackJournal (with Store.saveSoon async persistence) and
 * executes honest, client-only physical rollback using PlaceJob and MiningJob (never cheats or uses
 * OP commands). Suppresses self-recording during active rollback execution.
 */
public final class RollbackJob extends Actions.Job {
  public static volatile boolean recordingSuppressed;

  private final List<RollbackJournal.Entry> queue;
  private final boolean allowSubstitute;
  private final List<Long> completedIds = new ArrayList<>();
  private final List<String> blockEntityWarnings = new ArrayList<>();
  private int cursor, restored, skippedMissingItem, skippedChanged, failed;
  private boolean waitingChild;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public RollbackJob(
      List<RollbackJournal.Entry> entries, boolean allowSubstitute, Consumer<String> done) {
    super(done);
    this.queue = new ArrayList<>(entries);
    this.allowSubstitute = allowSubstitute;
  }

  public static void recordBreak(BlockPos pos, BlockState beforeState, String tool) {
    recordBreak(pos, beforeState, tool, Agent.currentTaskLabel());
  }

  public static void recordBreak(
      BlockPos pos, BlockState beforeState, String tool, String taskId) {
    if (recordingSuppressed || pos == null || beforeState == null) return;
    var mc = mc();
    if (mc.level == null || Store.data == null) return;
    String dim = mc.level.dimension().location().toString();
    String beforeId = BuiltInRegistries.BLOCK.getKey(beforeState.getBlock()).toString();
    if (BlueprintFormat.isAirBlock(beforeId)) return;
    RollbackJournal.record(
        Store.rollbackLog(),
        dim,
        pos.getX(),
        pos.getY(),
        pos.getZ(),
        RollbackJournal.Action.BREAK,
        beforeId,
        "minecraft:air",
        extractProps(beforeState),
        beforeState.hasBlockEntity(),
        tool,
        taskId == null ? "" : taskId,
        "",
        System.currentTimeMillis());
    Store.saveSoon();
  }

  public static void recordPlace(
      BlockPos pos, BlockState beforeState, BlockState afterState, String tool) {
    recordPlace(pos, beforeState, afterState, tool, Agent.currentTaskLabel());
  }

  public static void recordPlace(
      BlockPos pos, BlockState beforeState, BlockState afterState, String tool, String taskId) {
    if (recordingSuppressed || pos == null || afterState == null) return;
    var mc = mc();
    if (mc.level == null || Store.data == null) return;
    String dim = mc.level.dimension().location().toString();
    String beforeId =
        beforeState == null
            ? "minecraft:air"
            : BuiltInRegistries.BLOCK.getKey(beforeState.getBlock()).toString();
    String afterId = BuiltInRegistries.BLOCK.getKey(afterState.getBlock()).toString();
    if (BlueprintFormat.isAirBlock(afterId)) return;
    RollbackJournal.record(
        Store.rollbackLog(),
        dim,
        pos.getX(),
        pos.getY(),
        pos.getZ(),
        RollbackJournal.Action.PLACE,
        beforeId,
        afterId,
        beforeState == null ? Map.of() : extractProps(beforeState),
        beforeState != null && beforeState.hasBlockEntity(),
        tool,
        taskId == null ? "" : taskId,
        "",
        System.currentTimeMillis());
    Store.saveSoon();
  }

  private static Map<String, String> extractProps(BlockState state) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Property<?> p : state.getProperties()) {
      String name = p.getName();
      if (Set.of("facing", "axis", "half", "type").contains(name)) {
        out.put(name, propertyValue(state, p));
      }
    }
    return out;
  }

  private static <T extends Comparable<T>> String propertyValue(BlockState s, Property<T> prop) {
    return prop.getName(s.getValue(prop));
  }

  @Override
  void tick() {
    if (waitingChild) return;
    if (++ticks > 7200) {
      finishReceipt("部分完成：[ROLLBACK_TIMEOUT] 回滚超时");
      return;
    }
    var mc = mc();
    if (mc.player == null || mc.level == null) {
      finish("失败：[WRONG_CONTEXT] 世界未就绪");
      return;
    }
    String currentDim = mc.level.dimension().location().toString();
    Actions.status =
        "物理回滚中 · "
            + cursor
            + "/"
            + queue.size()
            + "（成功 "
            + restored
            + " · 缺料 "
            + skippedMissingItem
            + "）";

    while (cursor < queue.size()) {
      RollbackJournal.Entry e = queue.get(cursor++);
      if (!currentDim.equals(e.dim)) {
        skippedChanged++;
        continue;
      }
      BlockPos pos = new BlockPos(e.x, e.y, e.z);
      if (!mc.level.hasChunkAt(pos)) {
        skippedChanged++;
        continue;
      }
      BlockState cur = mc.level.getBlockState(pos);
      String curId = BuiltInRegistries.BLOCK.getKey(cur.getBlock()).toString();

      if ("BREAK".equals(e.action)) {
        if (!cur.canBeReplaced() && !BlueprintFormat.isAirBlock(curId)) {
          skippedChanged++;
          continue;
        }
        String restoreItem = BlueprintPlanner.toPlaceItemId(e.beforeBlock, e.beforeProps);
        if (allowSubstitute && !hasBagItem(restoreItem) && e.beforeBlock.equals("minecraft:stone")) {
          if (hasBagItem("minecraft:cobblestone")) restoreItem = "minecraft:cobblestone";
        }
        boolean creative =
            mc.gameMode != null
                && mc.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.CREATIVE;
        if (!creative && !hasBagItem(restoreItem)) {
          skippedMissingItem++;
          continue;
        }
        if (e.hasBlockEntity) {
          blockEntityWarnings.add(pos.toShortString() + ":" + e.beforeBlock);
        }
        JsonObject st = BlueprintPlanner.filterPlacementProperties(e.beforeProps);

        recordingSuppressed = true;
        waitingChild = true;
        PlaceJob place =
            new PlaceJob(
                restoreItem,
                pos,
                reply -> {
                  recordingSuppressed = false;
                  waitingChild = false;
                  if (Actions.job != null && Actions.job != this) return;
                  Actions.job = this;
                  if (!Rules.actionFailed(reply)) {
                    restored++;
                    completedIds.add(e.id);
                  } else if (reply.contains("缺少") || reply.contains("不足")) {
                    skippedMissingItem++;
                  } else {
                    failed++;
                  }
                });
        if (st.size() > 0 && !restoreItem.equals("minecraft:cobblestone")) place.state(st);
        Actions.job = place;
        return;
      } else if ("PLACE".equals(e.action)) {
        if (!curId.equals(e.afterBlock)) {
          skippedChanged++;
          continue;
        }
        if ("LIMITED".equals(Store.config.permission) && !Store.config.fullGameControl) {
          String denial =
              Rules.limitedDenial(
                  "break_block",
                  Store.config.allowMine,
                  Store.config.allowPlace,
                  Store.config.allowHunt);
          if (!denial.isEmpty()) {
            finish("部分完成：" + denial);
            return;
          }
        }
        recordingSuppressed = true;
        waitingChild = true;
        MiningJob mine =
            new MiningJob(
                e.afterBlock,
                1,
                4,
                pos,
                true,
                reply -> {
                  recordingSuppressed = false;
                  waitingChild = false;
                  if (Actions.job != null && Actions.job != this) return;
                  Actions.job = this;
                  if (!Rules.actionFailed(reply)) {
                    restored++;
                    completedIds.add(e.id);
                  } else {
                    failed++;
                  }
                });
        Actions.job = mine;
        return;
      }
    }

    finishReceipt("成功");
  }

  private void finishReceipt(String statusWord) {
    recordingSuppressed = false;
    if (!completedIds.isEmpty() && Store.data != null) {
      RollbackJournal.removeRestored(Store.rollbackLog(), completedIds);
      Store.saveSoon();
    }
    var json = new JsonObject();
    json.addProperty("restored", restored);
    json.addProperty("skipped_missing_item", skippedMissingItem);
    json.addProperty("skipped_changed", skippedChanged);
    json.addProperty("failed", failed);
    json.addProperty("total_requested", queue.size());
    if (!blockEntityWarnings.isEmpty()) {
      var arr = new JsonArray();
      for (String w : blockEntityWarnings) arr.add(w);
      json.add("block_entity_warnings", arr);
      json.addProperty("warning", "含方块实体的原容器/机器仅能放回空壳，原有库存与NBT无法由客户端复原");
    }
    String prefix =
        failed == 0 && skippedMissingItem == 0 && skippedChanged == 0 && statusWord.equals("成功")
            ? "成功"
            : "部分完成";
    finish(
        prefix
            + "：回滚执行完成（已恢复 "
            + restored
            + "，缺料跳过 "
            + skippedMissingItem
            + "，现场变化跳过 "
            + skippedChanged
            + "，失败 "
            + failed
            + "）。"
            + json);
  }

  private boolean hasBagItem(String itemId) {
    if (itemId == null || itemId.isBlank()) return false;
    var rl = ResourceLocation.tryParse(itemId);
    if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) return false;
    var item = BuiltInRegistries.ITEM.get(rl);
    if (!(item instanceof BlockItem)) return false;
    var p = mc().player;
    if (p == null) return false;
    for (int i = 0; i < 36; i++) {
      ItemStack s = p.getInventory().getItem(i);
      if (!s.isEmpty() && s.is(item)) return true;
    }
    return false;
  }

  @Override
  void stop() {
    recordingSuppressed = false;
    waitingChild = false;
    super.stop();
  }

  @Override
  String interrupted() {
    recordingSuppressed = false;
    return "回滚已停止；已复原 " + restored + " 格，请重新观察现场。";
  }
}
