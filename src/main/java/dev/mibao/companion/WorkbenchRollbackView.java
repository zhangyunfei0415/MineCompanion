package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;

/**
 * Dedicated「破坏回滚日志」panel inside「历史与回滚」allowing players to review recent Agent
 * block mutations and launch honest client-only physical restoration (zero OP cheats).
 */
final class WorkbenchRollbackView {
  private final WorkbenchScreen ui;
  private boolean allowSubstitute;
  private boolean confirmClear;

  WorkbenchRollbackView(WorkbenchScreen ui) {
    this.ui = ui;
  }

  void render() {
    UiTheme t = ui.theme();
    var mc = Minecraft.getInstance();
    String dim = mc.level == null ? "" : mc.level.dimension().location().toString();
    var log = Store.data == null ? null : Store.rollbackLog();
    List<RollbackJournal.Entry> entries =
        log == null ? List.of() : RollbackJournal.list(log, dim, 24, "");

    ui.card(
        "破坏与建造回滚总控",
        entries.size() + " / " + RollbackJournal.MAX_ENTRIES,
        entries.isEmpty() ? t.textMuted() : t.warning(),
        () -> {
          ui.mutedText("纯客户端物理恢复（零 OP 作弊）：缺原方块诚实跳过，不把回滚自身动作写回日志。");
          ui.toggle(
              "允许用圆石替代恢复被挖掉的原石 (allow_substitute)",
              () -> allowSubstitute,
              v -> allowSubstitute = v);
          if (Actions.job instanceof RollbackJob) {
            ui.coloredText("正在执行物理回滚 · " + Actions.status, t.accent());
          }
          if (!entries.isEmpty()) {
            ui.row(
                "回滚最近 1 条",
                () -> triggerRestore(1, List.of()),
                "回滚最近 5 条",
                () -> triggerRestore(5, List.of()));
            ui.row(
                "回滚最近 10 条",
                () -> triggerRestore(10, List.of()),
                confirmClear ? "再次点击确认清空本维日志" : "清空本维度日志",
                () -> {
                  if (!confirmClear) {
                    confirmClear = true;
                    ui.setStatus("再次点击确认清空当前维度回滚日志");
                    ui.rebuild();
                  } else {
                    confirmClear = false;
                    int removed = RollbackJournal.clear(log, dim);
                    Store.saveSoon();
                    ui.setStatus("已清理本维度回滚日志 " + removed + " 条");
                    ui.rebuild();
                  }
                });
          }
        });

    ui.card(
        "最近变更记录（倒序）",
        dim.isBlank() ? "未进世界" : dim,
        t.accent(),
        () -> {
          if (entries.isEmpty()) {
            ui.mutedText("当前维度暂无 Agent 破坏或放置记录。");
            return;
          }
          int count = 0;
          for (var e : entries) {
            if (++count > 20) break;
            boolean isBreak = "BREAK".equals(e.action);
            String blockShown = isBreak ? e.beforeBlock : e.afterBlock;
            String line =
                "#"
                    + e.id
                    + " · ["
                    + (isBreak ? "挖除" : "放置")
                    + "] "
                    + blockShown
                    + " @ ("
                    + e.x
                    + ", "
                    + e.y
                    + ", "
                    + e.z
                    + ")"
                    + (e.tool.isBlank() ? "" : " · " + e.tool);
            if (e.hasBlockEntity) {
              ui.coloredText(line + "\n  ⚠ 含容器/机器实体，仅可放回空壳", t.warning());
            } else {
              ui.coloredText(line, isBreak ? t.warning() : t.text());
            }
            if (count <= 8) {
              long targetId = e.id;
              ui.button("复原此格 #" + targetId, () -> triggerRestore(1, List.of(targetId)));
            }
          }
        });
  }

  private void triggerRestore(int count, List<Long> ids) {
    confirmClear = false;
    var mc = Minecraft.getInstance();
    if (mc.level == null) throw new IllegalArgumentException("请先进入世界");
    var args = new JsonObject();
    args.addProperty("action", "restore");
    args.addProperty("allow_substitute", allowSubstitute);
    if (!ids.isEmpty()) {
      var arr = new JsonArray();
      for (Long id : ids) arr.add(id);
      args.add("ids", arr);
    } else {
      args.addProperty("count", count);
    }
    ui.onClose();
    if (!Agent.enabled) Agent.start();
    Agent.execute("rollback_journal", args, Companion::notifyUser);
  }
}
