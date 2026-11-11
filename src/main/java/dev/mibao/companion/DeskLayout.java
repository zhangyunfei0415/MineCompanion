package dev.mibao.companion;

/** Logical GUI layout, independent of framebuffer resolution. */
public record DeskLayout(int nav, int left, int width, int top, int bottom) {
  public static DeskLayout of(int width, int height) {
    int nav = width >= 560 ? 116 : 86;
    return new DeskLayout(
        nav, nav + 16, Math.max(160, width - nav - 32), 70, Math.max(100, height - 34));
  }

  public int columns() {
    return width >= 310 ? 2 : 1;
  }

  public int columnWidth() {
    return columns() == 2 ? (width - 8) / 2 : width;
  }
}
