package dev.mibao.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** One generated atlas, two characters, two styles and eight actual task states. */
public final class WatchAvatar {
  private static final ResourceLocation ATLAS =
      new ResourceLocation(Companion.ID, "textures/gui/watch_states.png");
  private static final ResourceLocation DRAGON =
      new ResourceLocation(Companion.ID, "textures/gui/dragon_states_client04.png");
  private static int dragonWidth, dragonHeight;
  private static int width, height;
  private static int[] columns;
  private static int[][] rows;

  public static void render(GuiGraphics g, int w, int h) {
    if (!WatchMode.active() || Store.config.avatarStyle.equals("OFF")) return;
    var mc = Minecraft.getInstance();
    if (mc.screen != null || mc.options.hideGui) return;
    if (width == 0)
      try (var stream = mc.getResourceManager().open(ATLAS);
          var image = com.mojang.blaze3d.platform.NativeImage.read(stream);
          var layout =
              new java.io.InputStreamReader(
                  mc.getResourceManager()
                      .open(new ResourceLocation(Companion.ID, "textures/gui/watch_states.json")),
                  java.nio.charset.StandardCharsets.UTF_8)) {
        width = image.getWidth();
        height = image.getHeight();
        var meta = com.google.gson.JsonParser.parseReader(layout).getAsJsonObject();
        columns = Store.GSON.fromJson(meta.get("columns"), int[].class);
        rows = Store.GSON.fromJson(meta.get("rows"), int[][].class);
        if (width != meta.get("width").getAsInt()
            || height != meta.get("height").getAsInt()
            || columns.length != 9
            || rows.length != 4) throw new IllegalArgumentException("atlas layout mismatch");
      } catch (Exception e) {
        width = -1;
        return;
      }
    if (width < 0) return;
    var view = Hud.localView();
    int column =
        AvatarState.column(
            view.kind, Agent.paused, Agent.resource != null && Agent.resource.wood());
    if (!Agent.paused
        && Actions.job != null
        && view.kind == StatusFeed.Kind.WORKING
        && Actions.status.contains("熔炼")) column = 6;
    int
        row =
            (Store.config.avatar.equals("CHATGPT") ? 1 : 0)
                + (Store.config.avatarStyle.equals("MC") ? 2 : 0),
        cellW = width / 8,
        cellH = height / 4;
    cellW = columns[column + 1] - columns[column];
    cellH = rows[row][1];
    var rect = Hud.layout(w, h).avatar();
    int x = rect.x(), y = rect.y(), size = rect.width();
    if (size < 32) return;
    boolean newDragon =
        Store.config.avatar.equals("CHATGPT") && !Store.config.avatarStyle.equals("MC");
    if (newDragon && dragonWidth == 0)
      try (var stream = mc.getResourceManager().open(DRAGON);
          var image = com.mojang.blaze3d.platform.NativeImage.read(stream)) {
        dragonWidth = image.getWidth();
        dragonHeight = image.getHeight();
      } catch (Exception e) {
        dragonWidth = -1;
      }
    com.mojang.blaze3d.systems.RenderSystem.enableBlend();
    com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
    g.enableScissor(x, y, x + size, y + size);
    if (newDragon && dragonWidth > 0) {
      int sx = column % 4 * dragonWidth / 4,
          sy = column / 4 * dragonHeight / 2,
          sw = (column % 4 + 1) * dragonWidth / 4 - sx,
          sh = (column / 4 + 1) * dragonHeight / 2 - sy;
      g.blit(DRAGON, x, y, size, size, (float) sx, (float) sy, sw, sh, dragonWidth, dragonHeight);
    } else
      g.blit(
          ATLAS,
          x,
          y,
          size,
          size,
          (float) columns[column],
          (float) rows[row][0],
          cellW,
          cellH,
          width,
          height);
    g.disableScissor();
    com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    String name = Store.config.avatar.equals("CHATGPT") ? "ChatGPT 龙娘" : "DeepSeek 鲸娘";
    name += " · " + view.kind.title;
    g.drawString(
        mc.font, mc.font.plainSubstrByWidth(name, size + 8), x, y + size, 0xffd5e7fb, true);
  }
}
