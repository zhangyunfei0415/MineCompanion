package dev.mibao.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.GameType;

/**
 * Uses the same AE client menu actions as a player. Each issued step is checked, never replayed.
 */
public final class AeTransferJob extends Actions.Job {
  private final String item;
  private final int requestedCountArg, depositSlotArg;
  private final boolean deposit;
  private Object menu;
  private String fingerprint;
  private long stored;
  private int requested, resolvedSlot, inventory, completed, delay;
  private boolean sent, stowing, networkDrift;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public AeTransferJob(String item, int count, int slot, boolean deposit, Consumer<String> done) {
    super(done);
    this.item = item;
    requestedCountArg = count;
    depositSlotArg = slot;
    this.deposit = deposit;
    this.requested = count;
    if (!deposit && (count < 1 || count > 64)) throw new IllegalArgumentException("count为1..64");
    if (deposit && count != -1 && (count < 1 || count > 64))
      throw new IllegalArgumentException("count为1..64");
  }

  private int actual() {
    return PlayerEvidence.total(PlayerEvidence.actual(fingerprint));
  }

  @Override
  void tick() {
    try {
      if (mc().gameMode.getPlayerMode() != GameType.SURVIVAL)
        throw new IllegalArgumentException("AE物资验收先使用生存模式，避免创造复制混入");
      if (menu == null) {
        if (!AeTerminalBridge.available() || mc().screen == null)
          throw new IllegalArgumentException("先打开AE终端");
        menu = mc().player.containerMenu;
        if (!mc().player.containerMenu.getCarried().isEmpty())
          throw new IllegalArgumentException("先收好鼠标物品");
        if (deposit) {
          List<AeTransferProof.DepositSlot> matching = new ArrayList<>();
          for (int i = 0; i < 36; i++) {
            var st = mc().player.getInventory().getItem(i);
            if (!st.isEmpty() && Actions.match(st, item)) {
              matching.add(new AeTransferProof.DepositSlot(i, st.getCount()));
            }
          }
          var choice = AeTransferProof.selectDepositSlot(matching, depositSlotArg, requestedCountArg);
          if (!choice.error().isEmpty()) throw new IllegalArgumentException(choice.error());
          resolvedSlot = choice.slot();
          requested = choice.count();
          var s = mc().player.getInventory().getItem(resolvedSlot);
          fingerprint = WorldSense.fingerprint(s);
        } else {
          var e = AeTerminalBridge.find(item);
          fingerprint = WorldSense.fingerprint(e.stack());
          if (e.stored() < requested) throw new IllegalArgumentException("AE库存不足");
        }
        inventory = actual();
        stored = AeTerminalBridge.amount(fingerprint);
        PlayerEvidence.begin();
        if (!deposit) {
          int capacity = 0,
              stackLimit = AeTerminalBridge.find(fingerprint).stack().getMaxStackSize();
          for (int i = 0; i < 36; i++) {
            var s = mc().player.getInventory().getItem(i);
            if (s.isEmpty()) capacity += stackLimit;
            else if (Actions.match(s, fingerprint)) capacity += s.getMaxStackSize() - s.getCount();
          }
          if (capacity < requested) throw new IllegalArgumentException("背包可用容量不足");
        }
      }
      if (menu != mc().player.containerMenu) throw new IllegalArgumentException("AE菜单已变化，已发动作不重放");
      if (!sent) {
        if (deposit) {
          int index = -1;
          for (int i = 0; i < mc().player.containerMenu.slots.size(); i++) {
            var s = mc().player.containerMenu.getSlot(i);
            if (s.container == mc().player.getInventory() && s.getContainerSlot() == resolvedSlot) {
              index = i;
              break;
            }
          }
          if (index < 0) throw new IllegalArgumentException("当前菜单没有该玩家槽");
          mc().gameMode
              .handleInventoryMouseClick(
                  mc().player.containerMenu.containerId,
                  index,
                  0,
                  ClickType.QUICK_MOVE,
                  mc().player);
        } else
          AeTerminalBridge.interaction(
              AeTerminalBridge.find(fingerprint).serial(), "PICKUP_SINGLE");
        sent = true;
        delay = 0;
        if (deposit) MenuRefresh.request();
        return;
      }
      if (!deposit
          && !stowing
          && PlayerEvidence.cursorConfirmed()
          && Actions.match(mc().player.containerMenu.getCarried(), fingerprint)
          && mc().player.containerMenu.getCarried().getCount() == 1) {
        stowing = true;
        delay = 0;
        Actions.job =
            new CursorStowJob(
                result -> {
                  Actions.job = this;
                  if (Rules.actionFailed(result)) finish("部分完成：AE已取到鼠标；" + result);
                });
        return;
      }
      if (++delay > 100) {
        String report =
            "部分完成：AE已确认 "
                + completed
                + " / "
                + requested
                + "；当前动作已发送但双方数量未共同确认，停止且不重放，请ae_inventory和observe核对。";
        var carried = mc().player.containerMenu.getCarried();
        if (!deposit && !stowing && !carried.isEmpty() && Actions.match(carried, fingerprint)) {
          // The cursor holds what this job fetched; stow it before ending instead of leaving it.
          stowing = true;
          Actions.job =
              new CursorStowJob(
                  result -> {
                    Actions.job = this;
                    finish(report + "已尝试收回本次取到鼠标的物品：" + result);
                  });
          return;
        }
        finish(report);
        return;
      }
      if (delay % 4 != 0) return;
      int step = deposit ? requested : 1;
      int expected = inventory + (deposit ? -step : step);
      long expectedNetwork = stored + (deposit ? step : -step);
      int actualNow = actual();
      int serverNow = PlayerEvidence.total(PlayerEvidence.confirmed(fingerprint));
      long networkNow = AeTerminalBridge.amount(fingerprint);
      boolean exact =
          AeTransferProof.confirmed(
              PlayerEvidence.revision > 0,
              deposit,
              step,
              inventory,
              actualNow,
              serverNow,
              stored,
              networkNow);
      // Other automation may move the same item meanwhile: after the stow settles, a server-confirmed
      // +1 in the player inventory proves the fetch; the network count is only a note then.
      boolean playerOnly =
          !exact
              && !deposit
              && stowing
              && delay >= 20
              && mc().player.containerMenu.getCarried().isEmpty()
              && PlayerEvidence.revision > 0
              && actualNow - inventory == step
              && serverNow == actualNow;
      if (exact || playerOnly) {
        completed += step;
        if (playerOnly) networkDrift = true;
        if (completed == requested) {
          finish(
              "成功：AE"
                  + (deposit ? "存入" : "取出")
                  + " "
                  + completed
                  + (deposit ? "（来自背包槽位 " + resolvedSlot + "）" : "")
                  + (networkDrift
                      ? "；期间该物品网络数量另有变动（当前 "
                          + networkNow
                          + "），取出以服务器背包回执核对，网络数量仅作附注。未使用创造复制。"
                      : "；客户端网络数量与服务器背包变化共同核对。未使用创造复制。"));
          return;
        }
        inventory = expected;
        stored = playerOnly ? networkNow : expectedNetwork;
        PlayerEvidence.begin();
        sent = false;
        stowing = false;
        return;
      }
    } catch (RuntimeException | LinkageError e) {
      finish((sent ? "部分完成：动作可能已生效；" : "失败：") + e.getMessage() + "；没有自动重试。");
    }
  }

  @Override
  String interrupted() {
    return "AE操作已停止；已发出步骤可能生效，重新观察双方数量，不重放旧请求。";
  }
}
