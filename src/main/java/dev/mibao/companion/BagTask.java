package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Merge, return plain surplus, fill configured needs. Never equips or drops mod gear. */
public final class BagTask implements LocalRoutine {
  private final BagPolicy policy;
  private final Consumer<String> done;
  private final LogisticsRecord record;
  private final long epoch = Agent.epoch, deadline = System.currentTimeMillis() + 600_000;
  private final String scope = Store.scope(), dimension = LogisticsRuntime.dimension();
  private boolean pending, finished;
  private int phase, index, operations;
  private final List<String> items;
  private final List<String> warnings = new ArrayList<>();

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public BagTask(Consumer<String> done) {
    this(done, true);
  }

  public BagTask(Consumer<String> done, boolean attach) {
    this.done = done;
    policy = Store.GSON.fromJson(Store.GSON.toJson(Store.data.bag), BagPolicy.class);
    policy.validate();
    items = new ArrayList<>(policy.desired.keySet());
    var plan = new JsonObject();
    plan.addProperty("kind", "bag");
    record = LogisticsRuntime.record("行装 · " + policy.name, plan, 0, attach);
  }

  public boolean finished() {
    return finished;
  }

  public boolean ready() {
    return record.state.equals("COMPLETE");
  }

  private int count(String item) {
    int n = 0;
    for (int i = 0; i < 36; i++) {
      var s = mc().player.getInventory().getItem(i);
      boolean matches =
          item.contains("#")
              ? Actions.match(s, item)
              : !s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(item);
      if (matches
          && (!s.isDamageableItem()
              || s.getMaxDamage() - s.getDamageValue() >= policy.minDurability)) n += s.getCount();
    }
    return n;
  }

  private int free() {
    int n = 0;
    for (int i = 9; i < 36; i++) if (mc().player.getInventory().getItem(i).isEmpty()) n++;
    return n;
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
      fail("行装10分钟预算耗尽");
      return;
    }
    if (mc().screen != null) {
      child(
          "close_screen",
          new JsonObject(),
          r -> {
            if (Rules.actionFailed(r)) fail(r);
          });
      return;
    }
    if (phase == 0) {
      record.phase = "合并主背包";
      child(
          "organize_inventory",
          new JsonObject(),
          r -> {
            if (Rules.actionFailed(r)) {
              fail(r);
              return;
            }
            phase = 1;
          });
      return;
    }
    if ((phase == 1 || phase == 3) && policy.unload && !policy.destination.isBlank()) {
      for (int i = 9; i < 36; i++) {
        ItemStack s = mc().player.getInventory().getItem(i);
        if (s.isEmpty() || s.hasTag() || s.isDamageableItem() || LogisticsRuntime.protectedItem(s))
          continue;
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        String fingerprint = LogisticsRuntime.filter(id);
        int keep = Math.max(policy.reserve(fingerprint), LogisticsRuntime.reserve(fingerprint));
        int extra = BagPolicy.surplus(LogisticsRuntime.count(id), keep, false);
        if (extra > 0) {
          record.phase = "归仓多余物资";
          transfer(true, id, Math.min(extra, s.getCount()), policy.destination, false);
          return;
        }
      }
    }
    if (phase == 1) phase = 2;
    if (phase == 2 && index < items.size()) {
      String item = items.get(index);
      int missing = BagPolicy.deficit(policy.desired.get(item), count(item));
      if (missing == 0) {
        index++;
        return;
      }
      if (policy.source.isBlank()) {
        if (policy.mandatory.contains(item)) {
          fail("必要物资缺少 " + item + " ×" + missing + "，未绑定补给来源");
          return;
        }
        warnings.add("期望缺少 " + item + " ×" + missing);
        index++;
        return;
      }
      record.phase = "补充常备物资 " + item;
      transfer(false, item, Math.min(64, missing), policy.source, policy.mandatory.contains(item));
      return;
    }
    phase = 3;
    if (free() < policy.freeSlots)
      warnings.add("空主背包格 " + free() + " / 期望 " + policy.freeSlots + "，固定物品未移动");
    end(true, warnings.isEmpty() ? "常备物资与空间已检查" : String.join("；", warnings));
  }

  private void transfer(boolean deposit, String item, int count, String place, boolean required) {
    var a = new JsonObject();
    a.addProperty("place", place);
    String f = LogisticsRuntime.filter(item);
    a.addProperty("item", f);
    a.addProperty("count", count);
    var pos = Actions.target(a);
    String expected = WorldMemory.key(dimension, pos.getX(), pos.getY(), pos.getZ());
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
              || !proof.item().equals(f)
              || !proof.object().equals(expected)) {
            record.uncertain = true;
            fail("行装操作缺少本次数量证据：" + r);
            return;
          }
          record.transfer(proof.id(), deposit, proof.moved(), item);
          record.event((deposit ? "归仓" : "补给") + item + " ×" + proof.moved());
          if (proof.moved() != count) {
            if (deposit || required) {
              fail("行装未完整转移：" + r);
              return;
            }
            warnings.add("期望物资不足 " + item);
            index++;
          }
        });
  }

  private void child(String tool, JsonObject a, Consumer<String> result) {
    if (++operations > 80) {
      fail("行装达到80个子操作预算");
      return;
    }
    pending = true;
    Agent.execute(
        tool,
        a,
        r -> {
          if (finished || epoch != Agent.epoch) return;
          pending = false;
          try {
            result.accept(r);
          } catch (Exception e) {
            fail(Rules.clean(e.getMessage(), 180));
          }
        });
  }

  public void fail(String why) {
    end(false, why);
  }

  public void cancel() {
    if (finished) return;
    finished = true;
    record.state = "STOPPED";
    record.uncertain = pending;
    record.ended = System.currentTimeMillis();
    record.note = "行装中断，不丢弃或重放";
  }

  private void end(boolean success, String note) {
    if (finished) return;
    finished = true;
    record.state = success ? "COMPLETE" : record.uncertain ? "UNCERTAIN" : "BLOCKED";
    record.note = Rules.clean(note, 400);
    record.ended = System.currentTimeMillis();
    Store.save();
    done.accept((success ? "成功：" : "部分完成：") + record.summary());
  }
}
