package dev.mibao.companion;

import java.util.*;

/** No UI/runtime references: one family policy for every dispatch path. */
public final class PermissionPolicy {
  public record Grants(
      String mode, boolean containers, boolean gui, boolean drop, boolean entities) {}

  public static String denial(String tool, Grants g) {
    if ("FULL".equals(g.mode()) || !"LIMITED".equals(g.mode())) return "";
    if (tool.equals("drop_inventory") && !g.drop()) return "失败：[PERMISSION_REQUIRED] 有限模式未允许丢弃物品";
    if (tool.equals("use_entity") && !g.entities()) return "失败：[PERMISSION_REQUIRED] 有限模式未允许实体交互";
    if (Set.of(
                "fetch",
                "deposit",
                "transport",
                "restock",
                "open_container",
                "ae_fetch",
                "ae_deposit",
                "rs_fetch",
                "rs_deposit",
                "ae_request_craft",
                "ae_pattern_action",
                "ae_craft_plan",
                "ae_craft_confirm")
            .contains(tool)
        && !g.containers()) return "失败：[PERMISSION_REQUIRED] 有限模式未允许容器/仓储操作";
    if ((tool.startsWith("gui_")
            || tool.equals("quest_book_open")
            || tool.equals("game_input")
            || tool.equals("vanilla_menu_select")
            || tool.equals("jei_transfer_recipe")
            || tool.equals("ae_pattern_action")
            || tool.equals("ae_craft_plan")
            || tool.equals("ae_craft_confirm")
            || tool.equals("quest_book_click")
            || tool.equals("quest_task_submit")
            || tool.equals("click_slot")
            || tool.equals("write_sign"))
        && !g.gui()) return "失败：[PERMISSION_REQUIRED] 有限模式未允许GUI操作";
    return "";
  }
}
