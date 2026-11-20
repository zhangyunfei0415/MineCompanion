package dev.mibao.companion;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

final class FollowTargetScreen extends Screen {
  private final Screen previous;
  private int page;
  private String message = "";

  FollowTargetScreen(Screen previous) {
    super(Component.literal("选择跟随玩家"));
    this.previous = previous;
  }

  @Override protected void init() {
    int x = width / 2 - 108, top = height < 270 ? 32 : height / 2 - 98;
    var targets = FollowControl.candidates();
    int visible = Math.max(1, Math.min(6, (height - top - 56) / 23));
    int pages = Math.max(1, (targets.size() + visible - 1) / visible);
    page = Math.min(page, pages - 1);
    for (int i = page * visible; i < Math.min(targets.size(), (page + 1) * visible); i++) {
      var target = targets.get(i);
      String suffix = target.player() == null ? " · 在线，暂不可见" : " · 附近";
      addRenderableWidget(Button.builder(Component.literal(target.name() + suffix), b -> {
        FollowControl.select(target);
        Companion.notifyUser("已选择跟随 " + target.name() + "，在本地对话输入「开始跟随」");
        onClose();
      }).bounds(x, top + (i % visible) * 23, 216, 20).build());
    }
    message = targets.isEmpty() ? "当前没有其他可跟随的玩家" : "";
    int y = top + visible * 23;
    addRenderableWidget(Button.builder(Component.literal("上一页"), b -> {
      page--;
      rebuildWidgets();
    }).bounds(x, y, 66, 20).build()).active = page > 0;
    addRenderableWidget(Button.builder(Component.literal("刷新"), b -> rebuildWidgets())
        .bounds(x + 75, y, 66, 20).build());
    addRenderableWidget(Button.builder(Component.literal("下一页"), b -> {
      page++;
      rebuildWidgets();
    }).bounds(x + 150, y, 66, 20).build()).active = page + 1 < pages;
    addRenderableWidget(Button.builder(Component.literal("选准星玩家"), b -> {
      if (minecraft.crosshairPickEntity instanceof Player p && p.isAlive()
          && p.level() == minecraft.level && p != minecraft.player
          && p.distanceToSqr(minecraft.player) <= 144) {
        FollowControl.select(new FollowControl.Target(p.getUUID(), p.getGameProfile().getName(), p));
        onClose();
      } else message = "先把准星对准附近的其他玩家";
    }).bounds(x, y + 24, 106, 20).build());
    addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
        .bounds(x + 110, y + 24, 106, 20).build());
  }

  @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    renderBackground(graphics);
    int top = height < 270 ? 32 : height / 2 - 98;
    graphics.drawCenteredString(font, title, width / 2, top - 25, 0xffffff);
    if (!message.isEmpty()) graphics.drawCenteredString(font, message, width / 2, top - 12, 0xddd6bb);
    super.render(graphics, mouseX, mouseY, partial);
  }

  @Override public void onClose() { minecraft.setScreen(previous); }
  @Override public boolean isPauseScreen() { return false; }
}
