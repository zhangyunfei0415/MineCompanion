package dev.mibao.companion;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

/**
 * One owned menu session, paced exact clicks and authoritative verification of both inventories.
 */
final class ContainerJob extends Actions.Job {
  private enum Stage {
    WALK,
    OPEN,
    SETTLE,
    MOVE,
    CLICKS,
    AFTER_CLICK,
    CONFIRM,
    CLOSE,
    REOPEN,
    VERIFY
  }

  private record Click(int slot, int button, boolean returning) {}

  private final Navigator nav = new Navigator();
  private final BlockPos pos;
  private final String filter;
  private final int wanted;
  private final boolean deposit;
  private Stage stage = Stage.WALK;
  private boolean started;
  private int delay, menuId = -1, firstMenu = -1, planned, batchAmount, batchBefore;
  private String block, batchVariant;
  private final Deque<Click> clicks = new ArrayDeque<>();
  private Map<String, Integer> beforePlayer, beforeBox;
  private final Map<String, Integer> plan = new HashMap<>();
  private AbstractContainerMenu ownedMenu;
  private int batchSourceCount;
  private long firstRevision;
  private final boolean fluent = ExecutionStyle.fluent(RuntimeStrategy.executionStyle());
  private HandPreparation hand;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  ContainerJob(BlockPos pos, String filter, int count, boolean deposit, Consumer<String> done) {
    super(done);
    this.pos = pos;
    this.filter = filter;
    wanted = count;
    this.deposit = deposit;
  }

  @Override
  void tick() {
    if (++ticks > 6000) {
      finish("失败：容器操作超时；可能已有部分转移，请核对现场。");
      return;
    }
    Actions.status =
        (deposit ? "存入" : "取出")
            + filter
            + " "
            + planned
            + "/"
            + wanted
            + " · "
            + switch (stage) {
              case WALK -> "寻找交互站位";
              case OPEN, REOPEN -> "打开目标";
              case SETTLE -> "读取界面";
              case CONFIRM -> "确认槽位更新";
              case CLOSE, VERIFY -> "重开核对";
              default -> "转移物品";
            };
    if (stage == Stage.WALK) {
      if (!started) {
        started = true;
        block = BuiltInRegistries.BLOCK.getKey(mc().level.getBlockState(pos).getBlock()).toString();
        if (Reach.hit(pos) == null && !nav.startForInteraction(pos)) {
          finish("失败：[TRANSFER_NOT_STARTED] " + nav.error());
          return;
        }
      }
      if (Reach.hit(pos) != null) {
        stage = Stage.OPEN;
        delay = 0;
        return;
      }
      int r = nav.tick();
      if (r < 0) {
        finish("失败：[TRANSFER_NOT_STARTED] " + nav.error());
        return;
      }
      if (r > 0) {
        stage = Stage.OPEN;
        delay = 0;
      }
      return;
    }
    if (!mc().level.hasChunkAt(pos)
        || !block.equals(
            BuiltInRegistries.BLOCK.getKey(mc().level.getBlockState(pos).getBlock()).toString())) {
      finish("失败：目标方块已改变或区块卸载；停止操作。");
      return;
    }
    if (stage == Stage.OPEN || stage == Stage.REOPEN) {
      if (mc().player.containerMenu != mc().player.inventoryMenu) {
        if (!MenuContext.matches(pos)) {
          finish("失败：打开的菜单无法关联到目标方块。");
          return;
        }
        menuId = mc().player.containerMenu.containerId;
        ownedMenu = mc().player.containerMenu;
        if (stage == Stage.REOPEN && menuId == firstMenu) {
          finish("失败：未取得新菜单会话，不能验证库存。");
          return;
        }
        stage = stage == Stage.OPEN ? Stage.SETTLE : Stage.VERIFY;
        delay = 0;
        return;
      }
      if (hand == null) hand = new HandPreparation(pos);
      int ready = hand.tick();
      if (ready < 0) {
        finish("失败：打开前准备空手未完成：" + hand.error);
        return;
      }
      if (ready == 0) return;
      hand = null;
      if (delay++ % 20 == 0) Actions.open(pos);
      if (delay > 100) finish("失败：目标未打开；可能被上盖挡住、锁定、距离变化或不支持该容器。");
      return;
    }
    if (stage == Stage.CLOSE) {
      if (++delay >= 3) {
        stage = Stage.REOPEN;
        delay = 0;
      }
      return;
    }
    if (!MenuContext.matches(pos) || mc().player.containerMenu.containerId != menuId) {
      finish("失败：操作期间菜单已切换或关闭，旧槽位编号作废。");
      return;
    }
    var menu = mc().player.containerMenu;
    if (stage == Stage.CONFIRM) {
      if (MenuContext.revision() > firstRevision
          && MenuContext.synchronizedNow()
          && menu.getCarried().isEmpty()) {
        var proof =
            TransferProof.verify(
                beforePlayer, beforeBox, counts(true), counts(false), plan, deposit);
        if (proof.verified()) {
          complete(proof, false);
          return;
        }
      }
      if (++delay == 5) MenuRefresh.request();
      if (delay >= 25) {
        mc().player.closeContainer();
        stage = Stage.CLOSE;
        delay = 0;
      }
      return;
    }
    if (stage == Stage.SETTLE || stage == Stage.VERIFY) {
      if (++delay < 6) return;
      if (!MenuContext.synchronizedNow()) {
        if (delay > 100) finish("失败：等待服务器完整槽位同步超时（" + MenuContext.syncStatus() + "）；不确认库存或转移成功。");
        return;
      }
      if (!menu.getCarried().isEmpty()) {
        finish("失败：鼠标上仍有物品，请先放回再继续。");
        return;
      }
      WorldSense.rememberInventory(pos);
      if (stage == Stage.VERIFY) {
        var proof =
            TransferProof.verify(
                beforePlayer, beforeBox, counts(true), counts(false), plan, deposit);
        complete(proof, true);
        return;
      }
      if ((!MenuContext.kind().equals("storage") || !Rules.standardStorage(block))
          && !ModMenuReceipts.supported()) {
        finish("失败：界面已观察并记忆，但该方块/菜单尚未适配自动取放；请使用 observe 检查槽位，或反馈该机器/仓储界面。");
        return;
      }
      firstMenu = menuId;
      firstRevision = MenuContext.revision();
      beforePlayer = counts(true);
      beforeBox = counts(false);
      stage = Stage.MOVE;
      return;
    }
    if (stage == Stage.CLICKS) {
      Click next = clicks.pollFirst();
      if (next != null) {
        var cursor = menu.getCarried();
        if (next.returning && cursor.isEmpty()) {
        } else {
          if (next.slot == sourceSlot && !next.returning && cursor.isEmpty()) {
            var source = menu.slots.get(sourceSlot).getItem();
            if (source.isEmpty()
                || source.getCount() != batchSourceCount
                || !WorldSense.fingerprint(source).equals(batchVariant)) {
              finish("失败：源槽位在点击前已改变，未继续操作。");
              return;
            }
          }
          if (!next.returning && clicks.size() > 0 && cursor.isEmpty() && planned > 0) {
            // The first click of a batch takes from its source; later clicks require that cursor
            // item.
            if (next.slot != sourceSlot) {
              beginVerify();
              return;
            }
          }
          if (!cursor.isEmpty() && !WorldSense.fingerprint(cursor).equals(batchVariant)) {
            finish("失败：鼠标物品与本批次不符，已停止并关闭界面归还物品。");
            return;
          }
          if (!cursor.isEmpty() && !next.returning && !menu.slots.get(next.slot).mayPlace(cursor)) {
            beginVerify();
            return;
          }
          if (!cursor.isEmpty() && next.slot != sourceSlot) {
            var dest = menu.slots.get(next.slot);
            var stack = dest.getItem();
            int needed = next.button == 0 ? cursor.getCount() : 1;
            if (!stack.isEmpty() && !ItemStack.isSameItemSameTags(stack, cursor)
                || Math.min(dest.getMaxStackSize(cursor), cursor.getMaxStackSize())
                        - stack.getCount()
                    < needed) {
              beginVerify();
              return;
            }
          }
          Actions.click(next.slot, next.button, ClickType.PICKUP);
        }
      }
      if (clicks.isEmpty()) {
        stage = Stage.AFTER_CLICK;
        delay = 0;
      }
      return;
    }
    if (stage == Stage.AFTER_CLICK) {
      if (++delay < 5) return;
      int delta = counts(true).values().stream().mapToInt(Integer::intValue).sum() - batchBefore;
      if (delta != (deposit ? -batchAmount : batchAmount)) {
        beginVerify();
        return;
      }
      stage = Stage.MOVE;
      return;
    }
    if (planned >= wanted) {
      beginVerify();
      return;
    }
    if (!menu.getCarried().isEmpty()) {
      finish("失败：鼠标物品未归位，停止自动取放。");
      return;
    }
    Slot from = null;
    int fromId = -1, toId = -1, amount = 0;
    for (int i = 0; i < menu.slots.size() && from == null; i++) {
      Slot s = menu.slots.get(i);
      boolean player = s.container == mc().player.getInventory();
      ItemStack stack = s.getItem();
      if (player != deposit || !s.mayPickup(mc().player) || !Actions.match(stack, filter)) continue;
      if (player && s.getContainerSlot() >= 36) continue;
      if (deposit
          && (filter.isBlank() || filter.equals("*"))
          && (stack.hasTag() || stack.isDamageableItem())) continue;
      for (int j = 0; j < menu.slots.size(); j++) {
        Slot dest = menu.slots.get(j);
        boolean destPlayer = dest.container == mc().player.getInventory();
        if (destPlayer == deposit
            || destPlayer && dest.getContainerSlot() >= 36
            || !dest.mayPlace(stack)) continue;
        ItemStack other = dest.getItem();
        if (!other.isEmpty() && !ItemStack.isSameItemSameTags(stack, other)) continue;
        int space =
            Math.min(dest.getMaxStackSize(stack), stack.getMaxStackSize()) - other.getCount();
        if (space <= 0) continue;
        from = s;
        fromId = i;
        toId = j;
        amount = Math.min(Math.min(space, stack.getCount()), wanted - planned);
        break;
      }
    }
    if (from == null) {
      boolean available =
          menu.slots.stream()
              .anyMatch(
                  s ->
                      (s.container == mc().player.getInventory()) == deposit
                          && s.mayPickup(mc().player)
                          && Actions.match(s.getItem(), filter));
      shortage = available ? (deposit ? "目标容器已满或没有允许放入的兼容槽位" : "背包已满或没有兼容槽位") : "源库存缺货：没有更多匹配物品";
      if (planned == 0) {
        Actions.lastTransfer =
            new TransferReceipt(
                WorldMemory.key(dimension, pos.getX(), pos.getY(), pos.getZ()),
                filter,
                deposit,
                0,
                counts(false).values().stream().mapToInt(Integer::intValue).sum(),
                epoch);
        Store.save();
        mc().player.closeContainer();
        finish("未转移：" + shortage + "；已确认本步转移0个，已更新容器记录。");
      } else beginVerify();
      return;
    }
    batchBefore = counts(true).values().stream().mapToInt(Integer::intValue).sum();
    batchAmount = amount;
    batchVariant = WorldSense.fingerprint(from.getItem());
    batchSourceCount = from.getItem().getCount();
    sourceSlot = fromId;
    plan.merge(batchVariant, amount, Integer::sum);
    planned += amount;
    clicks.add(new Click(fromId, 0, false));
    if (amount == from.getItem().getCount()) clicks.add(new Click(toId, 0, false));
    else for (int i = 0; i < amount; i++) clicks.add(new Click(toId, 1, false));
    clicks.add(new Click(fromId, 0, true));
    stage = Stage.CLICKS;
  }

  private int sourceSlot = -1;
  private String shortage = "源物品不足或目标空间不足";

  private Map<String, Integer> counts(boolean player) {
    Map<String, Integer> result = new HashMap<>();
    for (var slot : mc().player.containerMenu.slots) {
      if ((slot.container == mc().player.getInventory()) != player
          || player && slot.getContainerSlot() >= 36) continue;
      var s = slot.getItem();
      if (Actions.match(s, filter))
        result.merge(WorldSense.fingerprint(s), s.getCount(), Integer::sum);
    }
    return result;
  }

  private void returnCursor() {
    var menu = mc().player.containerMenu;
    if (menu != ownedMenu
        || sourceSlot < 0
        || sourceSlot >= menu.slots.size()
        || menu.getCarried().isEmpty()) return;
    var cursor = menu.getCarried();
    var source = menu.slots.get(sourceSlot);
    var in = source.getItem();
    if (WorldSense.fingerprint(cursor).equals(batchVariant)
        && source.mayPlace(cursor)
        && (in.isEmpty() || ItemStack.isSameItemSameTags(in, cursor))
        && Math.min(source.getMaxStackSize(cursor), cursor.getMaxStackSize()) - in.getCount()
            >= cursor.getCount()) Actions.click(sourceSlot, 0, ClickType.PICKUP);
  }

  private void beginVerify() {
    clicks.clear();
    returnCursor();
    delay = 0;
    if (fluent) {
      stage = Stage.CONFIRM;
      return;
    }
    mc().player.closeContainer();
    stage = Stage.CLOSE;
  }

  private void complete(TransferProof.Result proof, boolean reopened) {
    if (proof.verified())
      Actions.lastTransfer =
          new TransferReceipt(
              WorldMemory.key(dimension, pos.getX(), pos.getY(), pos.getZ()),
              filter,
              deposit,
              proof.moved(),
              counts(false).values().stream().mapToInt(Integer::intValue).sum(),
              epoch);
    if (proof.verified()) WorldSense.rememberInventory(pos);
    mc().player.closeContainer();
    Store.save();
    finish(
        proof.verified()
            ? (proof.moved() >= wanted ? "成功" : "部分完成")
                + "："
                + (reopened ? "已重新打开目标并取得服务器同步" : "流畅模式已用服务器槽位更新核对双方库存，无需重复开箱")
                + "，核实"
                + (deposit ? "存入" : "取出")
                + proof.moved()
                + "/"
                + wanted
                + " 个 "
                + filter
                + (proof.moved() < wanted ? "；" + shortage : "")
            : "失败：重新打开后的双方库存与计划不符；可能有部分转移或其他玩家同时操作，请核对。不会继续重试点击。");
  }

  @Override
  void stop() {
    if (hand != null) hand.stop();
    clicks.clear();
    nav.stop();
    // Closing returns a carried stack through vanilla's server handling. Never send a new click on
    // F9.
    if (mc().player != null && mc().player.containerMenu == ownedMenu) mc().player.closeContainer();
    super.stop();
  }

  @Override
  String interrupted() {
    return planned > 0 ? "容器任务已中断：可能已有部分物品转移；没有完成重开核对，请检查双方实际数量。" : "";
  }
}
