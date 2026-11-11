package dev.mibao.companion;

import java.util.*;

/** Two roots; operation pages belong to ledger tasks instead of becoming global tabs. */
public final class PanelNavigation {
  public record Section(String name, int tab) {}

  public static List<Section> sections(boolean settings) {
    return settings
        ? List.of(
            new Section("模型与身份", 4),
            new Section("权限中心", 5),
            new Section("输入与按键", 12),
            new Section("显示与交流", 7),
            new Section("视觉与示范", 11),
            new Section("本地连接", 14),
            new Section("维护", 6))
        : List.of(
            new Section("当前任务", 0),
            new Section("基地与仓库", 1),
            new Section("世界与资源", 8),
            new Section("技能与经验", 3),
            new Section("世界记忆", 2),
            new Section("历史与备份", 15));
  }

  public static int section(int tab, boolean settings) {
    if (!settings && Set.of(9, 10, 12, 13).contains(tab)) return 0;
    for (var s : sections(settings)) if (s.tab() == tab) return tab;
    return settings ? 4 : 0;
  }
}
