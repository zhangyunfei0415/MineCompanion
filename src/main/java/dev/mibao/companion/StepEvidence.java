package dev.mibao.companion;

/** A control request or unverified button press cannot advance a portable production procedure. */
public final class StepEvidence {
  public static boolean complete(String tool, String result) {
    if (result == null || Rules.actionFailed(result)) return false;
    if (result.startsWith("成功：")) return true;
    if (result.startsWith("{") || result.startsWith("[")) return true;
    return tool.equals("go_to") && result.startsWith("已在目标高度")
        || tool.equals("close_screen") && result.equals("界面已经关闭。")
        || tool.equals("stow_cursor") && result.contains("没有持有物品");
  }
}
