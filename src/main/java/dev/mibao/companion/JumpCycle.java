package dev.mibao.companion;

/** Ground launch pulse, flight and landing cooldown. Never spam jumps into a wall. */
public final class JumpCycle {
  private int launched = -100, landed = -100;
  private boolean airborne;

  public boolean step(boolean grounded, boolean requested, int tick) {
    if (!grounded) {
      airborne = true;
      return false;
    }
    if (airborne) {
      airborne = false;
      landed = tick;
      return false;
    }
    if (tick - landed < 4) return false;
    if (tick - launched < 3) return true;
    if (requested && tick - launched >= 10) {
      launched = tick;
      return true;
    }
    return false;
  }
}
