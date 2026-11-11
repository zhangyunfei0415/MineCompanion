package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Bounded game-window callbacks. All held input is released by normal job cancellation. */
public final class GameInputJob extends Actions.Job {
  public static boolean injecting;
  private final JsonObject args;
  private final String kind;
  private final int duration, key, mods, button;
  private Screen screen;
  private boolean started, held;
  private boolean completing;
  private JsonObject navigationBefore = new JsonObject();
  private double x, y;
  private Set<Integer> keys = Set.of();

  public GameInputJob(JsonObject a, Consumer<String> done) {
    super(done);
    args = a.deepCopy();
    kind = Agent.str(a, "kind", "key");
    duration = GameInputPolicy.ticks(Agent.number(a, "ticks", 1));
    key = Agent.number(a, "key", 256);
    mods = GameInputPolicy.modifiers(Agent.number(a, "modifiers", 0));
    button = Agent.number(a, "button", 0);
    if (!Set.of("key", "text", "click", "drag", "scroll", "move").contains(kind)
        || button < 0
        || button > 2)
      throw new IllegalArgumentException(
          "[ARGUMENT_INVALID] kind为key/text/click/drag/scroll/move，鼠标button为0..2");
    if (kind.equals("key")) keys = GameInputPolicy.keys(key, mods);
  }

  private void event(Runnable r) {
    injecting = true;
    try {
      r.run();
    } finally {
      injecting = false;
    }
  }

  private void keyEvent(int k, int action) {
    var mc = Minecraft.getInstance();
    if (screen != null) {
      if (action == GLFW.GLFW_RELEASE) screen.keyReleased(k, 0, mods);
      else screen.keyPressed(k, 0, mods);
    } else mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), k, 0, action, mods);
  }

  private double coord(String name, int bound) {
    if (!args.has(name)) throw new IllegalArgumentException("[ARGUMENT_REQUIRED] GUI操作需要" + name);
    double v = args.has(name) ? args.get(name).getAsDouble() : 0;
    if (!Double.isFinite(v) || v < 0 || v >= bound)
      throw new IllegalArgumentException("[INPUT_BOUNDS] 坐标超出当前GUI");
    return v;
  }

  @Override
  void tick() {
    var mc = Minecraft.getInstance();
    if (!Store.config.fullGameControl) {
      finish("失败：[PERMISSION_REQUIRED] 完全游戏控制已关闭");
      return;
    }
    if (!Store.config.backgroundInput && !mc.isWindowActive()) {
      finish("输入已释放：后台输入关闭且当前窗口失去焦点。");
      return;
    }
    if (!started) {
      ScreenInput.validate(args.get("screen_view").getAsLong());
      screen = mc.screen;
      if (Panel.managing(screen)) {
        finish("失败：不允许Agent修改自己的设置/授权");
        return;
      }
      if (!Store.config.publicReply
          && !Store.config.allowChatTyping
          && (screen instanceof net.minecraft.client.gui.screens.ChatScreen
              || kind.equals("key")
                  && (key == 84
                      || key == 47
                      || mc.options.keyChat.matches(key, 0)
                      || mc.options.keyCommand.matches(key, 0)))) {
        finish("失败：[POLICY_DENIED] 公屏输入未授权");
        return;
      }
      if (screen instanceof net.minecraft.client.gui.screens.ChatScreen
          && kind.equals("key")
          && (key == 257 || key == 335)
          && !Store.config.allowChatSubmit) {
        finish("失败：[CHAT_SUBMIT_DENIED] 允许打字但未允许提交，文字保留供玩家检查");
        return;
      }
      if (!kind.equals("key") && !kind.equals("click") && screen == null) {
        finish("失败：拖动/滚动/文本操作需要当前GUI");
        return;
      }
      started = true;
      if (kind.equals("key")) {
        for (int k : keys)
          if (Companion.STOP.matches(k, 0)
              || Companion.PANEL.matches(k, 0)
              || Companion.QUICK_START.matches(k, 0)
              || Companion.WATCH.matches(k, 0)
              || Companion.COOP.matches(k, 0)
              || Companion.DIALOGUE.matches(k, 0)) {
            finish("失败：伙伴控制快捷键保留给玩家");
            return;
          }
        VirtualInput.begin(keys);
        held = true;
        event(() -> keys.forEach(k -> keyEvent(k, GLFW.GLFW_PRESS)));
      } else if (kind.equals("text")) {
        String text = Agent.str(args, "text", "");
        if (text.length() > 1024 || text.codePoints().anyMatch(Character::isISOControl)) {
          finish("失败：文本最多1024字符且不含控制符");
          return;
        }
        event(() -> text.chars().forEach(c -> screen.charTyped((char) c, mods)));
        finish("文本输入已发送到当前焦点；未按Enter，结果需观察。");
        return;
      } else if (screen == null) {
        if (button > 1) {
          finish("失败：世界鼠标仅支持左键攻击/右键使用");
          return;
        }
        var mapping = button == 0 ? mc.options.keyAttack : mc.options.keyUse;
        held = true;
        PlayerControls.hold(mapping);
        net.minecraft.client.KeyMapping.click(mapping.getKey());
      } else {
        x = coord("x", mc.getWindow().getGuiScaledWidth());
        y = coord("y", mc.getWindow().getGuiScaledHeight());
        navigationBefore = ScreenInput.navigationState();
        AgentPointer.begin(x, y);
        ScreenInput.position(x, y);
        if (kind.equals("move")) return;
        if (kind.equals("scroll")) {
          int amount = Agent.number(args, "amount", 1);
          if (amount == 0 || Math.abs((long) amount) > 10) {
            finish("失败：滚动amount为-10..10且非0");
            return;
          }
          // Ctrl+wheel is how FTB (and most modded GUIs) zoom; Screen.hasControlDown()
          // reads live GLFW key state, so the modifier must be held as a virtual key
          // for the duration of the scroll, not just passed as an event modifier.
          // Deliberately not setting `held`: stop() would then replay a
          // screen.mouseReleased(...) that a wheel event never had. VirtualInput.clear()
          // runs unconditionally in stop()'s finally block, so the held keys are still freed.
          var scrollMods = GameInputPolicy.modifierKeys(mods);
          if (!scrollMods.isEmpty()) VirtualInput.begin(scrollMods);
          event(() -> screen.mouseScrolled(x, y, amount));
          completing = true;
          return;
        }
        held = true;
        AgentPointer.button(button, true);
        event(() -> screen.mouseClicked(x, y, button));
      }
      return;
    }
    if (mc.screen != screen) {
      finish("输入已释放；界面已变化，请重新inspect_screen。");
      return;
    }
    VirtualInput.beat();
    if (screen != null && !kind.equals("key") && !kind.equals("text")) AgentPointer.beat();
    if (completing) {
      var after = ScreenInput.navigationState();
      finish(
          "输入已发送并释放；导航前="
              + navigationBefore
              + "；导航后="
              + after
              + "；可读导航状态变化="
              + !navigationBefore.equals(after)
              + "。该变化不是任务完成或物品到账证明，请重新观察。");
      return;
    }
    if (kind.equals("key") && screen != null && ticks > 0 && ticks % 4 == 0)
      event(() -> keyEvent(key, GLFW.GLFW_REPEAT));
    if (kind.equals("drag")) {
      double tx = coord("to_x", mc.getWindow().getGuiScaledWidth()),
          ty = coord("to_y", mc.getWindow().getGuiScaledHeight());
      double nx = x + (tx - x) / (duration - ticks), ny = y + (ty - y) / (duration - ticks);
      AgentPointer.move(nx, ny);
      ScreenInput.position(nx, ny);
      double dx = nx - x, dy = ny - y;
      event(() -> screen.mouseDragged(nx, ny, button, dx, dy));
      x = nx;
      y = ny;
    }
    if (++ticks >= duration) completing = true;
  }

  @Override
  void stop() {
    try {
      if (held)
        event(
            () -> {
              if (kind.equals("key")) {
                var list = new ArrayList<>(keys);
                Collections.reverse(list);
                for (int k : list) {
                  net.minecraft.client.KeyMapping.set(
                      com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(k), false);
                  try {
                    keyEvent(k, GLFW.GLFW_RELEASE);
                  } catch (RuntimeException ignored) {
                    // A mod callback may fail, but all remaining owned keys must still be released.
                  }
                }
              } else if (screen != null) screen.mouseReleased(x, y, button);
            });
    } catch (RuntimeException releaseFailure) {
      RuntimeStats.add("game_input_release_errors");
    } finally {
      held = false;
      for (int b = 0; b < 3; b++) AgentPointer.button(b, false);
      VirtualInput.clear();
      super.stop();
    }
  }
}
