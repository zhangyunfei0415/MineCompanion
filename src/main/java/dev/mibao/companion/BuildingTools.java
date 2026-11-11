package dev.mibao.companion;

import com.google.gson.*;
import net.minecraft.client.Minecraft;

/** Coordinates always denote the floor blocks, not the player's feet. */
public final class BuildingTools {
  public static PlatformPlan.Area area(JsonObject a, boolean bridge) {
    var p = Minecraft.getInstance().player;
    String direction = Agent.str(a, "direction", p.getDirection().getName());
    int x = Agent.number(a, "x", p.getBlockX()),
        y = Agent.number(a, "y", (int) Math.floor(p.getY() - .05)),
        z = Agent.number(a, "z", p.getBlockZ());
    if (bridge) {
      if (!a.has("x") && !a.has("z")) {
        var d = net.minecraft.core.Direction.byName(direction);
        if (d == null || d.getAxis().isVertical())
          throw new IllegalArgumentException("[ARGUMENT_INVALID] 桥方向须水平");
        x += d.getStepX();
        z += d.getStepZ();
      }
      return PlatformPlan.bridge(
          x,
          y,
          z,
          direction,
          SkillCatalog.bounded(a, "length", 16, 1, 64),
          SkillCatalog.bounded(a, "width", 1, 1, 5));
    }
    return PlatformPlan.rectangle(
        x,
        y,
        z,
        SkillCatalog.bounded(a, "width", 9, 1, 32),
        SkillCatalog.bounded(a, "depth", 9, 1, 32));
  }
}
