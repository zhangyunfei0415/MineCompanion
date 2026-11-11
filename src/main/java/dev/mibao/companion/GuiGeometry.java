package dev.mibao.companion;

/** Distinguish framebuffer pixels, logical GUI coordinates and container-local slots. */
public final class GuiGeometry {
  public record Point(double x, double y) {}

  public static Point toGui(double x, double y, int pixelsW, int pixelsH, int guiW, int guiH) {
    if (pixelsW < 1
        || pixelsH < 1
        || guiW < 1
        || guiH < 1
        || !Double.isFinite(x)
        || !Double.isFinite(y)
        || x < 0
        || y < 0
        || x >= pixelsW
        || y >= pixelsH) throw new IllegalArgumentException("截图坐标或当前尺寸无效");
    return new Point(x * guiW / pixelsW, y * guiH / pixelsH);
  }

  public static boolean inside(double x, double y, int left, int top, int width, int height) {
    return x >= left && y >= top && x < left + width && y < top + height;
  }
}
