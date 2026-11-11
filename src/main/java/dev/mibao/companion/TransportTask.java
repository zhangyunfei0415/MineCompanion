package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** One bounded, exact-count trip, composed from verified and permission-gated transfers. */
public final class TransportTask {
  private final long epoch = Agent.epoch;
  private final String scope = Store.scope(),
      dimension = Minecraft.getInstance().level.dimension().location().toString();
  private final BlockPos source, destination;
  private final String item;
  private final int count;
  private final Consumer<String> done;
  private boolean pending, finished;
  private int phase = -2, fetched, delivered, returned;
  private final TaskCard card = new TaskCard();
  private Map<String, Integer> before;
  private final Deque<Map.Entry<String, Integer>> cargo = new ArrayDeque<>();

  public TransportTask(String from, String to, String item, int count, Consumer<String> done) {
    this.item = item;
    this.count = count;
    this.done = done;
    source = position(from);
    destination = position(to);
    if (RestockTask.sameStorage(source, destination))
      throw new IllegalArgumentException("来源与目标是同一个箱子/双箱");
    if (item.isBlank() || item.equals("*")) throw new IllegalArgumentException("定量搬运必须指定物品/NBT指纹");
    card.title = "搬运 · " + from + " → " + to;
    card.item = item;
    card.target = count;
    card.current = 0;
    RestockTask.latest = card;
  }

  private static BlockPos position(String name) {
    JsonObject a = new JsonObject();
    a.addProperty("place", name);
    return Actions.target(a);
  }

  public boolean finished() {
    return finished;
  }

  public void fail(String why) {
    if (finished) return;
    card.uncertain = pending;
    end("部分完成：搬运异常停止；已确认取出 " + fetched + "、交付 " + delivered + "。" + why + "；请重新核实现场。");
  }

  public void tick() {
    if (finished || pending) return;
    if (epoch != Agent.epoch
        || !scope.equals(Store.scope())
        || !dimension.equals(Minecraft.getInstance().level.dimension().location().toString())) {
      cancel();
      return;
    }
    if (phase == -2) {
      pending = true;
      var a = new JsonObject();
      a.addProperty("x", destination.getX());
      a.addProperty("y", destination.getY());
      a.addProperty("z", destination.getZ());
      Agent.execute(
          "open_container",
          a,
          r -> {
            pending = false;
            if (Rules.actionFailed(r)) {
              end("失败：[DESTINATION_PREFLIGHT] 交付箱打开检查未通过，未取货；" + r);
              return;
            }
            phase = -1;
          });
      return;
    }
    if (phase == -1) {
      pending = true;
      Agent.execute(
          "close_screen",
          new JsonObject(),
          r -> {
            pending = false;
            if (Rules.actionFailed(r)) end(r);
            else phase = 0;
          });
      return;
    }
    if (phase == 2 && delivered < count && fetched == delivered) {
      phase = 0;
      return;
    }
    if (phase == 2) {
      end(
          (delivered == count ? "成功：" : "部分完成：")
              + "搬运取出 "
              + fetched
              + "、交付 "
              + delivered
              + " / "
              + count
              + "；双方数量均经重开核对。");
      return;
    }
    boolean rollback = phase == 3, deposit = phase == 1 || rollback;
    BlockPos pos = rollback ? source : deposit ? destination : source;
    if (deposit && cargo.isEmpty()) {
      if (rollback)
        end(
            "部分完成：[DELIVERY_ROLLED_BACK] 交付未完成，已核实退回来源 "
                + returned
                + " 件，已交付 "
                + delivered
                + "；携带账面剩余 "
                + Math.max(0, fetched - delivered - returned)
                + "，未继续取货。");
      else phase = 2;
      return;
    }
    String filter = deposit ? cargo.peekFirst().getKey() : item;
    int wanted = deposit ? cargo.peekFirst().getValue() : Math.min(64, count - fetched);
    if (!deposit) before = PlayerEvidence.actual(item);
    card.phase = rollback ? "交付受阻 · 把已确认剩余货物退回来源" : deposit ? "走向交付箱并核对存入" : "走向源箱并核对取出";
    card.next = "一次最多64个；" + Companion.STOP.getTranslatedKeyMessage().getString() + "立即停止";
    JsonObject a = new JsonObject();
    a.addProperty("x", pos.getX());
    a.addProperty("y", pos.getY());
    a.addProperty("z", pos.getZ());
    a.addProperty("item", filter);
    a.addProperty("count", wanted);
    pending = true;
    checkpoint(deposit ? "deposit_pending" : "fetch_pending", true);
    TransferReceipt previous = Actions.lastTransfer;
    Agent.execute(
        deposit ? "deposit" : "fetch",
        a,
        result -> {
          if (finished || epoch != Agent.epoch) return;
          pending = false;
          var receipt = Actions.lastTransfer;
          String object = WorldMemory.key(dimension, pos.getX(), pos.getY(), pos.getZ());
          if (receipt == null
              || receipt == previous
              || receipt.epoch() != epoch
              || receipt.deposit() != deposit
              || !receipt.object().equals(object)
              || !receipt.item().equals(filter)) {
            if (deposit
                && !rollback
                && result.contains("[TRANSFER_NOT_STARTED]")
                && !cargo.isEmpty()) {
              phase = 3;
              checkpoint("return_to_source", false);
              return;
            }
            card.uncertain = true;
            end(
                "部分完成：搬运停止，已核实取出 "
                    + fetched
                    + "、交付 "
                    + delivered
                    + "；待交付账面数量 "
                    + Math.max(0, fetched - delivered - returned)
                    + "，请先观察背包并交付剩余，禁止从源箱重复取货。本步未取得新转移证据。"
                    + result);
            return;
          }
          if (deposit) {
            if (receipt.moved() < 0 || receipt.moved() > wanted) {
              card.uncertain = true;
              end("部分完成：[RESULT_UNCONFIRMED] 转移数量超出计划，停止");
              return;
            }
            if (rollback) returned += receipt.moved();
            else delivered += receipt.moved();
            card.current = delivered;
            card.delivered = delivered;
            card.carrying = Math.max(0, fetched - delivered - returned);
            cargo.removeFirst();
            if (receipt.moved() != wanted) {
              if (receipt.moved() < 0 || receipt.moved() > wanted) {
                card.uncertain = true;
                end("部分完成：[RESULT_UNCONFIRMED] 转移数量超出计划，停止");
                return;
              }
              cargo.addFirst(Map.entry(filter, wanted - receipt.moved()));
              if (rollback) {
                end(
                    "部分完成：[ROLLBACK_BLOCKED] 来源容器不能收回全部余货；已退回 "
                        + returned
                        + "，背包账面剩余 "
                        + card.carrying);
                return;
              }
              phase = 3;
              checkpoint("return_to_source", false);
              return;
            }
            phase = rollback ? 3 : cargo.isEmpty() ? 2 : 1;
          } else {
            fetched += receipt.moved();
            card.carrying = fetched - delivered;
            if (receipt.moved() <= 0) {
              end("失败：源箱没有可取物品");
              return;
            }
            try {
              cargo.addAll(
                  DeltaProof.cargo(before, PlayerEvidence.actual(item), receipt.moved())
                      .entrySet());
            } catch (IllegalArgumentException e) {
              card.uncertain = true;
              end("部分完成：源箱取货已核实，但背包变体清单变化；停止交付，请重新观察。");
              return;
            }
            phase = 1;
          }
          checkpoint("transfer_confirmed", false);
        });
  }

  private void checkpoint(String phase, boolean uncertain) {
    if (!scope.equals(Store.scope())) return;
    JsonObject d = new JsonObject();
    d.addProperty("item", item);
    d.addProperty("requested", count);
    d.addProperty("fetched", fetched);
    d.addProperty("delivered", delivered);
    d.addProperty("returned_to_source", returned);
    d.addProperty("carrying_confirmed", Math.max(0, fetched - delivered - returned));
    d.add(
        "cargo",
        Store.GSON.toJsonTree(
            cargo.stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a + b))));
    RecoveryLedger.put("transport", phase, uncertain, d, dimension);
  }

  public void cancel() {
    if (finished) return;
    checkpoint("cancelled", pending);
    card.uncertain = pending;
    card.outcome = "cancelled";
    card.phase = "搬运已停止；请核实现有物品";
    card.problem =
        "已核实取出 " + fetched + "、交付 " + delivered + "、退回 " + returned + (pending ? "；当前转移待核实" : "");
    card.ended = System.currentTimeMillis();
    finished = true;
    Companion.notifyUser(card.problem);
  }

  private void end(String text) {
    checkpoint(text.startsWith("成功") ? "complete" : "stopped", pending || card.uncertain);
    finished = true;
    card.outcome = text.startsWith("成功") ? "complete" : "blocked";
    card.phase = text;
    card.problem = text.startsWith("成功") ? "" : text;
    card.ended = System.currentTimeMillis();
    done.accept(text);
  }
}
