package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;

/** A bounded task composed from the same permission-gated, verified client tools as the AI. */
public final class RestockTask {
  public static TaskCard latest;
  private final long epoch = Agent.epoch;
  private final String scope = Store.scope(),
      dimension = mc().level.dimension().location().toString();
  private final BlockPos destination;
  private BlockPos source;
  private final String item, sourceName, destinationName;
  private final RestockPlan plan;
  private final Consumer<String> done;
  private final Set<BlockPos> exhausted = new HashSet<>();
  private final TaskCard card = new TaskCard();
  private boolean pending, finished;
  private int inspected;

  public RestockTask(
      String destinationName, String sourceName, String item, int target, Consumer<String> done) {
    if (item.isBlank() || item.equals("*"))
      throw new IllegalArgumentException("补给须指定物品 ID/名称，不能使用 *");
    this.destinationName = destinationName;
    this.sourceName = sourceName;
    this.item = item;
    this.done = done;
    destination = position(destinationName);
    plan = new RestockPlan(target);
    if (!sourceName.isBlank() && sameStorage(destination, position(sourceName)))
      throw new IllegalArgumentException("来源和交付点属于同一个箱子/双箱，不能互相补给");
    card.title = "补给 · " + destinationName;
    card.item = item;
    card.target = target;
    latest = card;
    Hud.FEED.acknowledge();
    update();
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  private static BlockPos position(String name) {
    JsonObject a = new JsonObject();
    a.addProperty("place", name);
    return Actions.target(a);
  }

  public boolean finished() {
    return finished;
  }

  public void cancel() {
    if (finished) return;
    plan.cancel();
    update();
    finished = true;
  }

  public void fail(String reason) {
    plan.block(reason);
    update();
  }

  public void tick() {
    if (finished || pending) return;
    if (epoch != Agent.epoch || !scope.equals(Store.scope())) {
      cancel();
      return;
    }
    if (!dimension.equals(mc().level.dimension().location().toString())) {
      plan.block("维度已切换，原补给地点与步骤作废");
      update();
      finish();
      return;
    }
    if (plan.terminal()) {
      finish();
      return;
    }
    switch (plan.step()) {
      case DESTINATION, RECHECK ->
          open(
              destination,
              () -> {
                plan.destination(countMenu());
                mc().player.closeContainer();
                update();
              });
      case SOURCE -> {
        if (source == null || exhausted.contains(source)) source = chooseSource();
        if (source == null) {
          plan.block(sourceName.isBlank() ? "没有更多已加载、已知且可用的普通仓库线索；请先扫描或指定来源" : "指定源箱已缺货，无法补足目标");
          update();
          return;
        }
        if (++inspected > 96) {
          plan.block("本次已检查来源 96 次，请检查空间与仓库状态后重试");
          update();
          return;
        }
        BlockPos chosen = source;
        open(
            chosen,
            () -> {
              int available = countMenu();
              mc().player.closeContainer();
              plan.source(available);
              if (available == 0) exhausted.add(chosen);
              update();
            });
      }
      case FETCH ->
          transfer(
              source,
              false,
              plan.requested(),
              receipt -> {
                plan.fetched(receipt.moved());
                if (receipt.containerAfter() == 0) exhausted.add(source);
                update();
              });
      case DEPOSIT ->
          transfer(
              destination,
              true,
              plan.carrying(),
              receipt -> {
                plan.deposited(receipt.moved());
                update();
              });
      default -> {}
    }
  }

  private BlockPos chooseSource() {
    if (!sourceName.isBlank()) {
      BlockPos p = position(sourceName);
      return exhausted.contains(p) ? null : p;
    }
    var player = mc().player;
    var candidates = new LinkedHashMap<String, WorldMemory.Point>();
    for (var p :
        Store.data.world.query(
            dimension,
            player.getBlockX(),
            player.getBlockY(),
            player.getBlockZ(),
            item,
            "storage",
            32,
            96)) candidates.put(p.key, p);
    for (var p :
        Store.data.world.query(
            dimension,
            player.getBlockX(),
            player.getBlockY(),
            player.getBlockZ(),
            "",
            "storage",
            32,
            96)) candidates.putIfAbsent(p.key, p);
    for (var point : candidates.values()) {
      BlockPos p = new BlockPos(point.x, point.y, point.z);
      if (!exhausted.contains(p)
          && mc().level.hasChunkAt(p)
          && Rules.standardStorage(point.block)
          && !sameStorage(p, destination)) return p;
    }
    return null;
  }

  static boolean sameStorage(BlockPos a, BlockPos b) {
    if (a.equals(b)) return true;
    if (!mc().level.hasChunkAt(a) || !mc().level.hasChunkAt(b)) return false;
    var state = mc().level.getBlockState(a);
    return state.getBlock() instanceof ChestBlock
        && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
        && a.relative(ChestBlock.getConnectedDirection(state)).equals(b);
  }

  private JsonObject args(BlockPos p) {
    JsonObject a = new JsonObject();
    a.addProperty("x", p.getX());
    a.addProperty("y", p.getY());
    a.addProperty("z", p.getZ());
    return a;
  }

  private void open(BlockPos p, Runnable observed) {
    pending = true;
    Agent.execute(
        "open_container",
        args(p),
        result -> {
          if (!valid()) return;
          pending = false;
          if (Rules.actionFailed(result)) {
            if (!p.equals(destination) && sourceName.isBlank()) {
              exhausted.add(p);
              card.problem = "来源不可用，尝试其他仓库：" + Rules.clean(result, 100);
              return;
            }
            plan.block(result);
            update();
            return;
          }
          try {
            if (!MenuContext.matches(p)
                || !MenuContext.synchronizedNow()
                || !MenuContext.kind().equals("storage")
                || !Rules.standardStorage(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(mc().level.getBlockState(p).getBlock())
                        .toString())) throw new IllegalArgumentException("菜单/方块尚未适配普通补给");
            observed.run();
          } catch (Exception e) {
            if (MenuContext.matches(p)) mc().player.closeContainer();
            plan.block("观察补给容器失败：" + e.getMessage());
            update();
          }
        });
  }

  private int countMenu() {
    return mc().player.containerMenu.slots.stream()
        .filter(s -> s.container != mc().player.getInventory() && Actions.match(s.getItem(), item))
        .mapToInt(s -> s.getItem().getCount())
        .sum();
  }

  private void transfer(
      BlockPos p, boolean deposit, int count, Consumer<TransferReceipt> received) {
    JsonObject a = args(p);
    a.addProperty("item", item);
    a.addProperty("count", count);
    pending = true;
    Agent.execute(
        deposit ? "deposit" : "fetch",
        a,
        result -> {
          if (!valid()) return;
          pending = false;
          TransferReceipt receipt = Actions.lastTransfer;
          String key = WorldMemory.key(dimension, p.getX(), p.getY(), p.getZ());
          if (receipt == null
              || receipt.epoch() != epoch
              || receipt.deposit() != deposit
              || !receipt.object().equals(key)
              || !receipt.item().equals(item)) {
            plan.uncertain("没有取得本步骤的重开数量证明：" + Rules.clean(result, 250));
            update();
            return;
          }
          received.accept(receipt);
        });
  }

  private boolean valid() {
    return !finished && epoch == Agent.epoch && scope.equals(Store.scope());
  }

  private void update() {
    card.current = plan.current();
    card.delivered = plan.delivered();
    card.carrying = plan.carrying();
    card.trips = plan.trips();
    card.uncertain = plan.uncertain();
    card.phase =
        switch (plan.step()) {
          case DESTINATION -> "确认交付箱库存";
          case SOURCE -> "查找并确认源库存";
          case FETCH -> "取货并重开核对";
          case DEPOSIT -> "运送并核对到货";
          case RECHECK -> "重新确认目标数量";
          case COMPLETE -> "补给目标已满足";
          case BLOCKED -> "补给中断，需要处理";
          case CANCELLED -> "已停止，待重新观察";
        };
    card.next =
        switch (plan.step()) {
          case DESTINATION, RECHECK -> "确认实际数量后计算缺口";
          case SOURCE -> sourceName.isBlank() ? "按已知仓库线索逐个打开确认" : "来源 · " + sourceName;
          case FETCH -> "核实取到的数量，再送交付箱";
          case DEPOSIT -> "交付 · " + destinationName;
          default -> "";
        };
    if (!plan.terminal()) card.problem = "";
    if (plan.terminal()) {
      card.problem = plan.reason();
      card.outcome =
          plan.step() == RestockPlan.Step.COMPLETE
              ? "complete"
              : plan.step() == RestockPlan.Step.CANCELLED ? "cancelled" : "blocked";
      card.ended = System.currentTimeMillis();
    }
  }

  private void finish() {
    finished = true;
    update();
    String result =
        plan.step() == RestockPlan.Step.COMPLETE
            ? "补给完成："
                + destinationName
                + " 已确认 "
                + plan.current()
                + "/"
                + plan.target
                + " 个 "
                + item
                + "；本轮核实送达 "
                + plan.delivered()
                + " 个，往返 "
                + plan.trips()
                + " 趟。"
            : "失败：补给已停止；本轮曾核实送达 "
                + plan.delivered()
                + " 个，上次账面携带 "
                + plan.carrying()
                + " 个。原因："
                + plan.reason()
                + "。当前数量请按现场重新核实，已发操作可能部分发生。";
    Companion.notifyUser(result);
    Store.journal(result);
    WorkLedger.add("task_result", result);
    done.accept(result);
  }
}
