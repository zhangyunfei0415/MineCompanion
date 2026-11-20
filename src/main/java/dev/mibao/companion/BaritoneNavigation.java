package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** One owner of the optional pathfinder and its global settings during a navigation. */
public final class BaritoneNavigation {
  private static final class Api {
    final Method getProvider;
    final Method getPrimaryBaritone;
    final Method getSettings;
    final Constructor<?> newGoalNear;
    final Method getCustomGoalProcess;
    final Method setGoalAndPath;
    final Method getMineProcess;
    final Method mineByName;
    final Method isActive;
    final Method getPathingBehavior;
    final Method getInProgress;
    final Method isPathing;
    final Method cancelEverything;

    Api() throws ReflectiveOperationException {
      Class<?> baritoneApi = Class.forName("baritone.api.BaritoneAPI");
      getProvider = baritoneApi.getMethod("getProvider");
      getSettings = baritoneApi.getMethod("getSettings");
      getPrimaryBaritone =
          Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone");
      Class<?> iBaritone = Class.forName("baritone.api.IBaritone");
      getCustomGoalProcess = iBaritone.getMethod("getCustomGoalProcess");
      getMineProcess = iBaritone.getMethod("getMineProcess");
      getPathingBehavior = iBaritone.getMethod("getPathingBehavior");
      newGoalNear =
          Class.forName("baritone.api.pathing.goals.GoalNear")
              .getConstructor(BlockPos.class, int.class);
      setGoalAndPath =
          Class.forName("baritone.api.process.ICustomGoalProcess")
              .getMethod("setGoalAndPath", Class.forName("baritone.api.pathing.goals.Goal"));
      mineByName =
          Class.forName("baritone.api.process.IMineProcess")
              .getMethod("mineByName", int.class, String[].class);
      isActive = Class.forName("baritone.api.process.IBaritoneProcess").getMethod("isActive");
      Class<?> behaviorType = Class.forName("baritone.api.behavior.IPathingBehavior");
      getInProgress = behaviorType.getMethod("getInProgress");
      isPathing = behaviorType.getMethod("isPathing");
      cancelEverything = behaviorType.getMethod("cancelEverything");
    }
  }

  private static volatile Api cachedApi;
  private static BaritoneNavigation owner;
  private static String diagnostic = "idle";
  private final Map<Object, Object> previous = new LinkedHashMap<>();
  private Object instance;
  private boolean mining;

  private static Api api() throws ReflectiveOperationException {
    Api a = cachedApi;
    if (a == null) {
      a = new Api();
      cachedApi = a;
    }
    return a;
  }

  private Object acquire() throws ReflectiveOperationException {
    if (owner != null && owner != this)
      throw new IllegalStateException("another_navigation_active");
    Api a = api();
    Object provider = a.getProvider.invoke(null);
    instance = a.getPrimaryBaritone.invoke(provider);
    owner = this;
    return a.getSettings.invoke(null);
  }

  public boolean start(BlockPos target) {
    if (owner != null && owner != this) {
      diagnostic = "another_navigation_active";
      return false;
    }
    try {
      Object settings = acquire();
      Api a = api();
      boolean full = "FULL".equals(Store.config.permission) || Store.config.fullGameControl;
      setting(
          settings,
          "allowBreak",
          NavigationPermissions.canBreak()
              && (full || Store.config.allowMine)
              && MiningExpedition.navigationTerrain());
      setting(
          settings,
          "allowPlace",
          NavigationPermissions.canPlace()
              && (full || Store.config.allowPlace)
              && MiningExpedition.navigationTerrain());
      setting(settings, "allowBreakAnyway", java.util.List.of());
      setting(
          settings,
          "allowInventory",
          NavigationPermissions.canPlace() && MiningExpedition.navigationTerrain());
      setting(settings, "allowParkour", false);
      Object goal = a.newGoalNear.newInstance(target, 1);
      Object process = a.getCustomGoalProcess.invoke(instance);
      owner = this;
      a.setGoalAndPath.invoke(process, goal);
      diagnostic = "running";
      return true;
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      diagnostic = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
      stop();
      return false;
    }
  }

  boolean startMining(
      java.util.List<net.minecraft.world.level.block.Block> blocks,
      boolean terrain,
      int durability) {
    try {
      Object settings = acquire();
      Api a = api();
      setting(settings, "allowBreak", terrain);
      setting(settings, "allowBreakAnyway", java.util.List.copyOf(blocks));
      setting(settings, "allowPlace", terrain && NavigationPermissions.canPlace());
      setting(settings, "allowInventory", false);
      setting(settings, "autoTool", false);
      setting(settings, "allowParkour", false);
      setting(settings, "mineScanDroppedItems", false);
      setting(settings, "allowOnlyExposedOres", !terrain);
      setting(settings, "maxCachedWorldScanCount", 0);
      setting(settings, "itemSaver", true);
      setting(settings, "itemSaverThreshold", durability);
      Object process = a.getMineProcess.invoke(instance);
      String[] ids =
          blocks.stream()
              .map(b -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString())
              .toArray(String[]::new);
      mining = true;
      a.mineByName.invoke(process, 0, ids);
      diagnostic = "mining";
      return true;
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      diagnostic = "mine: " + e.getClass().getSimpleName() + " " + String.valueOf(e.getMessage());
      stop();
      return false;
    }
  }

  boolean miningActive() {
    if (!mining || instance == null) return false;
    try {
      Api a = api();
      Object process = a.getMineProcess.invoke(instance);
      return (boolean) a.isActive.invoke(process);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return false;
    }
  }

  private void setting(Object settings, String name, Object value)
      throws ReflectiveOperationException {
    Object setting = settings.getClass().getField(name).get(settings);
    Field field = setting.getClass().getField("value");
    previous.putIfAbsent(setting, field.get(setting));
    field.set(setting, value);
  }

  public boolean active() {
    if (instance == null) return false;
    try {
      Api a = api();
      Object behavior = a.getPathingBehavior.invoke(instance);
      java.util.Optional<?> calculation =
          (java.util.Optional<?>) a.getInProgress.invoke(behavior);
      return (boolean) a.isPathing.invoke(behavior) || calculation.isPresent();
    } catch (ReflectiveOperationException | RuntimeException e) {
      return false;
    }
  }

  public void stop() {
    if (instance != null) {
      try {
        Api a = api();
        Object behavior = a.getPathingBehavior.invoke(instance);
        a.cancelEverything.invoke(behavior);
      } catch (ReflectiveOperationException | RuntimeException e) {
        diagnostic = "cancel: " + e.getClass().getSimpleName();
      }
    }
    previous.forEach(
        (setting, value) -> {
          try {
            setting.getClass().getField("value").set(setting, value);
          } catch (ReflectiveOperationException e) {
            diagnostic = "restore: " + e.getClass().getSimpleName();
          }
        });
    previous.clear();
    instance = null;
    mining = false;
    if (owner == this) owner = null;
  }

  public static JsonObject status() {
    JsonObject result = new JsonObject();
    boolean available;
    try {
      api();
      available = true;
    } catch (ReflectiveOperationException | LinkageError e) {
      available = false;
    }
    result.addProperty("available", available);
    result.addProperty(
        "backend",
        owner == null ? "idle" : owner.mining ? "baritone_mining" : "baritone_navigation");
    result.addProperty("detail", diagnostic);
    result.addProperty("allow_break", NavigationPermissions.canBreak());
    result.addProperty("allow_place", NavigationPermissions.canPlace());
    return result;
  }
}
