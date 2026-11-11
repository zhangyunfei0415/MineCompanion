package dev.mibao.companion;

/** Turn before moving when changing direction sharply; never drift forward while facing away. */
public final class MovementSteering {
  public record Step(float yaw, boolean forward) {}

  public static Step toward(float current, float desired) {
    float delta = (desired - current) % 360;
    if (delta >= 180) delta -= 360;
    if (delta < -180) delta += 360;
    float turn = Math.max(-55, Math.min(55, delta));
    return new Step(current + turn, Math.abs(delta - turn) <= 35);
  }
}
