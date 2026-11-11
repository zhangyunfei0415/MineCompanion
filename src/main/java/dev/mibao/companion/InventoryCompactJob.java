package dev.mibao.companion;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/** Each pickup/merge/return is confirmed by a new complete snapshot, including the cursor. */
public final class InventoryCompactJob extends Actions.Job {
  private final InventorySession session = new InventorySession();
  private List<ItemStack> expected;
  private ItemStack expectedCursor;
  private InventoryCompaction.Merge merge;
  private int phase, moves;

  public InventoryCompactJob(Consumer<String> done) {
    super(done);
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  void tick() {
    Actions.status = "背包整理 · 已核实合并 " + moves + " 组，保留快捷栏和装备";
    if (++ticks > 2400) {
      end(false, "达到两分钟预算；先核对鼠标物品");
      return;
    }
    int r = session.tick();
    if (r < 0) {
      end(false, session.error);
      return;
    }
    if (r == 0) return;
    if (expected != null && !session.confirms(expected, expectedCursor)) {
      end(false, "服务器背包与预期不一致；可能收到物资或人工调整，停止且不重放");
      return;
    }
    var menu = mc().player.inventoryMenu;
    if (phase == 0) {
      if (!menu.getCarried().isEmpty()) {
        end(false, "鼠标持有物品，请先收好");
        return;
      }
      for (int i = 1; i <= 4; i++)
        if (menu.getSlot(i).hasItem()) {
          end(false, "合成栏有物品，请先收好");
          return;
        }
      var entries = new ArrayList<InventoryCompaction.Stack>();
      for (int i = 0; i < 36; i++) {
        var s = mc().player.getInventory().getItem(i);
        entries.add(
            new InventoryCompaction.Stack(
                WorldSense.fingerprint(s), s.getCount(), s.getMaxStackSize()));
      }
      merge = InventoryCompaction.next(entries);
      if (merge == null) {
        end(true, "主背包可合并物品已整理；没有丢弃或转存物品");
        return;
      }
      if (moves >= 128) {
        end(false, "达到128组合并预算");
        return;
      }
      expected = new ArrayList<>(InventorySession.snapshot());
      int from = merge.from();
      expectedCursor = expected.get(from).copy();
      expected.set(from, ItemStack.EMPTY);
      click(from, 1);
      return;
    }
    if (phase == 1) {
      int to = merge.to();
      var target = expected.get(to).copy();
      target.grow(merge.moved());
      expected.set(to, target);
      expectedCursor = expectedCursor.copy();
      expectedCursor.shrink(merge.moved());
      click(to, 2);
      return;
    }
    if (phase == 2 && !expectedCursor.isEmpty()) {
      expected.set(merge.from(), expectedCursor.copy());
      expectedCursor = ItemStack.EMPTY;
      click(merge.from(), 3);
      return;
    }
    moves++;
    phase = 0;
    expected = null;
  }

  private void click(int slot, int next) {
    var m = mc().player.inventoryMenu;
    var s = m.getSlot(slot);
    if (!s.mayPickup(mc().player) || (!m.getCarried().isEmpty() && !s.mayPlace(m.getCarried()))) {
      end(false, "槽位不允许本次操作");
      return;
    }
    mc().gameMode.handleInventoryMouseClick(0, slot, 0, ClickType.PICKUP, mc().player);
    session.refresh();
    phase = next;
  }

  private void end(boolean success, String detail) {
    if (success) {
      session.close();
      InventoryMemory.capture();
      Store.save();
    }
    finish((success ? "成功：" : "部分完成：") + "主背包合并 " + moves + " 组。" + detail);
  }

  void stop() {
    super.stop(); /* Emergency stop leaves any carried item visible; never drops it. */
  }

  String interrupted() {
    return "背包整理已停止；已核实合并 " + moves + " 组；请核对鼠标物品和当前槽位，不重放点击。";
  }
}
