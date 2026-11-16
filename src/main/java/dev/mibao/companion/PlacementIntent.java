package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Optional desired properties constrain real stance and hit selection, then verify actual state.
 */
final class PlacementIntent {
  private final Map<String, String> values = new LinkedHashMap<>();

  PlacementIntent(JsonObject input, Block block) {
    if (input.size() > 16) throw new IllegalArgumentException("[INVALID_ARGUMENT] state最多16个属性");
    input
        .entrySet()
        .forEach(
            e -> {
              var property = block.getStateDefinition().getProperty(e.getKey());
              String value = e.getValue().getAsString();
              if (property == null || property.getValue(value).isEmpty())
                throw new IllegalArgumentException(
                    "[INVALID_BLOCK_STATE] 方块没有该属性值：" + e.getKey() + "=" + value);
              values.put(e.getKey(), value);
            });
  }

  boolean matches(BlockState state) {
    return values.entrySet().stream()
        .allMatch(
            e -> {
              var property = state.getBlock().getStateDefinition().getProperty(e.getKey());
              return property != null && value(state, property).equals(e.getValue());
            });
  }

  private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
    return property.getName(state.getValue(property));
  }

  Direction lookDirection(Block block) {
    Direction facing = Direction.byName(values.getOrDefault("facing", ""));
    if (facing == null || facing.getAxis().isVertical()) return null;
    if (block instanceof StairBlock
        || block instanceof BedBlock
        || block instanceof DoorBlock
        || block instanceof FenceGateBlock) return facing;
    return block instanceof HorizontalDirectionalBlock ? facing.getOpposite() : null;
  }

  boolean hitAllowed(BlockHitResult hit) {
    String axis = values.get("axis");
    if (axis != null && !hit.getDirection().getAxis().getName().equals(axis)) return false;
    String half = values.getOrDefault("half", values.getOrDefault("type", ""));
    if (!half.equals("top") && !half.equals("bottom")) return true;
    if (hit.getDirection() == Direction.DOWN) return half.equals("top");
    if (hit.getDirection() == Direction.UP) return half.equals("bottom");
    boolean top = hit.getLocation().y - hit.getBlockPos().getY() > .5;
    return top == half.equals("top");
  }
}
