package dev.mibao.companion;

/**
 * Pure planning for one FTB quest-book wheel event.
 *
 * <p>FTB dispatches wheel events through widget mouse-over flags instead of the event coordinates,
 * so the event must be aimed at a point inside the intended panel after the virtual pointer has
 * been moved there. This class only decides that point; the caller performs the reflection.
 */
public final class ScrollTarget {
  private ScrollTarget() {}

  /** Area token validation; returns the rejection message, or {@code null} when accepted. */
  public static String validate(String area) {
    return "chapters".equals(area) || "tree".equals(area)
        ? null
        : "[ARGUMENT_INVALID] area须为chapters或tree";
  }

  /**
   * Plans the wheel point for one panel.
   *
   * @param rect target panel rectangle {@code {x,y,width,height}}, or {@code null} when the panel
   *     cannot be read
   * @param screenWidth current GUI-scaled width
   * @param screenHeight current GUI-scaled height
   * @param fallbackFractionX fallback centre as a fraction of the width
   * @return the clamped wheel point, plus whether the chapter sidebar was collapsed off-screen
   */
  public static Plan plan(int[] rect, int screenWidth, int screenHeight, double fallbackFractionX) {
    boolean sidebarCollapsed = rect != null && rect[2] > 0 && rect[0] < 0;
    int x;
    int y;
    if (rect != null && rect[0] >= 0 && rect[2] > 0 && rect[3] > 0) {
      x = rect[0] + rect[2] / 2;
      y = rect[1] + rect[3] / 2;
    } else {
      x = (int) Math.round(screenWidth * fallbackFractionX);
      y = screenHeight / 2;
    }
    return new Plan(clamp(x, screenWidth), clamp(y, screenHeight), sidebarCollapsed);
  }

  private static int clamp(int value, int bound) {
    return Math.max(0, Math.min(Math.max(bound, 1) - 1, value));
  }

  /** One planned wheel event: GUI-scaled point plus whether the chapter sidebar was collapsed. */
  public static final class Plan {
    public final int x;
    public final int y;
    public final boolean sidebarCollapsed;

    public Plan(int x, int y, boolean sidebarCollapsed) {
      this.x = x;
      this.y = y;
      this.sidebarCollapsed = sidebarCollapsed;
    }
  }
}
