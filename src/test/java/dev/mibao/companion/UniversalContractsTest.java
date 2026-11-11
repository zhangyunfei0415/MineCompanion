package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class UniversalContractsTest {
  @Test
  void catalogSeparatesMissingModsFromCurrentPreconditions() {
    var list = CapabilityCatalog.describe("ae_", Set.of()).getAsJsonArray("capabilities");
    assertFalse(list.isEmpty());
    for (var e : list)
      assertEquals("missing_component", e.getAsJsonObject().get("availability").getAsString());
    var installed =
        CapabilityCatalog.describe("rs_", Set.of("refinedstorage")).getAsJsonArray("capabilities");
    for (var e : installed) assertTrue(e.getAsJsonObject().get("component_present").getAsBoolean());
  }

  @Test
  void noWriteDisguisesItselfAsObservation() {
    for (String s : Rules.WRITE) assertTrue(CapabilityCatalog.mutates(s));
    for (String s :
        List.of(
            "drop_inventory",
            "use_entity",
            "gui_point",
            "select_slot",
            "open_inventory",
            "close_screen")) assertTrue(Rules.WRITE.contains(s));
  }

  @Test
  void finiteRequestsCannotBecomeProductionSuccess() {
    assertEquals(
        "reported_effect_unverified",
        ActionOutcome.describe("按钮已触发，效果待核验").get("outcome").getAsString());
    assertFalse(StepEvidence.complete("gui_select", "按钮已触发，效果待核验"));
    assertFalse(StepEvidence.complete("use_block", "部分完成：效果未知"));
    assertTrue(StepEvidence.complete("gui_select", "成功：槽位变化已核验"));
    assertFalse(ActionOutcome.describe("成功：已核验").get("replay_safe").getAsBoolean());
  }

  @Test
  void limitedGrantsApplyToBothLegacyAndNewWrites() {
    var g = new PermissionPolicy.Grants("LIMITED", false, false, false, false);
    for (String s :
        List.of(
            "fetch",
            "deposit",
            "transport",
            "rs_fetch",
            "ae_deposit",
            "gui_button",
            "gui_point",
            "click_slot",
            "drop_inventory",
            "use_entity")) assertFalse(PermissionPolicy.denial(s, g).isEmpty(), s);
    assertEquals("", PermissionPolicy.denial("observe", g));
    assertEquals(
        "",
        PermissionPolicy.denial(
            "drop_inventory", new PermissionPolicy.Grants("FULL", false, false, false, false)));
  }

  @Test
  void framebufferConversionsDoNotAssumeAnIntegerScale() {
    var a = GuiGeometry.toGui(1280, 720, 2560, 1440, 640, 360);
    assertEquals(320, a.x());
    assertEquals(180, a.y());
    var b = GuiGeometry.toGui(1000, 500, 2000, 1000, 667, 334);
    assertEquals(333.5, b.x());
    assertEquals(167, b.y());
  }

  @Test
  void coordinatesOutsideCurrentImageNeverProduceAClick() {
    assertThrows(
        IllegalArgumentException.class, () -> GuiGeometry.toGui(Double.NaN, 0, 100, 100, 50, 50));
    assertThrows(IllegalArgumentException.class, () -> GuiGeometry.toGui(100, 0, 100, 100, 50, 50));
    assertThrows(IllegalArgumentException.class, () -> GuiGeometry.toGui(1, 1, 0, 100, 50, 50));
    assertFalse(GuiGeometry.inside(26, 10, 10, 10, 16, 16));
  }

  @Test
  void compactModelMenuDoesNotRemoveFullHarnessSchemas() {
    var all = ToolSchemas.all();
    var selected = ToolMenu.select(all, "搬运木头", Set.of());
    assertTrue(selected.size() < all.size());
    assertEquals(Rules.TOOLS.size(), all.size());
    for (var e : selected) {
      String s = e.getAsJsonObject().getAsJsonObject("function").get("name").getAsString();
      assertFalse(s.startsWith("ae_"));
      assertFalse(s.startsWith("rs_"));
      assertNotEquals("drop_inventory", s);
    }
  }

  @Test
  void deskFitsSmallAndLargeLogicalWindows() {
    for (int w : List.of(320, 427, 640, 854, 1280))
      for (int h : List.of(240, 360, 720)) {
        var l = DeskLayout.of(w, h);
        assertTrue(l.left() >= l.nav());
        assertTrue(l.left() + l.width() <= w);
        assertTrue(l.bottom() < h);
        assertTrue(l.columnWidth() >= 160);
      }
  }

  @Test
  void fluentStyleReusesEvidenceAndOnlyAsksOneFallbackRefresh() {
    assertFalse(ExecutionStyle.needsRefresh("FLUENT", false, 4, false));
    assertTrue(ExecutionStyle.needsRefresh("FLUENT", false, 5, false));
    assertFalse(ExecutionStyle.needsRefresh("FLUENT", true, 100, false));
    assertFalse(ExecutionStyle.needsRefresh("FLUENT", false, 5, true));
    assertTrue(ExecutionStyle.needsRefresh("STRICT", false, 0, false));
    assertFalse(ExecutionStyle.fluent("unknown"));
  }

  @Test
  void incrementalServerEvidenceCanProveTransferWithoutReopeningButPredictionAloneCannot() {
    Object menu = new Object();
    var sync = new MenuSyncEvidence<String>(String::equals);
    sync.bind(menu, 4);
    sync.content(menu, 4, 1, List.of("iron:64", "empty"), "empty", 100);
    assertFalse(sync.ready(menu, 4, List.of("iron:32", "iron:32"), "empty", 400, 150));
    sync.slot(menu, 4, 2, 0, "iron:32", 500);
    assertFalse(sync.ready(menu, 4, List.of("iron:32", "iron:32"), "empty", 800, 150));
    sync.slot(menu, 4, 3, 1, "iron:32", 900);
    assertTrue(sync.ready(menu, 4, List.of("iron:32", "iron:32"), "empty", 1100, 150));
    assertTrue(
        TransferProof.verify(
                Map.of(),
                Map.of("iron", 64),
                Map.of("iron", 32),
                Map.of("iron", 32),
                Map.of("iron", 32),
                false)
            .verified());
    assertFalse(
        TransferProof.verify(
                Map.of(),
                Map.of("iron", 64),
                Map.of("iron", 32),
                Map.of("iron", 64),
                Map.of("iron", 32),
                false)
            .verified());
  }
}
