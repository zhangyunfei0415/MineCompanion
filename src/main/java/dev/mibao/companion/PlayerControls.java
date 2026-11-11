package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraftforge.client.settings.KeyModifier;

public final class PlayerControls {
  private static final InputOwnership<KeyMapping> held = new InputOwnership<>();
  private static final Set<KeyMapping> clicked = new HashSet<>();
  private static boolean eating;
  public static long generation;

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public static void hold(KeyMapping mapping) {
    mapping.setDown(true);
    held.add(mapping);
    var codes = new HashSet<Integer>();
    for (var k : held)
      if (k.getKey().getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM)
        codes.add(k.getKey().getValue());
    VirtualInput.begin(codes);
  }

  public static void holdFood() {
    eating = true;
    hold(mc().options.keyUse);
  }

  public static void isolatePhysical() {
    KeyMapping.releaseAll();
    reapply();
  }

  public static void reapply() {
    for (var k : held) k.setDown(true);
  }

  public static void motionState(boolean forward, boolean jump) {
    var o = mc().options;
    for (var k : List.of(o.keyUp, o.keyJump, o.keySprint)) {
      boolean down = k == o.keyUp ? forward : k == o.keyJump && jump;
      k.setDown(down);
      if (down) held.add(k);
      else held.remove(k);
    }
    var codes = new HashSet<Integer>();
    for (var k : held)
      if (k.getKey().getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM)
        codes.add(k.getKey().getValue());
    if (held.isEmpty() && clicked.isEmpty()) VirtualInput.clear();
    else VirtualInput.begin(codes);
  }

  public static void guard() {
    if (held.isEmpty() && clicked.isEmpty()) return;
    VirtualInput.beat();
    if (!Agent.enabled
        || Agent.paused
        || mc().player == null
        || mc().screen != null
        || mc().isPaused()
        || (eating && !mc().player.isUsingItem())) release();
  }

  public static void release() {
    if (held.isEmpty() && clicked.isEmpty() && !eating && !VirtualInput.owned()) return;
    VirtualInput.clear();
    held.release(key -> key.setDown(false));
    for (var key : clicked) while (key.consumeClick()) {}
    clicked.clear();
    eating = false;
    generation++;
  }

  public static KeyMapping motion(String name) {
    return switch (name) {
      case "forward" -> mc().options.keyUp;
      case "back" -> mc().options.keyDown;
      case "left" -> mc().options.keyLeft;
      case "right" -> mc().options.keyRight;
      case "jump" -> mc().options.keyJump;
      case "sneak" -> mc().options.keyShift;
      case "sprint" -> mc().options.keySprint;
      default -> throw new IllegalArgumentException("未知动作");
    };
  }

  private static java.lang.reflect.Field clicks;

  public static String effectiveModifier(KeyMapping key) {
    String known = InputPolicy.knownModifier(key.getName());
    String forge =
        key.getKeyModifier() == KeyModifier.NONE
            ? ""
            : switch (key.getKeyModifier()) {
              case CONTROL -> "CTRL";
              case SHIFT -> "SHIFT";
              case ALT -> "ALT";
              default -> "";
            };
    return InputPolicy.modifiers(known + "+" + forge);
  }

  public static String keyAccess(String name, boolean approved) {
    return InputPolicy.access(
        Store.config.permission,
        Store.config.allowInput,
        ControlPolicy.approved(name, Store.config.allowedKeys),
        approved);
  }

  public static KeyMapping find(String name) {
    var keys =
        Arrays.stream(mc().options.keyMappings).filter(k -> k.getName().equals(name)).toList();
    if (keys.size() != 1) throw new IllegalArgumentException("[UNKNOWN_KEY] 按键注册名不唯一或不存在");
    return keys.get(0);
  }

  public static String blocked(KeyMapping key, String modifiers) {
    if (InputPolicy.protectedKey(key.getName()))
      return "PROTECTED_KEY:原版使用请调用use_hand，破坏请用break_block，实体攻击请用hunt_mobs；不能靠白名单绕过";
    if (key.isUnbound()) return "UNBOUND_KEY:按键未绑定";
    if (!key.getKeyConflictContext().isActive()) return "WRONG_CONTEXT:按键上下文不允许";
    if (!modifiers.isEmpty() && !VirtualInput.keyHookSeen) return "HOOK_UNAVAILABLE:组合键轮询桥尚未生效";
    if (key.getKey().getType() != com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM
        && !modifiers.isEmpty()) return "UNSUPPORTED_KEY_TYPE:组合键目前需要键盘绑定";
    for (var other : mc().options.keyMappings)
      if (other != key
          && !other.isUnbound()
          && other.getKey().equals(key.getKey())
          && InputPolicy.conflict(other.getName(), modifiers, effectiveModifier(other)))
        return "KEY_CONFLICT:与 " + other.getName() + " 的完整组合冲突";
    return "";
  }

  public static void pressMod(String name, String requested) {
    var key = find(name);
    String mods = InputPolicy.modifiers(effectiveModifier(key) + "+" + requested);
    String why = blocked(key, mods);
    if (!why.isBlank()) throw new IllegalArgumentException("[" + why + "]");
    if (!keyAccess(name, true).equals("allowed"))
      throw new IllegalArgumentException(
          "[PERMISSION_REQUIRED] 有限模式须允许通用输入并把该注册名加入白名单；FULL全部允许，ASK一次审批");
    var codes = new HashSet<>(InputPolicy.codes(mods));
    if (key.getKey().getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM)
      codes.add(key.getKey().getValue());
    hold(key);
    VirtualInput.begin(codes);
    try {
      if (clicks == null)
        clicks =
            net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(
                KeyMapping.class, "f_90818_");
      clicks.setInt(key, clicks.getInt(key) + 1);
      clicked.add(key);
    } catch (ReflectiveOperationException ex) {
      release();
      throw new IllegalArgumentException("[KEY_CLICK_UNAVAILABLE] 无法安全投递独立按键，不广播到其他绑定");
    }
  }

  public static JsonObject context() {
    var p = mc().player;
    JsonObject o = new JsonObject();
    String kind =
        mc().screen == null
            ? "world"
            : mc().screen instanceof InventoryScreen
                ? "player_inventory"
                : mc().screen instanceof AbstractContainerScreen ? "container" : "other_screen";
    o.addProperty("screen_kind", kind);
    o.addProperty("screen_class", mc().screen == null ? "" : mc().screen.getClass().getName());
    o.addProperty("cursor_occupied", !p.containerMenu.getCarried().isEmpty());
    o.addProperty("can_close_safely", InventorySession.canClose());
    o.addProperty("selected_hotbar", p.getInventory().selected);
    o.addProperty("inventory_full_receipt", InventoryEvidence.fullRevision);
    o.addProperty("held_controls", held.size());
    o.addProperty(
        "foreground_physical",
        org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(
                mc().getWindow().getWindow(), org.lwjgl.glfw.GLFW.GLFW_FOCUSED)
            == 1);
    o.addProperty("background_input", Store.config.backgroundInput);
    o.addProperty("key_poll_bridge", VirtualInput.keyHookSeen);
    o.addProperty("focus_bridge", VirtualInput.focusHookSeen);
    o.addProperty("panel_key", Companion.PANEL.getTranslatedKeyMessage().getString());
    o.addProperty("stop_key", Companion.STOP.getTranslatedKeyMessage().getString());
    o.addProperty(
        "guidance",
        "先观察界面。open_inventory打开背包；move_inventory准备快捷栏/装备；stow_cursor收好鼠标物品；eat_food核对进食。press_control有界移动；模组按键按FULL/ASK/LIMITED授权；限定模式需通用输入和白名单，实际效果另核验。");
    return o;
  }

  public static JsonArray bindings(String query) {
    JsonArray out = new JsonArray();
    for (var key : mc().options.keyMappings) {
      if (out.size() >= 256) break;
      if (!query.isBlank() && !key.getName().contains(query) && !key.getCategory().contains(query))
        continue;
      JsonObject o = new JsonObject();
      o.addProperty("name", key.getName());
      o.addProperty("label", net.minecraft.client.resources.language.I18n.get(key.getName()));
      o.addProperty("category", key.getCategory());
      o.addProperty("bound_key", key.getTranslatedKeyMessage().getString());
      o.addProperty("unbound", key.isUnbound());
      o.addProperty("context_active", key.getKeyConflictContext().isActive());
      String mods = effectiveModifier(key);
      String reason = blocked(key, mods);
      o.addProperty("approved", keyAccess(key.getName(), false).equals("allowed"));
      o.addProperty("access", keyAccess(key.getName(), false));
      o.addProperty("modifiers", mods);
      o.addProperty("supported", reason.isBlank());
      o.addProperty("blocked_reason", reason);
      o.addProperty("max_hold_ticks", 200);
      out.add(o);
    }
    return out;
  }
}
