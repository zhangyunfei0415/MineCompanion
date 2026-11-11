package dev.mibao.companion;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/** Optional depth-tested diagnostic boxes. Observed geometry is never an interaction result. */
public final class SceneOverlay {
  private SceneOverlay() {}

  public static void render(RenderLevelStageEvent e) {
    var mc = Minecraft.getInstance();
    if (!Store.config.debugScene
        || mc.level == null
        || mc.player == null
        || e.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
    var all =
        mc.level.getEntities(
            mc.player,
            mc.player.getBoundingBox().inflate(16),
            v -> v instanceof LivingEntity || v instanceof ItemEntity);
    all.sort(Comparator.comparingDouble(mc.player::distanceToSqr));
    var pose = e.getPoseStack();
    var camera = e.getCamera().getPosition();
    var buffers = mc.renderBuffers().bufferSource();
    var lines = buffers.getBuffer(RenderType.lines());
    pose.pushPose();
    pose.translate(-camera.x, -camera.y, -camera.z);
    for (int i = 0; i < Math.min(24, all.size()); i++) {
      var entity = all.get(i);
      boolean focus =
          Objects.equals(CombatEvidence.focus, entity.getUUID())
              || Objects.equals(HuntJob.focus, entity.getUUID());
      float r = focus ? 1f : entity instanceof ItemEntity ? .2f : 1f,
          g = focus ? .8f : entity instanceof ItemEntity ? 1f : .3f,
          b = focus ? .1f : .3f;
      LevelRenderer.renderLineBox(pose, lines, entity.getBoundingBox(), r, g, b, .85f);
    }
    pose.popPose();
    buffers.endBatch(RenderType.lines());
  }

  public static void hud(net.minecraft.client.gui.GuiGraphics g) {
    var mc = Minecraft.getInstance();
    if (!Store.config.debugScene || mc.player == null || mc.screen != null) return;
    String text = "执行诊断 · " + ActionSummary.describe(Actions.status);
    int y = mc.getWindow().getGuiScaledHeight() / 2 + 18;
    g.fill(
        6,
        y - 3,
        Math.min(mc.getWindow().getGuiScaledWidth() - 6, mc.font.width(text) + 14),
        y + 13,
        0xA0101820);
    g.drawString(mc.font, text, 10, y, 0xBDEBFF, false);
  }
}
