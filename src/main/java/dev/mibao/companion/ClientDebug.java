package dev.mibao.companion;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.*;

/** F3-style data from the loaded client only; no integrated-server object access. */
public final class ClientDebug {
  public static JsonObject inspect(BlockPos target) {
    var mc = Minecraft.getInstance();
    var o = new JsonObject();
    if (mc.player == null || mc.level == null) return o;
    var p = mc.player;
    var pos = p.blockPosition();
    o.addProperty("source", "loaded_client_debug_data");
    o.addProperty("observed_at_ms", System.currentTimeMillis());
    o.add("eye", vec(p.getEyePosition()));
    o.addProperty("yaw", p.getYRot());
    o.addProperty("pitch", p.getXRot());
    o.addProperty("facing", p.getDirection().getName());
    o.addProperty("on_ground", p.onGround());
    o.add("velocity", vec(p.getDeltaMovement()));
    o.addProperty("horizontal_collision", p.horizontalCollision);
    o.addProperty("chunk_x", pos.getX() >> 4);
    o.addProperty("chunk_z", pos.getZ() >> 4);
    o.addProperty("dimension", mc.level.dimension().location().toString());
    o.addProperty("fps", mc.getFps());
    o.addProperty("block_light", mc.level.getBrightness(LightLayer.BLOCK, pos));
    o.addProperty("sky_light", mc.level.getBrightness(LightLayer.SKY, pos));
    mc.level
        .getBiome(pos)
        .unwrapKey()
        .ifPresent(k -> o.addProperty("biome", k.location().toString()));
    var ray = p.pick(Math.min(4.5, mc.gameMode.getPickRange()), 1, false);
    if (ray instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
      var h = new JsonObject();
      h.addProperty("x", hit.getBlockPos().getX());
      h.addProperty("y", hit.getBlockPos().getY());
      h.addProperty("z", hit.getBlockPos().getZ());
      h.addProperty("face", hit.getDirection().getName());
      h.add("point", vec(hit.getLocation()));
      h.addProperty("distance", p.getEyePosition().distanceTo(hit.getLocation()));
      h.addProperty("state", mc.level.getBlockState(hit.getBlockPos()).toString());
      o.add("crosshair_block", h);
    }
    if (target != null) {
      var t = new JsonObject();
      boolean loaded = mc.level.hasChunkAt(target);
      t.addProperty("loaded", loaded);
      if (loaded) {
        var state = mc.level.getBlockState(target);
        t.addProperty("state", state.toString());
        var faces = new JsonObject();
        for (Direction d : Direction.values()) {
          var hit = Reach.faceFrom(target, p.getEyePosition(), d);
          if (hit == null) faces.addProperty(d.getName(), false);
          else faces.add(d.getName(), vec(hit.getLocation()));
        }
        t.add("reachable_faces", faces);
        var boxes = new JsonArray();
        for (var a : state.getShape(mc.level, target).toAabbs()) {
          if (boxes.size() >= 16) break;
          boxes.add(
              Store.GSON.toJsonTree(new double[] {a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ}));
        }
        t.add("outline_local_boxes", boxes);
        t.addProperty("guidance", "reachable_faces仅为当前眼位的真实射线；false可能是距离、遮挡或指定面不可见，不等于永远不可达。");
      } else t.addProperty("status", "unknown_unloaded");
      o.add("target", t);
    }
    o.addProperty("rule", "客户端调试数据不证明库存、产物或服务端处理完成；F3无需开启。实体几何见inspect_scene。");
    return o;
  }

  private static JsonArray vec(Vec3 v) {
    var a = new JsonArray();
    a.add(v.x);
    a.add(v.y);
    a.add(v.z);
    return a;
  }
}
