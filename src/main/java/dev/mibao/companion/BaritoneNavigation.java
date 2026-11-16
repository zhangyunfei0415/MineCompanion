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
  private boolean mining;

  private Object acquire() throws ReflectiveOperationException {
    if (owner != null && owner != this)
      throw new IllegalStateException("another_navigation_active");
    Class<?> api = Class.forName("baritone.api.BaritoneAPI");
    Object provider = api.getMethod("getProvider").invoke(null);
    instance =
        Class.forName("baritone.api.IBaritoneProvider")
            .getMethod("getPrimaryBaritone")
            .invoke(provider);
    owner = this;
    return api.getMethod("getSettings").invoke(null);
  }

  public boolean start(BlockPos target) {
    if (owner != null && owner != this) {
      diagnostic = "another_navigation_active";
      return false;
    }
    try {
      Object settings = acquire();
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
      Object goal =
          Class.forName("baritone.api.pathing.goals.GoalNear")
              .getConstructor(BlockPos.class, int.class)
              .newInstance(target, 1);
      Object process =
          Class.forName("baritone.api.IBaritone")
              .getMethod("getCustomGoalProcess")
              .invoke(instance);
      owner = this;
      Class.forName("baritone.api.process.ICustomGoalProcess")
          .getMethod("setGoalAndPath", Class.forName("baritone.api.pathing.goals.Goal"))
          .invoke(process, goal);
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
      boolean full = "FULL".equals(Store.config.permission) || Store.config.fullGameControl;
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
      Object process =
          Class.forName("baritone.api.IBaritone").getMethod("getMineProcess").invoke(instance);
      String[] ids =
          blocks.stream()
              .map(b -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString())
              .toArray(String[]::new);
      mining = true;
      Class.forName("baritone.api.process.IMineProcess")
          .getMethod("mineByName", int.class, String[].class)
          .invoke(process, 0, ids);
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
      Object process =
          Class.forName("baritone.api.IBaritone").getMethod("getMineProcess").invoke(instance);
      return (boolean)
          Class.forName("baritone.api.process.IBaritoneProcess")
              .getMethod("isActive")
              .invoke(process);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return false;
    }
  }

  private void setting(Object settings, String name, Object value)
      throws ReflectiveOperationException {
    Object setting = settings.getClass().getField(name).get(settings);
    Field field = setting.getClass().getField("value");
    previous.put(setting, field.get(setting));
    field.set(setting, value);
  }

  public boolean active() {
    try {
      Object behavior =
          Class.forName("baritone.api.IBaritone").getMethod("getPathingBehavior").invoke(instance);
      Class<?> behaviorType = Class.forName("baritone.api.behavior.IPathingBehavior");
      java.util.Optional<?> calculation =
          (java.util.Optional<?>) behaviorType.getMethod("getInProgress").invoke(behavior);
      return (boolean) behaviorType.getMethod("isPathing").invoke(behavior)
          || calculation.isPresent();
    } catch (ReflectiveOperationException | RuntimeException e) {
      return false;
    }
  }

  public void stop() {
    if (instance != null) {
      try {
        Object behavior =
            Class.forName("baritone.api.IBaritone")
                .getMethod("getPathingBehavior")
                .invoke(instance);
        Class.forName("baritone.api.behavior.IPathingBehavior")
            .getMethod("cancelEverything")
            .invoke(behavior);
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
      Class.forName("baritone.api.BaritoneAPI");
      available = true;
    } catch (ClassNotFoundException | LinkageError e) {
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
