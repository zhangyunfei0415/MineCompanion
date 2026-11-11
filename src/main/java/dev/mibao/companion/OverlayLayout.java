package dev.mibao.companion;

/** Shared GUI-unit geometry. Reserves vanilla health/hotbar area and the chosen sidebar. */
public final class OverlayLayout {
  public record Rect(int x, int y, int width, int height) {
    public int right() {
      return x + width;
    }

    public int bottom() {
      return y + height;
    }

    public boolean overlaps(Rect b) {
      return x < b.right() && right() > b.x && y < b.bottom() && bottom() > b.y;
    }
  }

  public record Layout(Rect dialogue, Rect avatar, int hudBottom) {}

  public static int sidebarWidth(int width, int requested) {
    return Math.min(requested, Math.max(110, width - 176));
  }

  public static Layout calculate(
      int width,
      int height,
      boolean sidebar,
      boolean left,
      int sidebarWidth,
      int healthRows,
      boolean editing) {
    int reserve = Math.min(height / 2, Math.max(64, 52 + Math.max(0, healthRows - 1) * 10));
    int bottom = Math.max(48, height - reserve),
        side = sidebar ? sidebarWidth(width, sidebarWidth) + 16 : 0;
    int free = Math.max(0, width - side - 16), dw = Math.min(420, free), dh = editing ? 70 : 34;
    int dx = sidebar && left ? side + 8 : 8;
    Rect dialogue = new Rect(dx, Math.max(8, bottom - dh), dw, Math.min(dh, bottom - 8));
    int size = Math.min(68, Math.max(0, free - 16));
    Rect avatar = new Rect(dx, 36, size, size + 12);
    // In very short windows omit the avatar rather than overlap the local conversation.
    if (avatar.overlaps(dialogue) || avatar.bottom() > bottom) avatar = new Rect(dx, 8, 0, 0);
    return new Layout(dialogue, avatar, bottom - 6);
  }
}
