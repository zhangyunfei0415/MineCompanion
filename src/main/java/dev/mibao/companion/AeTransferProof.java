package dev.mibao.companion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** An AE proxy stack or an inventory prediction alone never proves a transfer. */
public final class AeTransferProof {
  public record DepositSlot(int slot, int count) {}

  public record SlotChoice(int slot, int count, String error) {}

  public static SlotChoice selectDepositSlot(
      List<DepositSlot> matching, int requestedSlot, int requestedCount) {
    if (requestedSlot >= 0) {
      if (requestedSlot > 35) return new SlotChoice(-1, 0, "inventory_slot为0..35");
      DepositSlot exact = null;
      if (matching != null) {
        for (var d : matching) {
          if (d.slot() == requestedSlot) {
            exact = d;
            break;
          }
        }
      }
      if (exact == null || exact.count() <= 0) {
        return new SlotChoice(-1, 0, "指定背包槽位 " + requestedSlot + " 中没有匹配该物品的整组");
      }
      if (requestedCount > 0 && exact.count() != requestedCount) {
        return new SlotChoice(
            -1,
            0,
            "存入须指定实际数量等于count的整组背包物品（槽位 "
                + requestedSlot
                + " 实际数量="
                + exact.count()
                + "），不自动拆分");
      }
      return new SlotChoice(exact.slot(), exact.count(), "");
    }
    if (matching == null || matching.isEmpty()) {
      return new SlotChoice(-1, 0, "玩家背包(0..35)中没有匹配该物品的整组槽位");
    }
    List<DepositSlot> ordered = new ArrayList<>(matching);
    // Prefer main inventory (9..35) before hotbar (0..8) so equipped tools stay undisturbed.
    ordered.sort(
        Comparator.comparingInt((DepositSlot d) -> d.slot() >= 9 ? 0 : 1)
            .thenComparingInt(DepositSlot::slot));
    if (requestedCount <= 0) {
      var first = ordered.get(0);
      return new SlotChoice(first.slot(), first.count(), "");
    }
    for (var d : ordered) {
      if (d.count() == requestedCount) return new SlotChoice(d.slot(), d.count(), "");
    }
    StringBuilder summary = new StringBuilder();
    for (int i = 0; i < Math.min(4, ordered.size()); i++) {
      if (i > 0) summary.append("、");
      summary.append("槽位").append(ordered.get(i).slot()).append("×").append(ordered.get(i).count());
    }
    return new SlotChoice(
        -1,
        0,
        "背包中没有数量恰好等于 "
            + requestedCount
            + " 的整组（匹配槽位："
            + summary
            + "）；AE整组存入不自动拆分，请传匹配的count或省略count整组存入");
  }

  public static boolean confirmed(
      boolean receipt,
      boolean deposit,
      int quantity,
      int beforePlayer,
      int actualPlayer,
      int serverPlayer,
      long beforeNetwork,
      long afterNetwork) {
    if (!receipt || quantity < 1 || quantity > 64 || beforeNetwork < 0 || afterNetwork < 0)
      return false;
    long delta = deposit ? -quantity : quantity;
    return (long) actualPlayer - beforePlayer == delta
        && serverPlayer == actualPlayer
        && afterNetwork - beforeNetwork == -delta;
  }
}
