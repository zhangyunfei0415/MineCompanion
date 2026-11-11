package dev.mibao.companion;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.world.level.block.state.BlockState;

/** F3's client-visible state, plus optional public part-host observation, never a server grid. */
final class BlockOrientation {
  static void append(JsonObject out, BlockPos pos, BlockState state) {
    var props = new JsonObject();
    state.getValues().forEach((k, v) -> props.addProperty(k.getName(), name(k, v)));
    out.add("properties", props);
    out.addProperty("observed_at", System.currentTimeMillis());
    out.addProperty("source", "loaded_client_blockstate");
    var orientation = new JsonObject();
    for (String key :
        new String[] {"facing", "horizontal_facing", "axis", "rotation", "half", "part"})
      if (props.has(key)) orientation.add(key, props.get(key));
    orientation.addProperty("interpretation", "原始方向/轴向，不自动推断机器输入输出面或动力流向");
    out.add("orientation", orientation);
    var boxes = new JsonArray();
    state.getShape(Minecraft.getInstance().level, pos).toAabbs().stream()
        .limit(32)
        .forEach(
            b -> {
              var a = new JsonArray();
              for (double v : new double[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ})
                a.add(v);
              boxes.add(a);
            });
    out.add("interaction_shape_local", boxes);
    var parts = parts(pos);
    if (parts != null) out.add("client_visible_parts", parts);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static String name(
      net.minecraft.world.level.block.state.properties.Property property, Comparable value) {
    return property.getName(value);
  }

  static JsonArray parts(BlockPos pos) {
    var be = Minecraft.getInstance().level.getBlockEntity(pos);
    if (be != null && be.getClass().getName().startsWith("appeng.")) {
      try {
        var host = Class.forName("appeng.api.parts.IPartHost");
        if (host.isInstance(be)) {
          var parts = new JsonArray();
          var method = host.getMethod("getPart", Direction.class);
          for (Direction face :
              new Direction[] {
                null,
                Direction.DOWN,
                Direction.UP,
                Direction.NORTH,
                Direction.SOUTH,
                Direction.WEST,
                Direction.EAST
              }) {
            Object part = method.invoke(be, face);
            if (part != null) {
              var p = new JsonObject();
              p.addProperty("face", face == null ? "center" : face.getName());
              p.addProperty("class", part.getClass().getName());
              parts.add(p);
            }
          }
          return parts;
        }
      } catch (ReflectiveOperationException | LinkageError e) {
        return null;
      }
    }
    return null;
  }
}
