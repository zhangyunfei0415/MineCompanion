package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeType;

/** Prepare -> normal child executor -> exact delivery receipts. Never replays a failed write. */
public final class LogisticsTask implements LocalRoutine {
  final LogisticsRecord record;
  private final LogisticsPlan plan;
  private final boolean automatic;
  private boolean yieldRequested;
  private final Consumer<String> done;
  private final long epoch = Agent.epoch, deadline = System.currentTimeMillis() + 1_800_000;
  private final String scope = Store.scope(),
      dimension = LogisticsRuntime.dimension(),
      destinationKey,
      sourceKey;
  private final String filter;
  private final int stockRetained;
  private int phase, operations, inputCount;
  private boolean pending, finished, bagChecked;
  private BagTask bag;
  private final TaskCard card = new TaskCard();

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public LogisticsTask(LogisticsPlan plan, Consumer<String> done) {
    this(plan, false, done);
  }

  public LogisticsTask(LogisticsPlan plan, boolean automatic, Consumer<String> done) {
    this.automatic = automatic;
    this.plan = plan;
    this.done = done;
    filter = LogisticsRuntime.filter(plan.item());
    if (LogisticsRuntime.protectedId(plan.item()))
      throw new IllegalArgumentException("目标物品已固定保护，请先调整行装保护清单");
    stockRetained = plan.kind().equals("stock") ? LogisticsRuntime.count(plan.item()) : 0;
    destinationKey = key(plan.destination());
    sourceKey = plan.source().isBlank() ? "" : key(plan.source());
    if (!sourceKey.isBlank()
        && RestockTask.sameStorage(
            Actions.target(place(plan.source())), Actions.target(place(plan.destination()))))
      throw new IllegalArgumentException("来源与交付属于同一个箱子");
    record =
        LogisticsRuntime.record(
            plan.kind() + " · " + plan.item() + " → " + plan.destination(),
            plan.json(),
            plan.count());
    card.kind = "logistics";
    card.title = record.title;
    card.item = plan.item();
    card.target = plan.count();
  }

  private static JsonObject place(String name) {
    var a = new JsonObject();
    a.addProperty("place", name);
    return a;
  }

  private static String key(String name) {
    var p = Actions.target(place(name));
    return WorldMemory.key(LogisticsRuntime.dimension(), p.getX(), p.getY(), p.getZ());
  }

  private JsonObject target(String name, String expected) {
    if (!key(name).equals(expected)) throw new IllegalArgumentException("地点绑定已改变，停止原委托");
    return place(name);
  }

  public boolean finished() {
    return finished;
  }

  public void requestYield() {
    if (automatic) yieldRequested = true;
  }

  public void tick() {
    if (finished || pending) return;
    if (epoch != Agent.epoch
        || !scope.equals(Store.scope())
        || !dimension.equals(LogisticsRuntime.dimension())) {
      cancel();
      return;
    }
    if (System.currentTimeMillis() > deadline) {
      fail("委托30分钟预算耗尽");
      return;
    }
    if (bag != null) {
      if (!bag.finished()) {
        bag.tick();
        return;
      }
      if (!bag.ready()) {
        fail("行前必要条件或同步未满足，请查看行装记录");
        return;
      }
      bag = null;
    }
    if (!bagChecked) {
      bagChecked = true;
      if (Store.data.bag.autoBeforeOrders && !plan.kind().equals("stock")) {
        bag =
            new BagTask(
                r -> {
                  if (r.startsWith("部分完成") || r.startsWith("失败")) record.event("行前准备未满足：" + r);
                },
                false);
        return;
      }
    }
    card.current = record.delivered;
    card.delivered = record.delivered;
    card.carrying = record.carrying;
    card.phase = record.phase;
    RestockTask.latest = card;
    if (mc().screen != null) {
      child(
          "close_screen",
          new JsonObject(),
          r -> {
            if (Rules.actionFailed(r)) fail(r);
          });
      return;
    }
    if (automatic
        && (yieldRequested || Store.data.community.next(dimension) != null)
        && record.fetched == record.delivered) {
      finished = true;
      record.state = "YIELDED";
      record.note = "人工作业优先，本轮维护已让出；下次检查重新观察库存";
      record.ended = System.currentTimeMillis();
      card.outcome = "yielded";
      card.ended = record.ended;
      card.phase = record.note;
      Store.save();
      done.accept("已让出：" + record.summary());
      return;
    }
    if (plan.kind().equals("stock")) {
      stock();
      return;
    }
    int available = available();
    record.carrying = Math.min(plan.count() - record.delivered, available);
    record.prepared =
        Math.max(record.prepared, Math.min(plan.count(), record.delivered + available));
    if (phase == 0 && available >= plan.count()) {
      phase = 1;
    }
    if (phase == 0) {
      record.phase = "准备剩余物资";
      int deficit = plan.count() - available;
      if (plan.kind().equals("deliver")) {
        fail("随身可交付数量不足；保留常备物资和固定物品后仅有" + available);
        return;
      }
      if (plan.kind().equals("supply")) {
        fetch(plan.item(), Math.min(64, deficit));
        return;
      }
      if (plan.kind().equals("craft")) {
        var a = new JsonObject();
        a.addProperty("item", plan.item());
        a.addProperty("count", deficit);
        a.addProperty("source", plan.source());
        produce("craft_item", a, "craft", plan.item(), deficit);
        return;
      }
      var recipe =
          mc().level.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING).stream()
              .filter(
                  r ->
                      r.getId().getNamespace().equals("minecraft")
                          && Arrays.stream(r.getIngredients().get(0).getItems())
                              .anyMatch(
                                  s ->
                                      !s.hasTag()
                                          && BuiltInRegistries.ITEM
                                              .getKey(s.getItem())
                                              .toString()
                                              .equals(plan.input())))
              .sorted(Comparator.comparing(r -> r.getId().toString()))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("没有匹配的普通炉配方"));
      var result = recipe.getResultItem(mc().level.registryAccess());
      if (result.hasTag()
          || !BuiltInRegistries.ITEM.getKey(result.getItem()).toString().equals(plan.item()))
        throw new IllegalArgumentException("默认普通炉配方产物与委托不同，未投料");
      int each = result.getCount();
      inputCount = (deficit + each - 1) / each;
      if (LogisticsRuntime.protectedId(plan.input())) {
        fail("熔炼输入已固定保护，未投料");
        return;
      }
      int inputMissing =
          inputCount
              + LogisticsRuntime.reserve(plan.input())
              - LogisticsRuntime.count(plan.input());
      if (inputMissing > 0) {
        fetch(plan.input(), Math.min(64, inputMissing));
        return;
      }
      int fuel = (inputCount * recipe.getCookingTime() + 1599) / 1600;
      int coal = usable("minecraft:coal"), charcoal = usable("minecraft:charcoal");
      if (Math.max(coal, charcoal) < fuel) {
        if (LogisticsRuntime.protectedId("minecraft:coal")) {
          fail("煤已固定保护，且可用木炭不足");
          return;
        }
        fetch("minecraft:coal", Math.min(64, fuel - coal));
        return;
      }
      var a = new JsonObject();
      a.addProperty("item", plan.input());
      a.addProperty("count", inputCount);
      a.addProperty("source", plan.source());
      produce("smelt_item", a, "smelt", plan.input(), deficit);
      return;
    }
    record.phase = "交付并核对";
    int amount = Math.min(plan.count() - record.delivered, available());
    if (amount <= 0) {
      fail("准备后物资变化，剩余数量不足，未再取货");
      return;
    }
    transfer(
        true,
        plan.item(),
        amount,
        () -> {
          if (record.delivered >= plan.count()) end(true, "目标交付数量已核实");
        });
  }

  private int usable(String item) {
    return LogisticsRuntime.protectedId(item)
        ? 0
        : Math.max(0, LogisticsRuntime.count(item) - LogisticsRuntime.reserve(item));
  }

  private int available() {
    return usable(plan.item());
  }

  private void fetch(String item, int count) {
    if (plan.source().isBlank()) {
      fail("缺少物资 " + item + " ×" + count + "，没有明确补料来源");
      return;
    }
    record.phase = "从指定来源准备 " + item;
    transfer(false, item, count, () -> {});
  }

  private void transfer(boolean deposit, String item, int amount, Runnable next) {
    String name = deposit ? plan.destination() : plan.source(),
        expected = deposit ? destinationKey : sourceKey;
    var a = target(name, expected);
    String f = LogisticsRuntime.filter(item);
    a.addProperty("item", f);
    a.addProperty("count", amount);
    TransferReceipt before = Actions.lastTransfer;
    child(
        deposit ? "deposit" : "fetch",
        a,
        r -> {
          var proof = Actions.lastTransfer;
          if (proof == null
              || proof == before
              || proof.epoch() != epoch
              || proof.deposit() != deposit
              || !proof.object().equals(expected)
              || !proof.item().equals(f)) {
            record.uncertain = true;
            fail("没有取得本次双方库存证据；" + r);
            return;
          }
          record.transfer(proof.id(), deposit, proof.moved(), item);
          record.carrying =
              Math.max(
                  0, LogisticsRuntime.count(plan.item()) - LogisticsRuntime.reserve(plan.item()));
          record.event((deposit ? "交付" : "取出") + item + " ×" + proof.moved() + " · " + proof.id());
          if (deposit) record.destinationCount = proof.containerAfter();
          Store.save();
          if (proof.moved() != amount) {
            fail("本步仅核实 " + proof.moved() + "/" + amount + "；" + r);
            return;
          }
          next.run();
        });
  }

  private void produce(String tool, JsonObject args, String mode, String input, int minimum) {
    ProductionTask.Output before = ProductionTask.lastOutput;
    child(
        tool,
        args,
        r -> {
          var output = ProductionTask.lastOutput;
          boolean verified =
              output != null
                  && output != before
                  && output.epoch() == epoch
                  && output.mode().equals(mode)
                  && output.input().equals(input)
                  && output.item().equals(plan.item())
                  && output.quantity() > 0;
          if (verified) {
            record.produced(output.id(), output.quantity());
            record.event(
                "已逐批核实产出 " + output.item() + " ×" + output.quantity() + " · " + output.id());
            Store.save();
          }
          if (Rules.actionFailed(r) || !verified || output.quantity() < minimum) {
            fail("生产未全部完成，保留已核实产出与现场；" + r);
            return;
          }
          phase = 0;
        });
  }

  private void stock() {
    if (phase == 0) {
      record.phase = "打开维护箱复核库存";
      child(
          "open_container",
          target(plan.destination(), destinationKey),
          r -> {
            if (Rules.actionFailed(r)
                || !MenuContext.synchronizedNow()
                || !MenuContext.kind().equals("storage")) {
              fail("库存观察失败：" + r);
              return;
            }
            int count =
                mc().player.containerMenu.slots.stream()
                    .filter(
                        s ->
                            s.container != mc().player.getInventory()
                                && Actions.match(s.getItem(), filter))
                    .mapToInt(s -> s.getItem().getCount())
                    .sum();
            record.destinationCount = count;
            phase = plan.needsRefill(count) ? 1 : 2;
          });
      return;
    }
    if (phase == 2) {
      end(true, "库存未低于下限，无需生成补货");
      return;
    }
    if (phase == 3) {
      int cargo = record.fetched - record.delivered;
      if (!StockTarget.canDeliverCargo(LogisticsRuntime.count(plan.item()), stockRetained, cargo)) {
        fail("新取物资已变化，无法同时保留原有随身数量，不挪用个人储备");
        return;
      }
      transfer(
          true,
          plan.item(),
          cargo,
          () -> {
            if (record.destinationCount >= plan.count()) end(true, "维护箱目标数量已核实");
            else phase = 1;
          });
      return;
    }
    int needed = plan.count() - record.destinationCount;
    if (needed <= 0) {
      end(true, "库存目标已满足");
      return;
    }
    int amount = plan.nextBatch(record.destinationCount);
    record.phase = "维护补货";
    // Only cargo fetched by this phase is delivered; existing equipment/reserves are untouched
    // numerically.
    transfer(
        false,
        plan.item(),
        amount,
        () -> {
          phase = 3;
        });
    if (phase == 3) return;
  }

  private void child(String tool, JsonObject args, Consumer<String> result) {
    if (++operations > 32) {
      fail("达到32个子操作预算");
      return;
    }
    pending = true;
    Agent.execute(
        tool,
        args,
        r -> {
          if (finished || epoch != Agent.epoch) return;
          pending = false;
          try {
            result.accept(r);
          } catch (Exception e) {
            fail(Rules.clean(e.getMessage(), 200));
          }
        });
  }

  public void fail(String why) {
    if (finished) return;
    end(false, why);
  }

  public void cancel() {
    if (finished) return;
    if (bag != null) bag.cancel();
    finished = true;
    record.state = "STOPPED";
    record.uncertain = pending;
    record.ended = System.currentTimeMillis();
    record.note = "作业停止，未确认操作不重放";
  }

  private void end(boolean success, String note) {
    if (finished) return;
    finished = true;
    record.note = Rules.clean(note, 400);
    record.state =
        success
            ? "COMPLETE"
            : record.uncertain
                ? "UNCERTAIN"
                : record.delivered > 0 || record.produced > 0 || record.fetched > 0
                    ? "PARTIAL"
                    : "BLOCKED";
    record.ended = System.currentTimeMillis();
    card.current = record.delivered;
    card.delivered = record.delivered;
    card.carrying = record.carrying;
    RestockTask.latest = card;
    card.outcome = success ? "complete" : "blocked";
    card.ended = record.ended;
    card.phase = record.summary();
    card.uncertain = record.uncertain;
    Store.save();
    done.accept((success ? "成功：" : "部分完成：") + record.summary());
  }
}
