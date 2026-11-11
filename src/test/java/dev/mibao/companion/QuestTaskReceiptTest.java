package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class QuestTaskReceiptTest {
  private static JsonObject snapshot() {
    var s = new JsonObject();
    s.addProperty("task_id", 77L);
    s.addProperty("quest_id", 12L);
    s.addProperty("task_title", "铁锭收集");
    s.addProperty("quest_title", "第一章");
    s.addProperty("task_valid", true);
    s.addProperty("max_progress", 1L);
    s.addProperty("progress_before", 0L);
    s.addProperty("completed_before", false);
    s.addProperty("quest_completed_before", false);
    s.addProperty("can_start", true);
    s.addProperty("deps_complete", false);
    s.addProperty("locked", false);
    s.addProperty("editable", true);
    s.addProperty("auto_submit", 0);
    return s;
  }

  @Test
  void submitToolIsRegisteredAsGuiWriteAndDiscoverable() {
    assertTrue(Rules.TOOLS.contains("quest_task_submit"));
    assertTrue(Rules.WRITE.contains("quest_task_submit"));
    assertTrue(CapabilityCatalog.mutates("quest_task_submit"));
    JsonObject schema = null;
    for (var e : ToolSchemas.all()) {
      var fn = e.getAsJsonObject().getAsJsonObject("function");
      if (fn.get("name").getAsString().equals("quest_task_submit")) schema = fn;
    }
    assertNotNull(schema);
    var params = schema.getAsJsonObject("parameters");
    var required = params.getAsJsonArray("required").asList();
    assertTrue(required.stream().anyMatch(x -> x.getAsString().equals("quest_view")));
    assertTrue(required.stream().anyMatch(x -> x.getAsString().equals("widget")));
    var limited = new PermissionPolicy.Grants("LIMITED", false, false, false, false);
    assertFalse(PermissionPolicy.denial("quest_task_submit", limited).isEmpty());
    var full = new PermissionPolicy.Grants("FULL", false, false, false, false);
    assertEquals("", PermissionPolicy.denial("quest_task_submit", full));
  }

  @Test
  void receiptIsSingleLineChineseDiagnosticWithSnapshot() {
    String receipt = QuestTaskReceipt.receipt(snapshot(), 12);
    assertFalse(receipt.contains("\n"));
    assertTrue(receipt.contains("已发送FTB任务提交"));
    assertTrue(receipt.contains("任务#77"));
    assertTrue(receipt.contains("章节#12"));
    assertTrue(receipt.contains("进度=0/1"));
    assertTrue(receipt.contains("依赖完成=false"));
    assertTrue(receipt.contains("依赖未完成时FTB只记进度不标记完成"));
    assertTrue(receipt.contains("回执不等于服务端完成"));
  }

  @Test
  void lastSubmitSeparatesBeforeAndAfterAndExplainsDependencyIncompleteProgress() {
    var before = QuestTaskReceipt.lastSubmit(snapshot(), null, null, null, "等待复核");
    assertEquals(77L, before.get("task_id").getAsLong());
    assertEquals(12L, before.get("quest_id").getAsLong());
    assertEquals(0L, before.get("progress_before").getAsLong());
    assertTrue(before.get("progress_after").isJsonNull());
    assertTrue(before.get("completed_after").isJsonNull());
    assertFalse(before.get("confirmed").getAsBoolean());

    var after = QuestTaskReceipt.lastSubmit(snapshot(), 1L, false, false, "");
    assertEquals(1L, after.get("progress_after").getAsLong());
    assertFalse(after.get("completed_after").getAsBoolean());
    assertFalse(after.get("deps_complete").getAsBoolean());
    assertTrue(after.get("confirmed").getAsBoolean());
    String note = QuestTaskReceipt.verificationNote(snapshot(), 1L, false, false);
    assertTrue(note.contains("依赖未完成"));
    assertTrue(note.contains("不会把该任务标记完成"));
  }

  @Test
  void completedTaskAndMissingServerStateAreReportedDifferently() {
    assertTrue(QuestTaskReceipt.confirmed(snapshot(), null, true));
    assertEquals(
        "已确认任务完成（客户端任务状态已同步）", QuestTaskReceipt.verificationNote(snapshot(), 1L, true, true));
    assertFalse(QuestTaskReceipt.confirmed(snapshot(), 0L, false));
    assertTrue(QuestTaskReceipt.verificationNote(snapshot(), null, null, null).contains("复核未取得"));
    assertTrue(QuestTaskReceipt.verificationNote(snapshot(), 0L, false, true).contains("未观察到进度"));
  }
}
