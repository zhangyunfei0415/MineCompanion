package dev.mibao.companion;

/** Capacity policy only controls accepting ground drops, never permission to destroy a target. */
final class DropCapacityPolicy {
  static boolean allowGround(String policy) {
    return switch (policy) {
      case "allow_ground" -> true;
      case "require_capacity" -> false;
      default ->
          throw new IllegalArgumentException(
              "[INVALID_ARGUMENT] drop_policy为allow_ground或require_capacity");
    };
  }
}
