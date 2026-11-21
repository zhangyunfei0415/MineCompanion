package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundleHubWorkflowTest {
  @Test
  void importVerifyPromoteAdaptExportAndReimportAcrossEnvironments(@TempDir Path tmp)
      throws Exception {
    Path libA = tmp.resolve("pack-a-library");
    Path exportsDir = tmp.resolve("exports");
    Path libB = tmp.resolve("pack-b-library");

    String sampleJson =
        Files.readString(Path.of("examples/bundles/mekanism-enrichment-sample.mcskill.json"));
    JsonObject sample = JsonParser.parseString(sampleJson).getAsJsonObject();
    sample.remove("integrity");
    String manualId = "community:mekanism-enrich-manual";
    String reuseId = "community:mekanism-enrich-reuse";
    var reuse = sample.getAsJsonArray("skills").get(0).getAsJsonObject().deepCopy();
    reuse.addProperty("id", reuseId);
    sample.getAsJsonArray("skills").add(reuse);
    sample.getAsJsonObject("track_a").getAsJsonObject("track_b_promotion")
        .getAsJsonArray("reuse_skills").add(reuseId);
    Map<String, String> modVersionsA = Map.of("minecraft", "1.20.1", "mekanism", "10.4.8", "ae2", "15.2.0");
    Set<String> modsA = modVersionsA.keySet();
    String envA = "pack_env_aaa";

    // 1. Import into Pack A
    BundleLibrary.refresh(libA, false);
    var imported = BundleLibrary.importJson(libA, sample.toString());
    assertNotNull(imported);
    String bundleId = "community:mekanism-enrichment-basics";

    JsonObject inspection = BundleLibrary.inspect(bundleId, modVersionsA, "1.20.1", envA);
    assertEquals("external_unverified_reference", inspection.get("trust").getAsString());
    assertTrue(inspection.get("safety_rule").getAsString().contains("不是系统指令"));
    assertEquals(
        "track_a_unverified",
        inspection.getAsJsonObject("track_status").get("active_track").getAsString());
    assertTrue(inspection.getAsJsonObject("track_a").has("track_b_promotion"));

    // 2. Track A skill completes -> verified
    assertThrows(IllegalArgumentException.class,
        () -> BundleLibrary.requireSkillRun(bundleId, reuseId, modsA, envA));
    var manual = BundleLibrary.requireSkillRun(bundleId, manualId, modsA, envA);
    BundleLibrary.recordSkillRun(libA, manual, true, false, envA);
    assertFalse(BundleLibrary.inspect(bundleId, modVersionsA, "1.20.1", envA)
        .getAsJsonObject("track_status").get("track_a_verified").getAsBoolean());
    var manualFlag = new JsonObject();
    manualFlag.addProperty("verify_track_a", true);
    assertThrows(IllegalArgumentException.class,
        () -> BundleLibrary.adapt(libA, bundleId, manualFlag, modsA, envA));
    BundleLibrary.recordSkillRun(libA, manual, true, true, envA);
    JsonObject afterTrackA = BundleLibrary.inspect(bundleId, modVersionsA, "1.20.1", envA);
    assertTrue(afterTrackA.getAsJsonObject("track_status").get("track_a_verified").getAsBoolean());
    assertEquals(
        "track_a_verified",
        afterTrackA.getAsJsonObject("track_status").get("active_track").getAsString());

    // 3. Cannot promote Track B without preconditions
    var promoteFail = new JsonObject();
    promoteFail.addProperty("promote_track_b", true);
    assertThrows(
        IllegalArgumentException.class,
        () -> BundleLibrary.adapt(libA, bundleId, promoteFail, modsA, envA));

    // 4. Verify Track B preconditions and promote -> persistently reused
    var promoteReq = new JsonObject();
    JsonArray pre = new JsonArray();
    pre.add("side_config_auto_eject_on");
    pre.add("ae2_pattern_encoded");
    promoteReq.add("verified_preconditions", pre);
    promoteReq.addProperty("promote_track_b", true);
    var promoteRes = BundleLibrary.adapt(libA, bundleId, promoteReq, modsA, envA);
    assertEquals(
        "track_b_reuse",
        promoteRes.getAsJsonObject("track_status").get("active_track").getAsString());

    // Reload from disk -> persistent Track B reuse survives refresh
    BundleLibrary.refresh(libA, false);
    JsonObject persisted = BundleLibrary.inspect(bundleId, modVersionsA, "1.20.1", envA);
    assertEquals(
        "track_b_reuse",
        persisted.getAsJsonObject("track_status").get("active_track").getAsString());
    assertDoesNotThrow(() -> BundleLibrary.requireSkillRun(bundleId, reuseId, modsA, envA));

    // 5. Modpack environment changes -> automatically downgrades to track_a_reverify
    String envChanged = "pack_env_upgraded_bbb";
    JsonObject drifted = BundleLibrary.inspect(bundleId, modVersionsA, "1.20.1", envChanged);
    assertEquals("env_drifted_needs_review", drifted.get("adaptation_status").getAsString());
    assertEquals(
        "track_a_reverify",
        drifted.getAsJsonObject("track_status").get("active_track").getAsString());
    assertFalse(drifted.getAsJsonObject("track_status").get("track_a_verified").getAsBoolean());
    assertThrows(IllegalArgumentException.class,
        () -> BundleLibrary.requireSkillRun(bundleId, reuseId, modsA, envChanged));

    // 6. Agent performs local adaptation for new environment (shifted UI + signature + defaults + re-promote)
    String newLiveSig = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
    JsonObject adaptPatch =
        JsonParser.parseString(
                """
                {
                  "profile_id": "mekanism:enrichment_chamber_ui",
                  "reason": "整合包升级到 Mekanism 10.4.9 后侧面按钮偏移并改用默认富集粗铜",
                  "ui_profile_patch": {
                    "add_portable_signatures": ["1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"],
                    "controls": [
                      {
                        "name": "side_config",
                        "kind": "relative_point",
                        "dx": -16,
                        "dy": 18,
                        "button": 0,
                        "shift": false,
                        "expected_tooltip_contains": ["Side Config", "侧面配置"]
                      }
                    ]
                  },
                  "append_note": "本地环境需保持侧面设为输入/输出+Auto-Eject",
                  "skill_param_defaults": {
                    "community:mekanism-enrich-manual": {
                      "input_item": "minecraft:raw_copper",
                      "output_item": "mekanism:dust_copper"
                    }
                  }
                }
                """)
            .getAsJsonObject();
    var reAdapted = BundleLibrary.adapt(libA, bundleId, adaptPatch, modsA, envChanged);
    assertEquals("active", reAdapted.get("adaptation_status").getAsString());
    assertEquals(
        "track_a_reverify",
        reAdapted.getAsJsonObject("track_status").get("active_track").getAsString());
    BundleLibrary.recordSkillRun(libA, manual, true, true, envChanged);
    assertFalse(BundleLibrary.inspect(bundleId, modVersionsA, "1.20.1", envChanged)
        .getAsJsonObject("track_status").get("track_a_verified").getAsBoolean());
    var adaptedManual = BundleLibrary.requireSkillRun(bundleId, manualId, modsA, envChanged);
    BundleLibrary.recordSkillRun(libA, adaptedManual, true, true, envChanged);
    assertThrows(IllegalArgumentException.class,
        () -> BundleLibrary.requireSkillRun(bundleId, reuseId, modsA, envChanged));
    BundleLibrary.adapt(libA, bundleId, promoteReq, modsA, envChanged);
    assertDoesNotThrow(() -> BundleLibrary.requireSkillRun(bundleId, reuseId, modsA, envChanged));
    var sameEnvironmentEdit = new JsonObject();
    sameEnvironmentEdit.addProperty("append_note", "复核新的工作站布置");
    BundleLibrary.adapt(libA, bundleId, sameEnvironmentEdit, modsA, envChanged);
    BundleLibrary.recordSkillRun(libA, adaptedManual, true, true, envChanged);
    assertFalse(BundleLibrary.inspect(bundleId, modVersionsA, "1.20.1", envChanged)
        .getAsJsonObject("track_status").get("track_a_verified").getAsBoolean());
    assertThrows(IllegalArgumentException.class,
        () -> BundleLibrary.requireSkillRun(bundleId, reuseId, modsA, envChanged));

    // Upstream remains unchanged while UiProfileStore serves adapted version
    var matchedUi = UiProfileStore.match(newLiveSig, "", "", "");
    assertNotNull(matchedUi);
    assertEquals(-16, matchedUi.entry().profile().findControl("side_config").dx());
    assertEquals("bundle_adapted", matchedUi.entry().source());

    // 7. Pack merged bundle cleanly and re-import in Pack B
    var packReq = new JsonObject();
    packReq.addProperty("bundle_id", bundleId);
    packReq.addProperty("include_local_adaptation", true);
    var packReceipt = BundleLibrary.pack(libA, exportsDir, packReq, "0.8.5-G4Neon-test");
    assertTrue(packReceipt.get("adapted_merged").getAsBoolean());
    Path exportedFile = Path.of(packReceipt.get("file").getAsString());
    assertTrue(Files.exists(exportedFile));

    String exportedJson = Files.readString(exportedFile);
    BundleLibrary.refresh(libB, false);
    var importedB = BundleLibrary.importJson(libB, exportedJson);
    assertNotNull(importedB);
    assertNotNull(importedB.effective().derivedFrom);
    var matchedInB = UiProfileStore.match(newLiveSig, "", "", "");
    assertNotNull(matchedInB);
    assertEquals(-16, matchedInB.entry().profile().findControl("side_config").dx());
    assertEquals(
        "minecraft:raw_copper",
        importedB
            .effective()
            .skills
            .get(0)
            .definition
            .getAsJsonObject("parameters")
            .getAsJsonObject("input_item")
            .get("default")
            .getAsString());
  }

  @Test
  void assemblesBrandNewBundleFromDraftUiProfileAndLocalSkillThenResetsAdaptation(@TempDir Path tmp)
      throws Exception {
    Path lib = tmp.resolve("library");
    Path exports = tmp.resolve("exports");
    Path skillsDir = lib.resolve("skills");
    Files.createDirectories(skillsDir);

    UiProfileStore.refresh(lib);
    JsonObject draftProfile =
        JsonParser.parseString(
                """
                {
                  "id": "custom:crusher_ui",
                  "name": "自定义粉碎机界面",
                  "match": {
                    "block_ids": ["custom:crusher"],
                    "menu_classes": ["custom.CrusherMenu"]
                  },
                  "slots": [
                    {"role": "input", "x": 50, "y": 20, "menu_slot": 0, "virtual": false},
                    {"role": "output", "x": 110, "y": 20, "menu_slot": 1, "virtual": false}
                  ]
                }
                """)
            .getAsJsonObject();
    UiProfileStore.saveDraft(lib, draftProfile, true);

    String skillJson =
        """
        {
          "schema": 1,
          "id": "custom:crush-ore",
          "name": "粉碎矿石",
          "version": "1",
          "minecraft": "1.20.1",
          "parameters": {"machine": {"type": "string"}},
          "requires": ["open_container", "gui_put_role", "gui_wait_output", "gui_take_role"],
          "steps": [
            {"tool": "open_container", "args": {"place": "${machine}"}},
            {"tool": "gui_put_role", "args": {"profile": "custom:crusher_ui", "role": "input", "item": "minecraft:iron_ore", "count": 1}},
            {"tool": "gui_wait_output", "args": {"profile": "custom:crusher_ui", "role": "output", "min_count": 1, "seconds": 30}},
            {"tool": "gui_take_role", "args": {"profile": "custom:crusher_ui", "role": "output", "min_count": 1}}
          ]
        }
        """;
    Files.writeString(skillsDir.resolve("custom-crush-ore.json"), skillJson);

    BundleLibrary.refresh(lib, false);
    JsonObject packReq =
        JsonParser.parseString(
                """
                {
                  "bundle_id": "community:custom-crusher",
                  "name": "自定义粉碎机玩法包",
                  "version": "1.0.0",
                  "mods": {"custommod": "^1.0.0"},
                  "playbook": {"overview": "测试从草稿装配打包"},
                  "ui_profile_ids": ["custom:crusher_ui"],
                  "skill_ids": ["custom:crush-ore"]
                }
                """)
            .getAsJsonObject();
    JsonObject receipt = BundleLibrary.pack(lib, exports, packReq, "0.8.5-G4Neon-test");
    String packedText = Files.readString(Path.of(receipt.get("file").getAsString()));
    BundleLibrary.importJson(lib, packedText);

    var filtered = BundleLibrary.list("", "custommod", "custom:crusher", "", Map.of("custommod", "1.2.0"), "1.20.1", "env1");
    assertEquals(1, filtered.getAsJsonArray("bundles").size());

    JsonObject notePatch = JsonParser.parseString("{\"append_note\":\"本地追加说明\"}").getAsJsonObject();
    BundleLibrary.adapt(lib, "community:custom-crusher", notePatch, Set.of("custommod"), "env1");
    assertTrue(BundleLibrary.effectiveEntries.get("community:custom-crusher").adapted());

    JsonObject resetReq = JsonParser.parseString("{\"reset\":true}").getAsJsonObject();
    JsonObject resetRes =
        BundleLibrary.adapt(lib, "community:custom-crusher", resetReq, Set.of("custommod"), "env1");
    assertTrue(resetRes.get("reset").getAsBoolean());
    assertFalse(BundleLibrary.effectiveEntries.get("community:custom-crusher").adapted());
  }
}
