package dev.mibao.companion;

import net.minecraft.client.gui.GuiGraphics;
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

  @Override
  protected void init() {
    int x = width / 2 - 112, top = height < 270 ? 44 : height / 2 - 90;
    var targets = FollowControl.candidates();
    int visible = Math.max(1, Math.min(6, (height - top - 70) / 24));
    int pages = Math.max(1, (targets.size() + visible - 1) / visible);
    page = Math.min(page, pages - 1);
    for (int i = page * visible; i < Math.min(targets.size(), (page + 1) * visible); i++) {
      var target = targets.get(i);
      String suffix = target.player() == null ? " · 在线，暂不可见" : " · 附近";
      addRenderableWidget(
          new DeskButton(
                  target.name() + suffix,
                  x,
                  top + (i % visible) * 24,
                  224,
                  21,
                  () -> {
                    FollowControl.select(target);
                    Companion.notifyUser("已选择跟随 " + target.name() + "，在本地对话输入「开始跟随」");
                    onClose();
                  })
              .badge(target.player() != null ? "附近" : "在线",
                  target.player() != null ? UiTheme.current().success() : UiTheme.current().textMuted()));
    }
    message = targets.isEmpty() ? "当前没有其他可跟随的玩家" : "";
    int y = top + visible * 24 + 4;
    var prev =
        new DeskButton(
            "上一页",
            x,
            y,
            70,
            20,
            () -> {
              page--;
              rebuildWidgets();
            });
    prev.active = page > 0;
    addRenderableWidget(prev);
    addRenderableWidget(new DeskButton("刷新", x + 76, y, 72, 20, this::rebuildWidgets));
    var next =
        new DeskButton(
            "下一页",
            x + 154,
            y,
            70,
            20,
            () -> {
              page++;
              rebuildWidgets();
            });
    next.active = page + 1 < pages;
    addRenderableWidget(next);

    addRenderableWidget(
        new DeskButton(
                "选准星玩家",
                x,
                y + 25,
                110,
                21,
                () -> {
                  if (minecraft.crosshairPickEntity instanceof Player p
                      && p.isAlive()
                      && p.level() == minecraft.level
                      && p != minecraft.player
                      && p.distanceToSqr(minecraft.player) <= 144) {
                    FollowControl.select(
                        new FollowControl.Target(p.getUUID(), p.getGameProfile().getName(), p));
                    onClose();
                  } else message = "先把准星对准附近的其他玩家";
                })
            .variant(DeskButton.Variant.PRIMARY));
    addRenderableWidget(new DeskButton("返回", x + 114, y + 25, 110, 21, this::onClose));
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float partial) {
    UiTheme t = UiTheme.current();
    g.fill(0, 0, width, height, t.backdrop());
    int cardX = width / 2 - 126, top = height < 270 ? 44 : height / 2 - 90;
    int cardY = Math.max(12, top - 34);
    int cardH = Math.min(height - 24, 240);
    UiDraw.fillCard(g, cardX, cardY, 252, cardH, t);
    UiDraw.fillAuroraBar(g, cardX + 1, cardY, 250, 2, t);
    g.drawCenteredString(font, title, width / 2, cardY + 10, t.accent());
    if (!message.isEmpty())
      g.drawCenteredString(font, message, width / 2, cardY + 23, t.warning());
    super.render(g, mx, my, partial);
  }

  @Override
  public void onClose() {
    minecraft.setScreen(previous);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
