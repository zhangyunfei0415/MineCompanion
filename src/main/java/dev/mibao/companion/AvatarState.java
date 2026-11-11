package dev.mibao.companion;

public final class AvatarState {
  public static int column(StatusFeed.Kind kind, boolean paused, boolean wood) {
    if (paused || kind == StatusFeed.Kind.REST || kind == StatusFeed.Kind.IDLE) return 0;
    if (kind == StatusFeed.Kind.HELP || kind == StatusFeed.Kind.WAITING) return 7;
    if (kind == StatusFeed.Kind.COMBAT) return 4;
    if (wood) return 3;
    return switch (kind) {
      case WALKING, STORAGE -> 1;
      case MINING -> 2;
      case THINKING, INTERFACE, EATING, SENSING, WORKING -> 5;
      default -> 0;
    };
  }

  public static int column(
      boolean paused, boolean approval, boolean enabled, String status, boolean wood) {
    if (approval || status.contains("失败") || status.contains("求助") || status.contains("不足"))
      return 7;
    if (paused || !enabled) return 0;
    if (status.contains("战斗")
        || status.contains("近战")
        || status.contains("索敌")
        || status.contains("清怪")
        || status.contains("自卫")) return 4;
    if (status.contains("熔炼")) return 6;
    if (status.contains("制作") || status.contains("生产")) return 5;
    if (status.contains("收集") || status.contains("搬运")) return 1;
    if (wood) return 3;
    if (status.contains("采矿") || status.contains("挖")) return 2;
    if (status.contains("前往")
        || status.contains("接近")
        || status.contains("收集")
        || status.contains("搬运")
        || status.contains("工作中")) return 1;
    return 0;
  }
}
