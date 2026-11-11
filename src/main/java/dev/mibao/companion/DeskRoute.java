package dev.mibao.companion;

/** One owner for every editor, independent of the entrance used to open it. */
public record DeskRoute(boolean settings, int page, String title) {
  public static DeskRoute of(int route, boolean controlSettings) {
    return switch (route) {
      case 1 -> new DeskRoute(false, 1, "地点与仓库");
      case 2 -> new DeskRoute(false, 1, "世界记忆与纠错");
      case 3 -> new DeskRoute(false, 2, "技能导入与导出");
      case 4 -> new DeskRoute(true, 0, "模型连接配置");
      case 5 -> new DeskRoute(true, 1, "进阶行为策略");
      case 6 -> new DeskRoute(true, 4, "备份与恢复");
      case 7 -> new DeskRoute(true, 3, "侧栏与交流");
      case 8 -> new DeskRoute(false, 1, "附近世界");
      case 9 -> new DeskRoute(false, 0, "补给与搬运");
      case 10 -> new DeskRoute(false, 2, "预制动作与农作");
      case 11 -> new DeskRoute(true, 2, "视觉辅助与示范");
      case 12 ->
          new DeskRoute(
              controlSettings, controlSettings ? 2 : 0, controlSettings ? "按键配置" : "背包操作");
      case 13 -> new DeskRoute(false, 0, "制作与熔炼");
      case 14 -> new DeskRoute(true, 0, "Harness与记录");
      case 15 -> new DeskRoute(false, 4, "任务与交付历史");
      case 16 -> new DeskRoute(true, 3, "人设与偏好");
      case 17 -> new DeskRoute(true, 1, "主人与授权成员");
      case 18 -> new DeskRoute(false, 0, "多人任务队列");
      case 19 -> new DeskRoute(false, 0, "统一委托与交付");
      case 20 -> new DeskRoute(false, 0, "常备背包");
      case 21 -> new DeskRoute(false, 0, "持续库存维护");
      case 22 -> new DeskRoute(false, 4, "委托结果与常用任务");
      default -> throw new IllegalArgumentException("未知工作台页面");
    };
  }
}
