package dev.mibao.companion;

/** Compatibility name used by input gates. All editable pages live in the single desk. */
public final class Panel {
  public static boolean managing(Object screen) {
    return screen instanceof WorkbenchScreen
        || screen instanceof HudLayoutEditor
        || screen instanceof NoticeScreen
        || screen instanceof RecipeBrowserScreen
        || screen instanceof CompanionHistoryScreen;
  }

  private Panel() {}
}
