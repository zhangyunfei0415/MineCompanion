package dev.mibao.companion;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/** Short leases follow current in-game bindings. Opening inventory verifies the actual screen. */
public final class ControlJob extends Actions.Job {
  private final String action, key, modifiers;
  private final int duration;
  private final InventorySession inventory = new InventorySession();
  private long lease, closeRevision;
  private boolean started;
  private net.minecraft.world.phys.Vec3 before;

  public ControlJob(String action, String key, int duration, Consumer<String> done) {
    super(done);
    this.action = action;
    this.key = key;
    this.duration = InputPolicy.ticks(duration);
    this.modifiers = "";
  }

  public ControlJob(
      String action, String key, String modifiers, int duration, Consumer<String> done) {
    super(done);
    this.action = action;
    this.key = key;
    this.modifiers = InputPolicy.modifiers(modifiers);
    this.duration = InputPolicy.ticks(duration);
  }

  @Override
  void tick() {
    var mc = Minecraft.getInstance();
    if (action.equals("open_inventory")) {
      Actions.status = "打开背包 · 等待真实界面与服务器快照";
      int r = inventory.tick();
      if (r > 0) finish("成功：玩家背包已打开，已取得完整服务器槽位快照。");
      else if (r < 0) finish("失败：" + inventory.error);
      return;
    }
    if (action.equals("close_screen")) {
      if (mc.screen == null) {
        finish("界面已经关闭。");
        return;
      }
      if (SignEditor.active()) {
        mc.screen.onClose();
        GuiContext.invalidate();
        finish("成功：告示牌编辑已正常关闭；文字保存另核实");
        return;
      }
      if (ScreenInput.ftb() || Store.config.fullGameControl) {
        if (!mc.player.containerMenu.getCarried().isEmpty()) {
          finish("失败：鼠标仍持有物品，请先收好再退出任务书。");
          return;
        }
        mc.screen.onClose();
        GuiContext.invalidate();
        finish(
            mc.screen == null ? "成功：任务书已关闭，回到世界。" : "已调用任务书正常返回；当前仍有界面，请重新input_capabilities核对。");
        return;
      }
      if (!(mc.screen
          instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) {
        finish("失败：当前不是背包/容器界面，请人工返回游戏。");
        return;
      }
      // Closing sends the normal close packet, not a slot mutation or a production verdict.
      // A custom menu's absent full resync must not trap an empty cursor in the UI.
      if (!MenuRefresh.supported()) {
        if (!InventorySession.canClose()) {
          finish("失败：鼠标或合成栏还有物品，请先收好，避免关闭时丢物。");
          return;
        }
        mc.player.closeContainer();
        GuiContext.invalidate();
        finish(mc.screen == null ? "成功：已正常关闭自定义物品界面；未核实的生产/库存结果仍保持未核实。" : "失败：界面未关闭。");
        return;
      }
      if (!started) {
        closeRevision = MenuContext.revision();
        started = true;
        if (MenuRefresh.request()) return;
      }
      if (!MenuContext.synchronizedNow()
          || (MenuRefresh.supported() && MenuContext.revision() <= closeRevision)) {
        if (++ticks > 100) finish("失败：没有一致的服务器菜单快照，保持界面；请核对鼠标物品。");
        return;
      }
      if (!InventorySession.canClose()) {
        finish("失败：鼠标或合成栏还有物品，请先收好，避免关闭时丢物。");
        return;
      }
      mc.player.closeContainer();
      GuiContext.invalidate();
      finish(mc.screen == null ? "成功：已关闭物品界面。" : "失败：界面未关闭。");
      return;
    }
    if (started && mc.screen != null) {
      finish("输入已释放；已观察到界面打开：" + mc.screen.getTitle().getString() + "。功能结果待核验，不自动重按切换键。");
      return;
    }
    if (started && lease != PlayerControls.generation) {
      finish("部分完成：按键控制已释放（暂停或自卫），未继续按住；请重新观察。");
      return;
    }
    if (!started) {
      if (mc.screen != null
          || mc.player.containerMenu != mc.player.inventoryMenu
          || (!Store.config.backgroundInput && !mc.isWindowActive())) {
        finish("失败：[WRONG_CONTEXT] 请回到游戏世界；后台输入关闭时才要求前台焦点。");
        return;
      }
      before = mc.player.position();
      lease = PlayerControls.generation;
      started = true;
      if (action.equals("mod_key")) PlayerControls.pressMod(key, modifiers);
      else {
        PlayerControls.hold(PlayerControls.motion(action));
        if (action.equals("sprint")) PlayerControls.hold(mc.options.keyUp);
      }
      Actions.status = "按键操作 · " + action + "（最多" + duration + "刻）";
      return;
    }
    if (mc.screen != null || (!Store.config.backgroundInput && !mc.isWindowActive())) {
      finish("操作按键已释放；界面或焦点发生变化，效果待观察。");
      return;
    }
    if (++ticks >= duration)
      finish(
          "输入已发送并释放，功能结果未核验："
              + action
              + "；实际位移 "
              + String.format(
                  java.util.Locale.ROOT, "%.2f", mc.player.position().distanceTo(before))
              + " 格。动作效果仍需结合现场核验。");
  }
}
