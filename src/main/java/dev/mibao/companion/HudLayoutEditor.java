package dev.mibao.companion;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** World overlay preview. Dragging only changes local drafts until Save is clicked. */
public final class HudLayoutEditor extends Screen {
  private final Screen parent;
  private boolean left;
  private int sidebarY, sidebarWidth, avatarX, avatarY, size;
  private int drag;
  private double dx, dy;

  public HudLayoutEditor(Screen parent) {
    super(Component.literal("观看布局"));
    this.parent = parent;
    left = Store.config.hudLeft;
    sidebarY = Store.config.hudY;
    sidebarWidth = Store.config.hudWidth;
    avatarX = Store.config.avatarX;
    avatarY = Store.config.avatarY;
    size = Store.config.avatarSize;
  }

  @Override
  protected void init() {
    addRenderableWidget(
        new DeskButton(
            "保存布局",
            8,
            height - 28,
            85,
            () -> {
              Store.config.hudLeft = left;
              Store.config.hudY = sidebarY;
              Store.config.hudWidth = sidebarWidth;
              Store.config.avatarX = avatarX;
              Store.config.avatarY = avatarY;
              Store.config.avatarSize = size;
              Store.save();
              minecraft.setScreen(parent);
            }));
    addRenderableWidget(
        new DeskButton(
            "复位预览",
            99,
            height - 28,
            85,
            () -> {
              left = false;
              sidebarY = 42;
              sidebarWidth = 190;
              avatarX = avatarY = -1;
              size = 68;
            }));
    addRenderableWidget(
        new DeskButton("取消", 190, height - 28, 60, () -> minecraft.setScreen(parent)));
  }

  private OverlayLayout.Rect side() {
    int w = OverlayLayout.sidebarWidth(width, sidebarWidth);
    return new OverlayLayout.Rect(
        left ? 8 : width - w - 8, Rules.bounded(sidebarY, 30, Math.max(30, height - 125)), w, 85);
  }

  private OverlayLayout.Rect avatar() {
    return new OverlayLayout.Rect(
        avatarX < 0 ? (left ? side().right() + 8 : 8) : Rules.bounded(avatarX, 0, width - size),
        avatarY < 0 ? 36 : Rules.bounded(avatarY, 28, height - size - 45),
        size,
        size);
  }

  private boolean inside(OverlayLayout.Rect r, double x, double y) {
    return GuiGeometry.inside(x, y, r.x(), r.y(), r.width(), r.height());
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float dt) {
    g.fill(0, 0, width, height, 0x99071622);
    g.drawString(font, "拖动侧栏/头像 · 滚轮改变所指区域大小 · 保存后生效", 8, 10, 0xffe7f4f2, false);
    var s = side();
    g.fill(s.x(), s.y(), s.right(), s.bottom(), 0xe0183546);
    g.fill(s.x(), s.y(), s.x() + 3, s.bottom(), 0xff72d3bf);
    g.drawString(font, "当前任务 / 实际动作", s.x() + 9, s.y() + 10, 0xffdeeeef, false);
    g.drawString(font, "进度、等待与核验结果", s.x() + 9, s.y() + 28, 0xffa7c6cf, false);
    var a = avatar();
    g.fill(a.x(), a.y(), a.right(), a.bottom(), 0xcc604c76);
    g.drawString(font, "伙伴形象", a.x() + 5, a.y() + size / 2, 0xfff1e3fd, false);
    g.fill(8, height - 80, width - 8, height - 40, 0x99617b89);
    g.drawString(font, "底部对话与快捷栏保留区", 12, height - 66, 0xffe0eeee, false);
    super.render(g, mx, my, dt);
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    if (super.mouseClicked(x, y, button)) return true;
    if (button != 0) return false;
    var a = avatar();
    var s = side();
    if (inside(a, x, y)) {
      drag = 2;
      dx = x - a.x();
      dy = y - a.y();
      return true;
    }
    if (inside(s, x, y)) {
      drag = 1;
      dx = x - s.x();
      dy = y - s.y();
      return true;
    }
    return false;
  }

  @Override
  public boolean mouseDragged(double x, double y, int button, double vx, double vy) {
    if (button != 0 || drag == 0) return false;
    if (drag == 1) {
      left = x < width / 2.0;
      sidebarY = Rules.bounded((int) (y - dy), 30, Math.max(30, height - 125));
    } else {
      avatarX = Rules.bounded((int) (x - dx), 0, Math.max(0, width - size));
      avatarY = Rules.bounded((int) (y - dy), 28, Math.max(28, height - size - 45));
    }
    return true;
  }

  @Override
  public boolean mouseReleased(double x, double y, int button) {
    drag = 0;
    return super.mouseReleased(x, y, button);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double delta) {
    if (inside(avatar(), x, y)) size = Rules.bounded(size + (int) (delta * 4), 32, 100);
    else sidebarWidth = Rules.bounded(sidebarWidth + (int) (delta * 8), 110, 300);
    return true;
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
