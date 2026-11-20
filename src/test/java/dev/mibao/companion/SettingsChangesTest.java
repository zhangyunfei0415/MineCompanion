package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class SettingsChangesTest {
  private static JsonObject value(String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }

  @Test
  void unrelatedLocalEditKeepsAnExternalDecisionEditUntilReload() {
    var saved = value("{\"decisionAutoSubmit\":true,\"hud\":true}");
    var disk = value("{\"decisionAutoSubmit\":false,\"hud\":true,\"externalField\":1}");
    var local = value("{\"decisionAutoSubmit\":true,\"hud\":false}");
    assertEquals(disk, SettingsChanges.merge(disk, saved, saved));
    assertEquals(
        value("{\"decisionAutoSubmit\":false,\"hud\":false,\"externalField\":1}"),
        SettingsChanges.merge(disk, saved, local));
  }

  @Test
  void reloadedValuesDoNotOverwriteASubsequentExternalEditOnUnrelatedSave() {
    var saved = value("{\"decisionAutoSubmit\":true,\"hud\":true}");
    var reloaded = value("{\"decisionAutoSubmit\":false,\"hud\":true}");
    var baseline = SettingsChanges.merge(saved, saved, reloaded);
    var disk = value("{\"decisionAutoSubmit\":true,\"hud\":true}");
    var local = value("{\"decisionAutoSubmit\":false,\"hud\":false}");
    assertEquals(
        value("{\"decisionAutoSubmit\":true,\"hud\":false}"),
        SettingsChanges.merge(disk, baseline, local));
  }
}
