package dev.mibao.companion;

/** One activation of a toggle/container per physical press; ordinary held use is untouched. */
public final class ManualUseLatch {
  private boolean down, used;

  public void press() {
    if (!down) {
      down = true;
      used = false;
    }
  }

  public void release() {
    down = false;
    used = false;
  }

  public boolean allow() {
    if (!down) return true;
    if (used) return false;
    used = true;
    return true;
  }
}
