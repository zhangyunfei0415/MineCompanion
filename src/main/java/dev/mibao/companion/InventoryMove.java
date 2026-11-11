package dev.mibao.companion;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/** Reusable sub-operation: verified swaps, including main inventory -> hotbar -> equipment. */
public final class InventoryMove {
  private final InventorySession session = new InventorySession();
  private final Predicate<ItemStack> filter;
  private final String destination;
  private final int requestedSlot;
  private final boolean stow;
  private final boolean main;
  private final Deque<int[]> swaps = new ArrayDeque<>();
  private List<ItemStack> expected;
  private ItemStack expectedCursor;
  private boolean planned, finished;
  private int phase;
  private boolean workSlot, displaced, emptyHand;
  public int readySlot = -1;
  public String error = "", detail = "";

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public InventoryMove(Predicate<ItemStack> filter, String destination, int slot) {
    this.filter = filter;
    this.destination = destination;
    requestedSlot = slot;
    stow = destination.equals("stow_cursor");
    main = destination.equals("main");
    workSlot = destination.equals("hotbar") && slot < 0;
    if (destination.equals("hotbar") && (slot < -1 || slot > 8))
      throw new IllegalArgumentException("快捷栏索引须为0..8，-1自动选空位");
    if (!stow && !main && !destination.equals("hotbar")) InventoryLayout.equipment(destination);
  }

  public static InventoryMove ensure(Predicate<ItemStack> filter) {
    var move = new InventoryMove(filter, "hotbar", -1);
    move.workSlot = true;
    return move;
  }

  public static InventoryMove emptyHand() {
    var move = ensure(ItemStack::isEmpty);
    move.emptyHand = true;
    return move;
  }

  public int tick() {
    if (finished) return error.isBlank() ? 1 : -1;
    int r = session.tick();
    if (r < 0) return fail(session.error);
    if (r == 0) return 0;
    var p = mc().player;
    var m = p.inventoryMenu;
    if (phase == 1) {
      if (!session.confirms(expected, expectedCursor)) return fail("服务器背包结果与预期不一致；不重试，避免重复搬动");
      phase = 0;
    }
    if (!planned) {
      planned = true;
      if (stow) {
        var carried = m.getCarried();
        if (carried.isEmpty()) return success();
        int empty = -1;
        for (int i = 9; i <= 44; i++) {
          var s = m.getSlot(i);
          if (!s.hasItem()
              && s.mayPlace(carried)
              && s.getMaxStackSize(carried) >= carried.getCount()) {
            empty = i;
            break;
          }
        }
        if (empty < 0) return fail("背包没有能完整接住鼠标物品的空槽；保留物品，不关闭界面");
        expected = new ArrayList<>(InventorySession.snapshot());
        expected.set(empty, carried.copy());
        expectedCursor = ItemStack.EMPTY;
        mc().gameMode.handleInventoryMouseClick(0, empty, 0, ClickType.PICKUP, p);
        session.refresh();
        phase = 1;
        return 0;
      }
      if (!m.getCarried().isEmpty()) return fail("鼠标上持有物品，请先运行收好鼠标物品");
      for (int i = 1; i <= 4; i++) if (m.getSlot(i).hasItem()) return fail("合成栏尚有物品，请先人工收好再整理");
      if (main) {
        int source = -1;
        for (int i = 0; i < 9; i++)
          if (filter.test(p.getInventory().getItem(i))) {
            source = i;
            break;
          }
        if (source < 0) {
          for (int i = 9; i < 36; i++)
            if (filter.test(p.getInventory().getItem(i))) {
              detail = "指定物品已在主背包中。";
              return success();
            }
          return fail("快捷栏和主背包中没有符合条件的物品");
        }
        var item = p.getInventory().getItem(source);
        int target = -1;
        for (int i = 9; i < 36; i++) {
          var slot = m.getSlot(InventoryLayout.menuSlot(i));
          if (!slot.hasItem() && slot.mayPlace(item)) {
            target = i;
            break;
          }
        }
        if (target < 0) return fail("主背包没有能容纳该物品的空槽；先整理背包或指定其他去向");
        swaps.add(new int[] {InventoryLayout.menuSlot(target), source});
        readySlot = source;
        detail = WorldSense.fingerprint(item) + "；目标主背包槽位 " + target;
      } else {
        if (!destination.equals("hotbar")
            && filter.test(m.getSlot(InventoryLayout.equipment(destination)).getItem())) {
          detail = "指定装备位置已经有符合条件的物品。";
          return success();
        }
        int source = -1;
        for (int i = 0; i <= 40; i++)
          if ((i < 36 || i == 40) && filter.test(p.getInventory().getItem(i))) {
            source = i;
            break;
          }
        if (source < 0) return fail(emptyHand ? "没有空主背包格可以收起快捷栏物品；先整理背包或按明确交付箱存货" : "背包中没有符合条件的物品");
        int hotbar = requestedSlot;
        if (!destination.equals("hotbar") && source < 9) hotbar = source;
        if (destination.equals("hotbar") && hotbar < 0 && source < 9) {
          readySlot = source;
          return success();
        }
        if (hotbar < 0) {
          for (int i = 0; i < 9; i++)
            if (p.getInventory().getItem(i).isEmpty()) {
              hotbar = i;
              break;
            }
        }
        if (hotbar < 0 && workSlot) {
          List<WorkSlotPolicy.Slot> slots = new ArrayList<>();
          for (int i = 0; i < 9; i++) {
            var s = p.getInventory().getItem(i);
            slots.add(
                new WorkSlotPolicy.Slot(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(s.getItem())
                        .toString(),
                    s.isEmpty(),
                    s.hasTag()));
          }
          hotbar = WorkSlotPolicy.choose(slots, p.getInventory().selected);
          displaced = hotbar >= 0;
        }
        if (hotbar < 0 && emptyHand && p.getInventory().getItem(source).isEmpty()) {
          hotbar = p.getInventory().selected;
          displaced = true;
        }
        // A full, verified SWAP preserves the displaced device (including NBT) in the source
        // main-inventory slot. Keep slot 0 for the user's terminal; never drop or overwrite.
        if (hotbar < 0 && workSlot && !emptyHand && source >= 9) {
          hotbar = 8;
          displaced = true;
        }
        if (hotbar < 0) return fail("没有空快捷栏或可安全交换的普通材料；请明确指定可交换的快捷栏");
        readySlot = hotbar;
        if (destination.equals("hotbar")) {
          if (source != hotbar) swaps.add(new int[] {InventoryLayout.menuSlot(source), hotbar});
        } else {
          if (source < 9) {
            hotbar = source;
            readySlot = source;
          } else swaps.add(new int[] {InventoryLayout.menuSlot(source), hotbar});
          swaps.add(new int[] {InventoryLayout.equipment(destination), hotbar});
        }
        detail = WorldSense.fingerprint(p.getInventory().getItem(source));
      }
    }
    if (swaps.isEmpty()) return success();
    int[] next = swaps.removeFirst();
    int from = next[0], to = 36 + next[1];
    var a = m.getSlot(from);
    var b = m.getSlot(to);
    var av = a.getItem();
    var bv = b.getItem();
    if (!m.getCarried().isEmpty()
        || !a.mayPickup(p)
        || !b.mayPickup(p)
        || (!bv.isEmpty() && !a.mayPlace(bv))
        || (!av.isEmpty() && !b.mayPlace(av))
        || bv.getCount() > a.getMaxStackSize(bv)
        || av.getCount() > b.getMaxStackSize(av)) return fail("目标槽位不接收物品、绑定装备或数量不适合完整交换");
    expected = InventoryProof.swapped(InventorySession.snapshot(), from, to);
    expectedCursor = ItemStack.EMPTY;
    mc().gameMode.handleInventoryMouseClick(0, from, next[1], ClickType.SWAP, p);
    session.refresh();
    phase = 1;
    return 0;
  }

  private int success() {
    finished = true;
    session.close();
    if (displaced) {
      detail += "；已将原快捷栏物品连同NBT完整交换到背包，未丢弃物品";
      WorkLedger.add("work_slot", "准备快捷栏 " + (readySlot + 1) + "：" + detail);
    }
    return 1;
  }

  private int fail(String reason) {
    error = reason;
    finished = true;
    session.close();
    return -1;
  }

  public void stop() {
    /* Keep the current UI and cursor intact on F9 or manual takeover. */
  }
}
