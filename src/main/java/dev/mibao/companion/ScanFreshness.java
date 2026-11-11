package dev.mibao.companion;

/** Coalesce local changes; an update during scanning cannot make the completed cache current. */
public final class ScanFreshness {
  private long revision, started, cached = -1;

  public void changed() {
    revision++;
  }

  public void begin() {
    started = revision;
  }

  public void complete() {
    cached = started;
  }

  public boolean dirty() {
    return cached != revision;
  }

  public boolean refresh(long age, boolean moved, boolean forced) {
    return forced || moved || age < 0 || age >= (dirty() ? 750 : 2500);
  }
}
