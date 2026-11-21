package dev.mibao.companion;

/** Pure screen-bound pointer lease, independent of the operating-system cursor. */
public final class PointerState {
  private Object screen;
  private long epoch, until;
  private int width, height, buttons;
  private double x, y;

  public void begin(
      Object screen, long epoch, int width, int height, double x, double y, long now) {
    if (screen == null || width < 1 || height < 1)
      throw new IllegalArgumentException("GUI required");
    this.screen = screen;
    this.epoch = epoch;
    this.width = width;
    this.height = height;
    buttons = 0;
    move(x, y, now);
  }

  public void move(double x, double y, long now) {
    if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0 || x >= width || y >= height)
      throw new IllegalArgumentException("[INPUT_BOUNDS] 虚拟指针超出GUI");
    this.x = x;
    this.y = y;
    beat(now);
  }

  public void beat(long now) {
    until = now + 2_000_000_000L;
  }

  public boolean active(Object screen, long epoch, int width, int height, long now) {
    return this.screen != null
        && this.screen == screen
        && this.epoch == epoch
        && this.width == width
        && this.height == height
        && now < until;
  }

  public void button(int button, boolean down) {
    if (button < 0 || button > 2) throw new IllegalArgumentException("button");
    if (down) buttons |= 1 << button;
    else buttons &= ~(1 << button);
  }

  public boolean down(int button) {
    return button >= 0 && button <= 2 && (buttons & (1 << button)) != 0;
  }

  public double x() {
    return x;
  }

  public double y() {
    return y;
  }

  public void clear() {
    screen = null;
    buttons = 0;
    until = 0;
  }

  /**
   * Read-only bounded hover probe state (200ms TTL, decoupled from physical mouse buttons and
   * fullGameControl). Safe for offline JUnit verification.
   */
  public static final class HoverProbe {
    public static final long TTL_NANOS = 200_000_000L;

    private Object screen;
    private long epoch, untilNs;
    private int width, height;
    private double x, y;
    private double physicalX, physicalY;

    public void begin(
        Object screen,
        long epoch,
        int width,
        int height,
        double x,
        double y,
        double physicalX,
        double physicalY,
        long nowNs) {
      if (screen == null || width < 1 || height < 1) {
        throw new IllegalArgumentException("GUI required for probe");
      }
      if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0 || x >= width || y >= height) {
        throw new IllegalArgumentException("[INPUT_BOUNDS] 悬停探针坐标超出当前GUI");
      }
      this.screen = screen;
      this.epoch = epoch;
      this.width = width;
      this.height = height;
      this.x = x;
      this.y = y;
      this.physicalX = physicalX;
      this.physicalY = physicalY;
      this.untilNs = nowNs + TTL_NANOS;
    }

    public boolean active(Object screen, long epoch, int width, int height, long nowNs) {
      return this.screen != null
          && this.screen == screen
          && this.epoch == epoch
          && this.width == width
          && this.height == height
          && nowNs <= untilNs;
    }

    public boolean checkPhysicalMoved(double curPhysicalX, double curPhysicalY) {
      if (screen != null && Math.abs(curPhysicalX - physicalX) + Math.abs(curPhysicalY - physicalY) > 1.0) {
        clear();
        return true;
      }
      return false;
    }

    public double x() {
      return x;
    }

    public double y() {
      return y;
    }

    public void clear() {
      screen = null;
      untilNs = 0;
    }
  }
}
