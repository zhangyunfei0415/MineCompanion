package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GuiProfilePolicyTest {
  private static final Set<String> ALL_12_TOOLS =
      Set.of(
          "inspect_tooltip",
          "gui_relative_point",
          "gui_put_role",
          "gui_take_role",
          "gui_trigger_control",
          "gui_wait_output",
          "ui_profile_draft",
          "bundle_list",
          "bundle_inspect",
          "bundle_adapt",
          "bundle_pack",
          "demo_summarize");

  @Test
  void all12NewToolsAreSynchronizedAcrossRulesAndSchemas() {
    assertTrue(Rules.TOOLS.containsAll(ALL_12_TOOLS));
    Set<String> schemaNames = new HashSet<>();
    for (JsonElement el : ToolSchemas.all()) {
      schemaNames.add(el.getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
    }
    assertEquals(Rules.TOOLS, schemaNames);

    var cat = CapabilityCatalog.describe("", Set.of("ae2", "jei"));
    assertEquals(2, cat.get("open_skill_bundle_schema").getAsInt());
    for (String guiTool :
        Set.of(
            "inspect_tooltip",
            "gui_relative_point",
            "gui_put_role",
            "gui_take_role",
            "gui_trigger_control",
            "gui_wait_output")) {
      assertEquals("interface", CapabilityCatalog.family(guiTool), guiTool);
    }
    for (String skillTool :
        Set.of("ui_profile_draft", "bundle_list", "bundle_inspect", "bundle_adapt", "bundle_pack")) {
      assertEquals("skills", CapabilityCatalog.family(skillTool), skillTool);
    }
    assertEquals("observation", CapabilityCatalog.family("demo_summarize"));
  }

  @Test
  void writeClassificationAndPermissionPolicyExemptReadOnlyTools() {
    Set<String> writeTools =
        Set.of("gui_relative_point", "gui_put_role", "gui_take_role", "gui_trigger_control");
    Set<String> readOnlyTools =
        Set.of(
            "inspect_tooltip",
            "gui_wait_output",
            "ui_profile_draft",
            "bundle_list",
            "bundle_inspect",
            "bundle_adapt",
            "bundle_pack",
            "demo_summarize");

    assertTrue(Rules.WRITE.containsAll(writeTools));
    for (String ro : readOnlyTools) assertFalse(Rules.WRITE.contains(ro), ro);

    var noGui = new PermissionPolicy.Grants("LIMITED", true, false, false, false);
    for (String w : writeTools) {
      assertFalse(PermissionPolicy.denial(w, noGui).isEmpty(), "Should deny " + w);
    }
    assertEquals("", PermissionPolicy.denial("inspect_tooltip", noGui));
    assertEquals("", PermissionPolicy.denial("gui_wait_output", noGui));

    var withGui = new PermissionPolicy.Grants("LIMITED", true, true, false, false);
    for (String w : writeTools) {
      assertEquals("", PermissionPolicy.denial(w, withGui), "Should allow " + w);
    }
  }

  @Test
  void sessionPlanStepEvidenceHarnessCapsAndToolMenuWorkTogether() {
    assertTrue(
        SessionPlan.TOOLS.containsAll(
            Set.of(
                "gui_put_role",
                "gui_take_role",
                "gui_trigger_control",
                "gui_wait_output",
                "ae_fetch",
                "ae_deposit")));

    assertFalse(StepEvidence.complete("gui_relative_point", "{\"status\":\"dispatched\",\"business_success\":false}"));
    assertFalse(StepEvidence.complete("gui_put_role", "部分完成：未达到预期"));
    assertFalse(StepEvidence.complete("gui_trigger_control", "失败：[TOOLTIP_MISMATCH] 偏移"));
    assertTrue(StepEvidence.complete("gui_put_role", "成功：已向 input 槽位投入原料"));
    assertTrue(StepEvidence.complete("gui_take_role", "成功：已从 output 槽位取出产物"));
    assertTrue(StepEvidence.complete("gui_wait_output", "成功：等待目标达成"));

    var h = HarnessCapabilities.describe(Rules.TOOLS.size());
    assertEquals(2, h.get("skill_bundle_schema").getAsInt());
    assertTrue(h.get("bundle_local_adaptation").getAsBoolean());
    assertTrue(h.get("track_b_inside_track_a_promotion").getAsBoolean());
    assertTrue(h.get("ui_profile_portable_signature").getAsBoolean());
    assertTrue(h.get("tooltip_probe_tool").getAsBoolean());
    assertTrue(h.get("container_data_sync").getAsBoolean());

    // Ensure farming profile compact menu constraint (<= 36) remains preserved
    var farmingMenu = ToolMenu.select(ToolSchemas.all(), "种植和收割", Set.of("jei", "ae2"));
    assertTrue(farmingMenu.size() <= 36, "farming menu exceeded 36: " + farmingMenu.size());
    assertEquals("production", ToolMenu.profile("帮我看下富集仓记忆包并做本地化适配"));
  }

  @Test
  void requiredNumericArgumentsRejectMissingOrInvalidWithoutNpe() {
    var empty = new JsonObject();
    var missingLong = assertThrows(IllegalArgumentException.class, () -> Rules.requiredLong(empty, "screen_view"));
    assertTrue(missingLong.getMessage().contains("[ARGUMENT_REQUIRED]"));

    var nullProp = new JsonObject();
    nullProp.add("dx", JsonNull.INSTANCE);
    var nullInt = assertThrows(IllegalArgumentException.class, () -> Rules.requiredInt(nullProp, "dx"));
    assertTrue(nullInt.getMessage().contains("[ARGUMENT_REQUIRED]"));

    var badType = new JsonObject();
    badType.addProperty("x", "not-a-number");
    var invalidDouble = assertThrows(IllegalArgumentException.class, () -> Rules.requiredDouble(badType, "x"));
    assertTrue(invalidDouble.getMessage().contains("[INVALID_ARGUMENT]"));

    var valid = new JsonObject();
    valid.addProperty("gui_view", 42L);
    valid.addProperty("dx", -12);
    valid.addProperty("x", 15.5);
    assertEquals(42L, Rules.requiredLong(valid, "gui_view"));
    assertEquals(-12, Rules.requiredInt(valid, "dx"));
    assertEquals(15.5, Rules.requiredDouble(valid, "x"));
  }
}
