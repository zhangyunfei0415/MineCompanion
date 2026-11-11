package dev.mibao.companion;

/** Screen-space rectangle intersections, including partially clipped widgets. */
public record GuiViewport(int left, int top, int right, int bottom) {
  public GuiViewport intersect(GuiViewport b) {
    return new GuiViewport(
        Math.max(left, b.left),
        Math.max(top, b.top),
        Math.min(right, b.right),
        Math.min(bottom, b.bottom));
  }

  public boolean visible() {
    return right > left && bottom > top;
  }

  public int centerX() {
    return left + (right - left) / 2;
  }

  public int centerY() {
    return top + (bottom - top) / 2;
  }
}
