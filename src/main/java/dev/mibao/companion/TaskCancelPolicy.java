package dev.mibao.companion;

/**
 * Pure routing precedence policy for {@code task_cancel}.
 * Zero {@code net.minecraft.*} dependencies for offline JUnit verification.
 */
public final class TaskCancelPolicy {
  public enum Route {
    GATEWAY_CANCEL,
    CANCEL_INFLIGHT_REQUEST,
    ABORT_SCAFFOLD,
    ABORT_BLUEPRINT,
    CANCEL_ACTIVE_FOREGROUND,
    NOT_FOUND
  }

  private TaskCancelPolicy() {}

  public static Route resolve(
      String id,
      boolean hasGatewayCheckpoint,
      boolean matchesInflightRequest,
      String activeJobKind) {
    if (id == null || id.isBlank()) return Route.NOT_FOUND;
    if (hasGatewayCheckpoint) return Route.GATEWAY_CANCEL;
    if (matchesInflightRequest) return Route.CANCEL_INFLIGHT_REQUEST;
    if ("scaffold".equalsIgnoreCase(id)) {
      return "scaffold".equals(activeJobKind) ? Route.ABORT_SCAFFOLD : Route.NOT_FOUND;
    }
    if ("blueprint".equalsIgnoreCase(id)) {
      return "blueprint".equals(activeJobKind) ? Route.ABORT_BLUEPRINT : Route.NOT_FOUND;
    }
    if ("active".equalsIgnoreCase(id)) {
      if ("scaffold".equals(activeJobKind)) return Route.ABORT_SCAFFOLD;
      if ("blueprint".equals(activeJobKind)) return Route.ABORT_BLUEPRINT;
      return activeJobKind != null && !activeJobKind.isBlank()
          ? Route.CANCEL_ACTIVE_FOREGROUND
          : Route.NOT_FOUND;
    }
    return Route.NOT_FOUND;
  }
}
