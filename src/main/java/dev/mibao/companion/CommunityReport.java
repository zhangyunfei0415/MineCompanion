package dev.mibao.companion;

/** Public brevity never discards the local task ledger. */
public final class CommunityReport {
  public static String mode(String member, String global) {
    return "DETAIL".equals(member) || "DEFAULT".equals(member) && "DETAIL".equals(global)
        ? "DETAIL"
        : "BRIEF";
  }

  public static boolean intermediate(
      String mode, boolean reply, boolean receipt, boolean alert, String text) {
    return "DETAIL".equals(mode)
        ? reply || receipt || alert
        : text.startsWith("待审批") || text.contains("[PERMISSION_REQUIRED]");
  }

  public static String compact(String text, String mode) {
    return Rules.clean(text, "DETAIL".equals(mode) ? 500 : 110).replace('\n', ' ');
  }

  private CommunityReport() {}
}
