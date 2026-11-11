package dev.mibao.companion;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/** Explicit one-item or whole-stack normal drop. No outside-slot clicks, no implicit cleanup. */
public final class DropInventoryJob extends Actions.Job {
  private final String item;
  private final boolean stack;
  private final InventoryMove prepare;
  private final InventorySession proof = new InventorySession(false);
  private int stage;
  private int settle;
  private net.minecraft.world.item.ItemStack expected;

  public DropInventoryJob(String item, boolean stack, Consumer<String> done) {
    super(done);
    this.item = item;
    this.stack = stack;
    prepare = InventoryMove.ensure(s -> Actions.match(s, item));
  }

  @Override
  void tick() {
    var mc = Minecraft.getInstance();
    if (stage == 0) {
      if (mc.screen != null
          || mc.player.containerMenu != mc.player.inventoryMenu
          || !mc.player.containerMenu.getCarried().isEmpty()) {
        finish("失败：丢弃前请退出菜单并收好鼠标物品");
        return;
      }
      if (mc.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.SURVIVAL
          && mc.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.ADVENTURE
          && mc.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.CREATIVE) {
        finish("失败：[MODE_UNSUPPORTED] 旁观模式不支持丢弃，未发送丢弃");
        return;
      }
      stage = 1;
    }
    if (stage == 1) {
      int r = prepare.tick();
      if (r < 0) {
        finish("失败：" + prepare.error);
        return;
      }
      if (r == 0) return;
      mc.player.getInventory().selected = prepare.readySlot;
      expected = mc.player.getMainHandItem().copy();
      expected.shrink(stack ? expected.getCount() : 1);
      PlayerEvidence.begin();
      mc.player.drop(stack);
      stage = 2;
      return;
    }
    if (++settle < 8) return;
    int r = proof.tick();
    if (r < 0) {
      finish("部分完成：丢弃已发送，" + proof.error);
      return;
    }
    if (r == 0) return;
    var actual = mc.player.getInventory().getItem(prepare.readySlot);
    var evidence = new com.google.gson.JsonObject();
    evidence.addProperty("whole_stack", stack);
    evidence.addProperty("expected_remaining", expected.getCount());
    evidence.addProperty("actual_remaining", actual.getCount());
    boolean verified = net.minecraft.world.item.ItemStack.matches(expected, actual);
    finish(new ActionResult(verified ? ActionResult.Status.verified : ActionResult.Status.unverified,
        verified ? ActionResult.Code.NONE : ActionResult.Code.USE_UNCONFIRMED,
        verified ? "指定槽位丢弃后的同步背包已核对" : "已发送一次丢弃，最终槽位不符；请检查重新拾取或槽位变化", evidence).json());
  }

  @Override
  void stop() {
    prepare.stop();
    proof.close();
    super.stop();
  }
}
