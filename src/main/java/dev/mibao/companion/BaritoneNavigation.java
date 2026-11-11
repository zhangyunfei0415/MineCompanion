package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** One owner of the optional pathfinder and its global settings during a navigation. */
public final class BaritoneNavigation {
  private static BaritoneNavigation owner;
  private static String diagnostic = "idle";
  private final Map<Object, Object> previous = new LinkedHashMap<>();
  private Object instance;

  public boolean start(BlockPos target) {
    if (owner != null && owner != this) {
      diagnostic = "another_navigation_active";
      return false;
    }
    try {
      Class<?> api = Class.forName("baritone.api.BaritoneAPI");
      Object provider = api.getMethod("getProvider").invoke(null);
      instance = Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
      Object settings = api.getMethod("getSettings").invoke(null);
      boolean full = "FULL".equals(Store.config.permission) || Store.config.fullGameControl;
      setting(settings, "allowBreak", Store.config.navigationBreak && (full || Store.config.allowMine));
      setting(settings, "allowPlace", Store.config.navigationPlace && (full || Store.config.allowPlace));
      setting(settings, "allowBreakAnyway", java.util.List.of());
      setting(settings, "allowInventory", Store.config.navigationPlace && (full || Store.config.allowPlace));
      setting(settings, "allowParkour", false);
      Object goal = Class.forName("baritone.api.pathing.goals.GoalNear")
          .getConstructor(BlockPos.class, int.class).newInstance(target, 1);
      Object process = Class.forName("baritone.api.IBaritone").getMethod("getCustomGoalProcess").invoke(instance);
      owner = this;
      Class.forName("baritone.api.process.ICustomGoalProcess").getMethod("setGoalAndPath", Class.forName("baritone.api.pathing.goals.Goal"))
          .invoke(process, goal);
      diagnostic = "running";
      return true;
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      diagnostic = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
      stop();
      return false;
    }
  }

  private void setting(Object settings, String name, Object value) throws ReflectiveOperationException {
    Object setting = settings.getClass().getField(name).get(settings);
    Field field = setting.getClass().getField("value");
    previous.put(setting, field.get(setting));
    field.set(setting, value);
  }

  public boolean active() {
    try {
      Object behavior = Class.forName("baritone.api.IBaritone").getMethod("getPathingBehavior").invoke(instance);
      Class<?> behaviorType = Class.forName("baritone.api.behavior.IPathingBehavior");
      java.util.Optional<?> calculation = (java.util.Optional<?>)
          behaviorType.getMethod("getInProgress").invoke(behavior);
      return (boolean) behaviorType.getMethod("isPathing").invoke(behavior)
          || calculation.isPresent();
    } catch (ReflectiveOperationException | RuntimeException e) {
      return false;
    }
  }

  public void stop() {
    if (instance != null) {
      try {
        Object behavior = Class.forName("baritone.api.IBaritone").getMethod("getPathingBehavior").invoke(instance);
        Class.forName("baritone.api.behavior.IPathingBehavior").getMethod("cancelEverything").invoke(behavior);
      } catch (ReflectiveOperationException | RuntimeException e) {
        diagnostic = "cancel: " + e.getClass().getSimpleName();
      }
    }
    previous.forEach((setting, value) -> {
      try { setting.getClass().getField("value").set(setting, value); }
      catch (ReflectiveOperationException e) { diagnostic = "restore: " + e.getClass().getSimpleName(); }
    });
    previous.clear();
    instance = null;
    if (owner == this) owner = null;
  }

  public static JsonObject status() {
    JsonObject result = new JsonObject();
    boolean available;
    try { Class.forName("baritone.api.BaritoneAPI"); available = true; }
    catch (ClassNotFoundException | LinkageError e) { available = false; }
    result.addProperty("available", available);
    result.addProperty("backend", owner == null ? "idle" : "baritone");
    result.addProperty("detail", diagnostic);
    result.addProperty("allow_break", Store.config.navigationBreak);
    result.addProperty("allow_place", Store.config.navigationPlace);
    return result;
  }
}
