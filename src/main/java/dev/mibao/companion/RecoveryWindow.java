package dev.mibao.companion;

/** One bounded recovery episode; never resumes an interrupted task. */
public final class RecoveryWindow {
  public enum Step {
    NONE,
    RECOVERED,
    EAT,
    WAIT,
    HELP
  }

  private boolean active, attempted;
  private long until;

  public boolean active() {
    return active;
  }

  public void reset() {
    active = false;
    attempted = false;
    until = 0;
  }

  public Step step(
      long now, double health, double maximum, int food, boolean ordinaryFood, boolean safeMenu) {
    if (!active) {
      if (health >= SurvivalPolicy.critical(maximum)) return Step.NONE;
      active = true;
      until = now + 45_000;
    }
    if (health >= Math.min(maximum, SurvivalPolicy.work(maximum))) {
      reset();
      return Step.RECOVERED;
    }
    if (now >= until) return Step.HELP;
    if (food < 18 && !attempted && ordinaryFood && safeMenu) {
      attempted = true;
      return Step.EAT;
    }
    if (food < 18 && (!ordinaryFood || !safeMenu)) return Step.HELP;
    return Step.WAIT;
  }
}
